package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证redisReady外置配置更新逻辑。
 */
class RedisReadyConfigUpdaterTest {

    /** 拓扑验收成功后将redisReady持久化为true。 */
    @Test
    void shouldMarkRedisReady() throws Exception {
        Path temporaryDirectory = Files.createTempDirectory(
                Paths.get("target"),
                "redis-ready-"
        );
        Path configFile = temporaryDirectory.resolve("application.yml");
        Files.write(
                configFile,
                ("agent:\n"
                        + "  redisReady: false\n"
                        + "  agent-name: target-agent\n")
                        .getBytes(StandardCharsets.UTF_8)
        );

        AgentProperties properties = new AgentProperties();
        properties.setConfigFile(configFile.toString());
        properties.setRedisReady(false);

        RedisReadyConfigUpdater updater =
                new RedisReadyConfigUpdater(properties);
        updater.markReadyIfNecessary();

        assertTrue(properties.isRedisReady());
        String updatedConfig = new String(
                Files.readAllBytes(configFile),
                StandardCharsets.UTF_8
        );
        assertTrue(updatedConfig.contains("redisReady: true"));
    }
}
