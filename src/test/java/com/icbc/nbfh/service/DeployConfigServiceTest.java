package com.icbc.nbfh.service;

import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.service.impl.DeployConfigServiceImpl;
import com.icbc.nbfh.service.impl.ConfigFileServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 只在临时目录部署文本配置，不执行Redis或Shell。 */
class DeployConfigServiceTest {

    @TempDir
    Path root;

    private AgentProperties properties;
    private DeployConfigService service;
    private Map<String, String> params;
    private ConfigFileService files;
    private byte[] redisBytes;
    private byte[] sentinelBytes;
    private Path redisTarget;
    private Path sentinelTarget;

    /** 准备本地固定路径及包含中文和不同换行的配置文本。 */
    @BeforeEach
    void prepare() throws Exception {
        properties = new AgentProperties();
        redisTarget = root.resolve("redis.conf");
        sentinelTarget = root.resolve("sentinel.conf");
        properties.getRedis().setConfigFile(redisTarget.toString());
        properties.getSentinel().setConfigFile(sentinelTarget.toString());
        properties.getOperation().setConfigFileDir(Files.createDirectory(root.resolve("configfiles")).toString());
        files = new ConfigFileServiceImpl(properties);
        service = new DeployConfigServiceImpl(properties, files);
        redisBytes = "  # Redis配置\r\n\r\nport 6379\r\n".getBytes(StandardCharsets.UTF_8);
        sentinelBytes = "# Sentinel配置\n\nport 26379\n".getBytes(StandardCharsets.UTF_16);
        params = uploadParams();
    }

    /** 正常部署及相同内容重试均保留精确字节，结束后清理暂存文件。 */
    @Test
    void shouldDeployAndAllowIdenticalRetry() throws Exception {
        assertTrue(service.deployConfig(params).isSuccess());
        assertConfigContents();
        params = uploadParams();
        assertTrue(service.deployConfig(params).isSuccess());
        assertConfigContents();
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(redisTarget));
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(sentinelTarget));
        }
        assertNoTemporaryFiles();
    }

    /** 排队后任意一份摘要不匹配时，两份正式文件都不能发布，暂存文件须清理。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRejectEitherChecksumBeforePublishing(boolean redisChecksum) throws Exception {
        params.put(redisChecksum ? TaskParamConstants.REDIS_CONFIG_SHA256 : TaskParamConstants.SENTINEL_CONFIG_SHA256,
                sha256("wrong-content".getBytes(StandardCharsets.UTF_8)));
        assertEquals(OperationErrorCode.CHECKSUM_MISMATCH, service.deployConfig(params).getErrorCode());
        assertFalse(Files.exists(redisTarget));
        assertFalse(Files.exists(sentinelTarget));
        assertNoTemporaryFiles();
    }

    /** 已有Sentinel配置内容不同时，按当前原子覆盖流程更新两份配置。 */
    @Test
    void shouldReplaceExistingSentinelConfig() throws Exception {
        Files.write(sentinelTarget, "keep".getBytes(StandardCharsets.UTF_8));
        assertTrue(service.deployConfig(params).isSuccess());
        assertConfigContents();
        assertNoTemporaryFiles();
    }

    /** 已有Redis配置内容不同时，也应使用上传内容覆盖。 */
    @Test
    void shouldReplaceExistingRedisConfig() throws Exception {
        Files.write(redisTarget, "keep".getBytes(StandardCharsets.UTF_8));
        assertTrue(service.deployConfig(params).isSuccess());
        assertConfigContents();
        assertNoTemporaryFiles();
    }

    /** 先前只成功发布一份时，相同摘要重试能补齐另一份。 */
    @Test
    void shouldCompletePartialDeploymentOnRetry() throws Exception {
        Files.write(redisTarget, redisBytes);
        assertTrue(service.deployConfig(params).isSuccess());
        assertConfigContents();
        assertNoTemporaryFiles();
    }

    /** 已有文件匹配也不能跳过暂存文件在排队期间被改动的检查。 */
    @Test
    void shouldVerifyIncomingTextEvenWhenExistingFileMatches() throws Exception {
        byte[] original = redisBytes;
        Files.write(redisTarget, original);
        Files.write(Paths.get(params.get(TaskParamConstants.REDIS_CONFIG_LOCAL_PATH)),
                "different-staged-content".getBytes(StandardCharsets.UTF_8));
        assertEquals(OperationErrorCode.CHECKSUM_MISMATCH, service.deployConfig(params).getErrorCode());
        assertArrayEquals(original, Files.readAllBytes(redisTarget));
        assertFalse(Files.exists(sentinelTarget));
        assertNoTemporaryFiles();
    }

    /** 缺失父目录不会自动补建。 */
    @Test
    void shouldRejectMissingParent() throws Exception {
        properties.getSentinel().setConfigFile(root.resolve("missing/sentinel.conf").toString());
        assertEquals(OperationErrorCode.COMMAND_EXECUTION_FAILED, service.deployConfig(params).getErrorCode());
        assertFalse(Files.exists(root.resolve("missing")));
        assertFalse(Files.exists(redisTarget));
        assertNoTemporaryFiles();
    }

    /** 第一份目标为非空目录时原子替换失败，目录内容保留且两份暂存均清理。 */
    @Test
    void shouldFailWhenFirstTargetIsNonEmptyDirectory() throws Exception {
        Files.createDirectory(redisTarget);
        Path marker = Files.write(redisTarget.resolve("keep"), new byte[]{9});
        assertEquals(OperationErrorCode.COMMAND_EXECUTION_FAILED, service.deployConfig(params).getErrorCode());
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(marker));
        assertFalse(Files.exists(sentinelTarget));
        assertNoTemporaryFiles();
    }

    /** 并发上传使用独立目录，部署按执行器实际工作线程串行执行并清理。 */
    @Test
    void shouldDeployConcurrentUploadsSerially() throws Exception {
        files.deleteStagedFiles(Paths.get(params.get(TaskParamConstants.REDIS_CONFIG_LOCAL_PATH)).getParent());
        ExecutorService clients = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Map<String, String>>> uploads = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                uploads.add(clients.submit(() -> {
                    start.await();
                    return uploadParams();
                }));
            }
            start.countDown();
            for (Future<Map<String, String>> upload : uploads) {
                OperationResultDTO actual = service.deployConfig(upload.get(10, TimeUnit.SECONDS));
                assertTrue(actual.isSuccess(), actual.getMessage());
            }
        } finally {
            clients.shutdownNow();
            assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertConfigContents();
        assertNoTemporaryFiles();
    }

    /** 排队期间暂存文件丢失时返回执行失败，并清理剩余文件。 */
    @Test
    void shouldCleanWhenStagedFileIsMissing() throws Exception {
        Files.delete(Paths.get(params.get(TaskParamConstants.REDIS_CONFIG_LOCAL_PATH)));
        assertEquals(OperationErrorCode.COMMAND_EXECUTION_FAILED, service.deployConfig(params).getErrorCode());
        assertFalse(Files.exists(redisTarget));
        assertFalse(Files.exists(sentinelTarget));
        assertNoTemporaryFiles();
    }

    /** 精确比较原始字节，确保UTF-8、UTF-16及换行未被改写。 */
    private void assertConfigContents() throws IOException {
        assertArrayEquals(redisBytes,
                Files.readAllBytes(redisTarget));
        assertArrayEquals(sentinelBytes,
                Files.readAllBytes(sentinelTarget));
    }

    /** 测试目录中不应遗留任何本次配置部署临时文件。 */
    private void assertNoTemporaryFiles() throws IOException {
        try (Stream<Path> files = Files.list(root)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".config-deploy-")));
        }
        try (Stream<Path> requests = Files.list(root.resolve("configfiles/staging"))) {
            assertEquals(0, requests.count());
        }
    }

    /** 为每次部署或重试重新上传独立的两份配置。 */
    private Map<String, String> uploadParams() throws Exception {
        String redisSha256 = sha256(redisBytes);
        String sentinelSha256 = sha256(sentinelBytes);
        Path directory = files.saveAndValidate("TASK202610170001", redisSha256, sentinelSha256,
                new MockMultipartFile("redisFile", redisBytes), new MockMultipartFile("sentinelFile", sentinelBytes));
        Map<String, String> result = new HashMap<>();
        result.put(TaskParamConstants.REDIS_CONFIG_SHA256, redisSha256);
        result.put(TaskParamConstants.SENTINEL_CONFIG_SHA256, sentinelSha256);
        result.put(TaskParamConstants.REDIS_CONFIG_LOCAL_PATH, directory.resolve("redis.conf").toString());
        result.put(TaskParamConstants.SENTINEL_CONFIG_LOCAL_PATH, directory.resolve("sentinel.conf").toString());
        return result;
    }

    /** 独立计算测试报文摘要，不依赖待测服务的写入逻辑。 */
    private String sha256(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
