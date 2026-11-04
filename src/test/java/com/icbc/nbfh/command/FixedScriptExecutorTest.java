package com.icbc.nbfh.command;

import com.icbc.nbfh.common.Enum.ScriptType;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.config.OperationPathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证脚本子进程只使用application.yml绑定后的最终配置。 */
class FixedScriptExecutorTest {

    @TempDir
    Path temporaryDirectory;

    private AgentProperties properties;
    private OperationPathResolver operationPaths;
    private FixedScriptExecutor executor;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getOperation().setBaseDir(
                temporaryDirectory.resolve("redis-root").toString()
        );
        operationPaths = new OperationPathResolver(properties);

        AgentProperties.RedisConfig redis = properties.getRedis();
        redis.setHost("127.0.0.1");
        redis.setPort(6379);
        redis.setPassword("redis-secret");
        redis.setDataDir("/opt/redis/data");
        redis.setLogDir("/opt/redis/logs");

        AgentProperties.SentinelConfig sentinel = properties.getSentinel();
        sentinel.setHost("127.0.0.2");
        sentinel.setPort(26379);
        sentinel.setPassword("sentinel-secret");
        sentinel.setDataDir("/opt/redis/data");
        sentinel.setLogDir("/opt/redis/logs");

        executor = new FixedScriptExecutor(properties, operationPaths);
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
        assertEquals(operationPaths.getRedisConfigFile().toString(),
                environment.get("REDIS_CONFIG_FILE"));
        assertEquals("/opt/redis/data",
                environment.get("REDIS_DATA_ROOT"));
        assertEquals("/opt/redis/logs",
                environment.get("REDIS_LOG_ROOT"));
        assertEquals(operationPaths.getRedisCli().toString(),
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
        assertEquals(operationPaths.getSentinelConfigFile().toString(),
                environment.get("SENTINEL_CONFIG_FILE"));
        assertEquals("/opt/redis/data",
                environment.get("SENTINEL_DATA_ROOT"));
        assertEquals("/opt/redis/logs",
                environment.get("SENTINEL_LOG_ROOT"));
        assertEquals(operationPaths.getRedisCli().toString(),
                environment.get("REDIS_CLI_BIN"));
    }

    @Test
    void shouldRejectMissingRedisCliWhenStopOperationActuallyRuns() {
        IOException exception = assertThrows(
                IOException.class,
                () -> executor.executeScript(
                        ScriptType.STOP_REDIS,
                        1
                )
        );

        assertTrue(exception.getMessage().contains(
                "Redis CLI不存在、不是普通文件或不可执行"
        ));
    }
}
