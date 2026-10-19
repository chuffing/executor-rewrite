package com.icbc.nbfh.service;

import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.service.impl.RestoreConfigServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 在临时目录中确认工具类调整不会破坏配置恢复。 */
class RestoreConfigServiceTest {

    @TempDir
    Path root;

    private RestoreConfigService service;
    /** 准备两份可信备份和独立的临时配置目录。 */
    @BeforeEach
    void prepare() throws Exception {
        AgentProperties properties = new AgentProperties();
        Path redisBackup = Files.write(root.resolve("redis.backup"),
                "redis-backup".getBytes(StandardCharsets.UTF_8));
        Path sentinelBackup = Files.write(root.resolve("sentinel.backup"),
                "sentinel-backup".getBytes(StandardCharsets.UTF_8));
        properties.getRedis().setBackupFile(redisBackup.toString());
        properties.getSentinel().setBackupFile(sentinelBackup.toString());
        properties.getRedis().setConfigFile(root.resolve("redis.conf").toString());
        properties.getSentinel().setConfigFile(root.resolve("sentinel.conf").toString());
        service = new RestoreConfigServiceImpl(properties);
    }

    /** 正常目标仍能覆盖旧文件并创建尚不存在的配置文件。 */
    @Test
    void shouldRestoreRegularConfigurationTargets() throws Exception {
        Files.write(root.resolve("redis.conf"), "old".getBytes(StandardCharsets.UTF_8));
        assertTrue(service.restoreConfig().isSuccess());
        assertEquals("redis-backup", new String(
                Files.readAllBytes(root.resolve("redis.conf")), StandardCharsets.UTF_8));
        assertEquals("sentinel-backup", new String(
                Files.readAllBytes(root.resolve("sentinel.conf")), StandardCharsets.UTF_8));
    }

    /** 目标为目录时沿用配置错误码，且不开始覆盖另一份配置。 */
    @Test
    void shouldRejectDirectoryWithoutRestoringEitherFile() throws Exception {
        Path redisConfig = Files.write(root.resolve("redis.conf"),
                "keep".getBytes(StandardCharsets.UTF_8));
        Files.createDirectory(root.resolve("sentinel.conf"));
        OperationResultDTO result = service.restoreConfig();
        assertEquals(OperationErrorCode.CONFIG_INVALID, result.getErrorCode());
        assertEquals("keep", new String(Files.readAllBytes(redisConfig), StandardCharsets.UTF_8));
        assertTrue(Files.isDirectory(root.resolve("sentinel.conf")));
    }
}
