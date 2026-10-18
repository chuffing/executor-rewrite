package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.service.ConfigFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

/** 通过非空目标目录模拟第二份原子替换失败，只操作临时目录。 */
class DeployConfigFailureTest {

    @TempDir
    Path root;

    /** 第一份成功后第二份IO失败，保留第一份，重新上传相同文件能够补齐。 */
    @Test
    void shouldPreserveFirstPublishedFileAndCompleteRetry() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getOperation().setConfigFileDir(Files.createDirectory(root.resolve("configfiles")).toString());
        properties.getRedis().setConfigFile(root.resolve("redis.conf").toString());
        properties.getSentinel().setConfigFile(root.resolve("sentinel.conf").toString());
        ConfigFileService files = new ConfigFileServiceImpl(properties);
        DeployConfigServiceImpl realService = new DeployConfigServiceImpl(properties, files);
        // 非空目录不能被配置文件原子替换，确定性触发第二份发布失败。
        Path blockedTarget = Files.createDirectory(root.resolve("sentinel.conf"));
        Path marker = Files.write(blockedTarget.resolve("keep"), new byte[]{9});

        Map<String, String> params = upload(files);
        OperationResultDTO failure = realService.deployConfig(params);
        assertEquals(OperationErrorCode.COMMAND_EXECUTION_FAILED, failure.getErrorCode());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(root.resolve("redis.conf")));
        assertTrue(Files.isDirectory(blockedTarget));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(marker));
        try (Stream<Path> requests = Files.list(root.resolve("configfiles/staging"))) {
            assertEquals(0, requests.count());
        }
        try (Stream<Path> temporary = Files.list(root)) {
            assertFalse(temporary.anyMatch(path -> path.getFileName().toString().startsWith(".config-deploy-")));
        }
        // 仅移除测试创建的阻塞目录，再用相同任务内容重试。
        Files.delete(marker);
        Files.delete(blockedTarget);
        assertTrue(realService.deployConfig(upload(files)).isSuccess());
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(root.resolve("sentinel.conf")));
    }

    /** 创建新的独立暂存目录，模拟平台重新下发同一任务。 */
    private Map<String, String> upload(ConfigFileService files) throws Exception {
        byte[] redis = {1, 2, 3};
        byte[] sentinel = {4, 5, 6};
        String redisSha256 = sha256(redis);
        String sentinelSha256 = sha256(sentinel);
        Path directory = files.saveAndValidate("TASK202610170001", redisSha256, sentinelSha256,
                new MockMultipartFile("redisFile", redis), new MockMultipartFile("sentinelFile", sentinel));
        Map<String, String> params = new HashMap<>();
        params.put(TaskParamConstants.REDIS_CONFIG_SHA256, redisSha256);
        params.put(TaskParamConstants.SENTINEL_CONFIG_SHA256, sentinelSha256);
        params.put(TaskParamConstants.REDIS_CONFIG_LOCAL_PATH, directory.resolve("redis.conf").toString());
        params.put(TaskParamConstants.SENTINEL_CONFIG_LOCAL_PATH, directory.resolve("sentinel.conf").toString());
        return params;
    }

    /** 计算测试原始字节的SHA256。 */
    private String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
