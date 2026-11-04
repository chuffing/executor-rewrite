package com.icbc.nbfh.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 根据唯一的运维根目录生成所有固定运维路径。
 */
@Component
@RequiredArgsConstructor
public class OperationPathResolver {

    private final AgentProperties agentProperties;

    public Path getBaseDirectory() {
        return Paths.get(agentProperties.getOperation().getBaseDir());
    }

    public Path getScriptDirectory() {
        return getBaseDirectory().resolve("scripts");
    }

    public Path getPackageDirectory() {
        return getBaseDirectory().resolve("packages");
    }

    public Path getConfigFileDirectory() {
        return getBaseDirectory().resolve("configfiles");
    }

    public Path getInstallDirectory() {
        return getBaseDirectory().resolve("versions");
    }

    public Path getCurrentDirectory() {
        return getBaseDirectory().resolve("current");
    }

    public Path getRedisConfigFile() {
        return getBaseDirectory().resolve("conf").resolve("redis.conf");
    }

    public Path getRedisBackupFile() {
        return getBaseDirectory().resolve("backup").resolve("redis.conf");
    }

    public Path getSentinelConfigFile() {
        return getBaseDirectory().resolve("conf").resolve("sentinel.conf");
    }

    public Path getSentinelBackupFile() {
        return getBaseDirectory().resolve("backup").resolve("sentinel.conf");
    }

    public Path getRedisCli() {
        return getCurrentDirectory().resolve("bin").resolve("redis-cli");
    }
}
