package com.icbc.nbfh.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationServiceResourceTest {

    @Test
    void shouldPackageIndependentRedisAndSentinelSystemdUnits()
            throws IOException {
        String redisUnit = readResource(
                "systemd/redis-managed.service"
        );
        String sentinelUnit = readResource(
                "systemd/sentinel-managed.service"
        );

        assertTrue(redisUnit.contains("Type=simple"));
        assertTrue(redisUnit.contains(
                "__CURRENT_DIR__/bin/redis-server"
        ));
        assertTrue(redisUnit.contains("__REDIS_CONFIG_FILE__"));
        assertTrue(redisUnit.contains("--daemonize no"));

        assertTrue(sentinelUnit.contains("Type=simple"));
        assertTrue(sentinelUnit.contains(
                "__CURRENT_DIR__/bin/redis-sentinel"
        ));
        assertTrue(sentinelUnit.contains("__SENTINEL_CONFIG_FILE__"));
        assertTrue(sentinelUnit.contains("--daemonize no"));

        assertFalse(redisUnit.contains("KillMode=process"));
        assertFalse(sentinelUnit.contains("KillMode=process"));
    }

    @Test
    void shouldStartAndStopInstancesThroughIndependentSystemdUnits()
            throws IOException {
        String startRedis = readResource("scripts/start-redis.sh");
        String stopRedis = readResource("scripts/stop-redis.sh");
        String startSentinel = readResource(
                "scripts/start-sentinel.sh"
        );
        String stopSentinel = readResource(
                "scripts/stop-sentinel.sh"
        );

        assertTrue(startRedis.contains(
                "systemctl start \"$redis_unit\""
        ));
        assertTrue(stopRedis.contains(
                "systemctl stop \"$redis_unit\""
        ));
        assertTrue(startSentinel.contains(
                "systemctl start \"$sentinel_unit\""
        ));
        assertTrue(stopSentinel.contains(
                "systemctl stop \"$sentinel_unit\""
        ));

        assertFalse(startRedis.contains("--daemonize yes"));
        assertFalse(startSentinel.contains("--daemonize yes"));
    }

    private String readResource(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        assertTrue(resource.exists(), "缺少类路径资源：" + path);
        return StreamUtils.copyToString(
                resource.getInputStream(),
                StandardCharsets.UTF_8
        );
    }
}
