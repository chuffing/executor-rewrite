package com.icbc.nbfh.service;

import com.icbc.nbfh.common.Enum.ParameterErrorCode;
import com.icbc.nbfh.common.constant.ConfigFileConstants;
import com.icbc.nbfh.common.exception.ParameterException;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.service.impl.ConfigFileServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原始字节接收及清理边界测试，仅使用临时目录。 */
class ConfigFileServiceTest {

    private static final String STAGING_DIRECTORY_NAME = "staging";

    @TempDir
    Path root;

    private ConfigFileService files;
    private Path configFiles;
    private AgentProperties properties;
    private byte[] redis;
    private byte[] sentinel;

    /** 使用带注释、空行和不同编码的数据，不依赖真实Redis配置。 */
    @BeforeEach
    void prepare() throws Exception {
        properties = new AgentProperties();
        configFiles = Files.createDirectory(root.resolve("configfiles"));
        properties.getOperation().setConfigFileDir(configFiles.toString());
        files = new ConfigFileServiceImpl(properties);
        redis = "# 中文注释\r\n\r\n  port 6379\n".getBytes(StandardCharsets.UTF_8);
        sentinel = "# UTF16注释\r\n\r\nport 26379\r\n".getBytes(StandardCharsets.UTF_16);
    }

    /** 忽略上传文件名，使用固定名称保留原始编码及所有字节。 */
    @Test
    void shouldSaveOriginalBytesWithFixedNamesAndPrivatePermissions() throws Exception {
        Path directory = upload();
        assertTrue(directory.getFileName().toString().startsWith("TASK202610170001-config-"));
        assertArrayEquals(redis, Files.readAllBytes(directory.resolve(ConfigFileConstants.REDIS_FILE_NAME)));
        assertArrayEquals(sentinel, Files.readAllBytes(directory.resolve(ConfigFileConstants.SENTINEL_FILE_NAME)));
        assertEquals(configFiles.resolve(STAGING_DIRECTORY_NAME), directory.getParent());
        if (Files.getFileStore(configFiles).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(directory.resolve(ConfigFileConstants.REDIS_FILE_NAME)));
        }
        files.deleteStagedFiles(directory);
        assertFalse(Files.exists(directory));
    }

    /** 配置上传只使用独立配置根目录，不要求版本包目录存在。 */
    @Test
    void shouldUseConfigRootIndependentlyFromPackageRoot() throws Exception {
        Path unusedPackages = root.resolve("missing-packages");
        properties.getOperation().setPackageDir(unusedPackages.toString());
        Path directory = upload();
        assertEquals(configFiles.toRealPath().resolve(STAGING_DIRECTORY_NAME),
                directory.getParent());
        assertFalse(Files.exists(unusedPackages));
        files.deleteStagedFiles(directory);
        assertFalse(Files.exists(directory));
        assertTrue(Files.isDirectory(configFiles));
    }

    /** 按当前实现创建配置暂存目录，不回退到版本包根目录。 */
    @Test
    void shouldCreateStagingUnderConfiguredRoot() throws Exception {
        Path configured = root.resolve("new-configfiles");
        properties.getOperation().setConfigFileDir(configured.toString());
        properties.getOperation().setPackageDir(configFiles.toString());
        Path directory = upload();
        assertEquals(configured.resolve(STAGING_DIRECTORY_NAME), directory.getParent());
        assertTrue(Files.exists(directory.resolve(ConfigFileConstants.REDIS_FILE_NAME)));
        assertTrue(Files.exists(directory.resolve(ConfigFileConstants.SENTINEL_FILE_NAME)));
        assertFalse(Files.exists(configFiles.resolve(STAGING_DIRECTORY_NAME)));
        files.deleteStagedFiles(directory);
        assertFalse(Files.exists(directory));
    }

    /** 旧packages目录中的配置文件不能被配置暂存清理逻辑删除。 */
    @Test
    void shouldRejectAndPreserveOldPackageStagingFiles() throws Exception {
        Path packages = Files.createDirectory(root.resolve("packages"));
        properties.getOperation().setPackageDir(packages.toString());
        Path oldDirectory = Files.createDirectories(packages.resolve("staging/TASK202610170001-config-old"));
        Path oldRedis = Files.write(oldDirectory.resolve(ConfigFileConstants.REDIS_FILE_NAME), redis);
        Path oldSentinel = Files.write(oldDirectory.resolve(ConfigFileConstants.SENTINEL_FILE_NAME), sentinel);
        files.deleteStagedFiles(oldDirectory);
        assertArrayEquals(redis, Files.readAllBytes(oldRedis));
        assertArrayEquals(sentinel, Files.readAllBytes(oldSentinel));
        Path directory = upload();
        files.deleteStagedFiles(directory);
        assertFalse(Files.exists(directory));
        assertArrayEquals(redis, Files.readAllBytes(oldRedis));
    }

    /** Redis或Sentinel摘要不一致均清理本次目录及已保存的另一份文件。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldCleanWhenEitherChecksumIsWrong(boolean redisChecksum) throws Exception {
        ParameterException exception = assertThrows(ParameterException.class, () -> files.saveAndValidate(
                "TASK202610170001", sha256(redisChecksum ? new byte[]{1} : redis),
                sha256(redisChecksum ? sentinel : new byte[]{1}), part(redis), part(sentinel)));
        assertEquals(ParameterErrorCode.CONFIG_CHECKSUM_MISMATCH.getCode(), exception.getCode());
        assertNoRequests();
    }

    /** 第二份文件流读取失败时，也能清理已经保存的第一份。 */
    @Test
    void shouldCleanWhenSecondStreamFails() throws Exception {
        MultipartFile broken = mock(MultipartFile.class);
        when(broken.getInputStream()).thenThrow(new IOException("test-read-failure"));
        assertThrows(IOException.class, () -> files.saveAndValidate("TASK202610170001",
                sha256(redis), sha256(sentinel), part(redis), broken));
        assertNoRequests();
    }

    /** 实际空流及长度不足不能被当作成功上传。 */
    @Test
    void shouldRejectEmptyOrTruncatedStream() throws Exception {
        for (byte[] bytes : new byte[][]{new byte[0], redis}) {
            MockMultipartFile misleading = new MockMultipartFile("file", bytes) {
                /** 声称比实际流多一个字节。 */
                @Override
                public long getSize() {
                    return bytes.length + 1;
                }
            };
            assertThrows(ParameterException.class, () -> files.saveAndValidate("TASK202610170001",
                    sha256(bytes), sha256(sentinel), misleading, part(sentinel)));
            assertNoRequests();
        }
    }

    /** 两次上传独立隔离，清理本次文件不能影响另一任务或正式配置。 */
    @Test
    void shouldOnlyCleanOwnDirectory() throws Exception {
        Path first = upload();
        Path second = upload();
        Path formal = Files.write(root.resolve("redis.conf"), redis);
        files.deleteStagedFiles(first);
        assertFalse(Files.exists(first));
        assertArrayEquals(redis, Files.readAllBytes(second.resolve(ConfigFileConstants.REDIS_FILE_NAME)));
        assertArrayEquals(redis, Files.readAllBytes(formal));
        files.deleteStagedFiles(root);
        assertArrayEquals(redis, Files.readAllBytes(formal));
    }

    /** staging范围外的目录即使有同名文件，也不能被配置清理逻辑删除。 */
    @Test
    void shouldRejectDirectoryOutsideStaging() throws Exception {
        Path other = Files.createDirectories(root.resolve("other/TASK202610170001-config-test"));
        Path file = Files.write(other.resolve(ConfigFileConstants.REDIS_FILE_NAME), redis);
        files.deleteStagedFiles(other);
        assertArrayEquals(redis, Files.readAllBytes(file));
    }

    /** 清理失败只记录日志，继续清理另一份文件，不递归删除未知内容。 */
    @Test
    void shouldContinueCleanupAfterOneFileCannotBeDeleted() throws Exception {
        Path directory = upload();
        Path sentinelPath = directory.resolve(ConfigFileConstants.SENTINEL_FILE_NAME);
        Files.delete(sentinelPath);
        Files.createDirectory(sentinelPath);
        Path unknown = Files.write(sentinelPath.resolve("keep"), new byte[]{1});
        assertDoesNotThrow(() -> files.deleteStagedFiles(directory));
        assertFalse(Files.exists(directory.resolve(ConfigFileConstants.REDIS_FILE_NAME)));
        assertTrue(Files.exists(unknown));
        assertTrue(Files.isDirectory(directory));
    }

    /** 相同任务编号并发上传仍使用不同目录。 */
    @Test
    void shouldIsolateConcurrentUploads() throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(4);
        List<Path> directories = new ArrayList<>();
        try {
            List<Future<Path>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(clients.submit(this::upload));
            }
            for (Future<Path> result : results) {
                directories.add(result.get(10, TimeUnit.SECONDS));
            }
            assertEquals(8, new HashSet<>(directories).size());
            for (Path directory : directories) {
                assertArrayEquals(redis, Files.readAllBytes(directory.resolve(ConfigFileConstants.REDIS_FILE_NAME)));
                files.deleteStagedFiles(directory);
            }
            assertNoRequests();
        } finally {
            clients.shutdownNow();
            assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** 使用已有编号规则上传两份测试文件。 */
    private Path upload() throws Exception {
        return files.saveAndValidate("TASK202610170001", sha256(redis), sha256(sentinel), part(redis), part(sentinel));
    }

    /** 不允许实现通过getBytes把整个上传文件载入内存，文件名也不可信。 */
    private MockMultipartFile part(byte[] bytes) {
        return new MockMultipartFile("file", "../../uncontrolled.conf", "application/octet-stream", bytes) {
            /** 测试仅允许流式读取上传对象。 */
            @Override
            public byte[] getBytes() {
                throw new AssertionError("上传服务不得调用getBytes");
            }
        };
    }

    /** 不应残留失败上传的任务目录。 */
    private void assertNoRequests() throws IOException {
        try (Stream<Path> requests = Files.list(configFiles.resolve("staging"))) {
            assertEquals(0, requests.count());
        }
    }

    /** 按原始字节生成可信预期摘要。 */
    private String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
