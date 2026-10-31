package com.icbc.nbfh.command;

import com.icbc.nbfh.common.Enum.ScriptType;
import com.icbc.nbfh.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证脚本子进程只使用application.yml绑定后的最终配置。 */
class FixedScriptExecutorTest {

    private AgentProperties properties;
    private FixedScriptExecutor executor;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();

        AgentProperties.RedisConfig redis = properties.getRedis();
        redis.setHost("127.0.0.1");
        redis.setPort(6379);
        redis.setPassword("redis-secret");
        redis.setConfigFile("/opt/redis/conf/redis.conf");
        redis.setDataDir("/opt/redis/data");
        redis.setLogDir("/opt/redis/logs");
        redis.setCliBin("/opt/redis/current/bin/redis-cli");

        AgentProperties.SentinelConfig sentinel = properties.getSentinel();
        sentinel.setHost("127.0.0.2");
        sentinel.setPort(26379);
        sentinel.setPassword("sentinel-secret");
        sentinel.setConfigFile("/opt/redis/conf/sentinel.conf");
        sentinel.setDataDir("/opt/redis/data");
        sentinel.setLogDir("/opt/redis/logs");

        executor = new FixedScriptExecutor(properties);
    }

    @Test
    void shouldInjectRedisConfigurationIntoRedisScriptProcess() {
        ProcessBuilder processBuilder = new ProcessBuilder();

        executor.configureScriptEnvironment(
                processBuilder,
                ScriptType.START_REDIS
        );

        Map<String, String> environment = processBuilder.environment();
        assertEquals("127.0.0.1", environment.get("REDIS_HOST"));
        assertEquals("6379", environment.get("REDIS_PORT"));
        assertEquals("redis-secret", environment.get("REDIS_PASSWORD"));
        assertEquals("/opt/redis/conf/redis.conf",
                environment.get("REDIS_CONFIG_FILE"));
        assertEquals("/opt/redis/data",
                environment.get("REDIS_DATA_ROOT"));
        assertEquals("/opt/redis/logs",
                environment.get("REDIS_LOG_ROOT"));
        assertEquals("/opt/redis/current/bin/redis-cli",
                environment.get("REDIS_CLI_BIN"));
    }

    @Test
    void shouldInjectSentinelConfigurationIntoSentinelScriptProcess() {
        ProcessBuilder processBuilder = new ProcessBuilder();

        executor.configureScriptEnvironment(
                processBuilder,
                ScriptType.STOP_SENTINEL
        );

        Map<String, String> environment = processBuilder.environment();
        assertEquals("127.0.0.2", environment.get("SENTINEL_HOST"));
        assertEquals("26379", environment.get("SENTINEL_PORT"));
        assertEquals("sentinel-secret",
                environment.get("SENTINEL_PASSWORD"));
        assertEquals("/opt/redis/conf/sentinel.conf",
                environment.get("SENTINEL_CONFIG_FILE"));
        assertEquals("/opt/redis/data",
                environment.get("SENTINEL_DATA_ROOT"));
        assertEquals("/opt/redis/logs",
                environment.get("SENTINEL_LOG_ROOT"));
        assertEquals("/opt/redis/current/bin/redis-cli",
                environment.get("REDIS_CLI_BIN"));
    }
}
