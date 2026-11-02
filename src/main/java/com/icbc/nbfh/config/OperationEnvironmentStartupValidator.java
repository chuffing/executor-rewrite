package com.icbc.nbfh.config;

import com.icbc.nbfh.common.Enum.ScriptType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 执行器启动时一次性校验固定脚本和人工准备的运维根目录。
 */
@Component
@RequiredArgsConstructor
public class OperationEnvironmentStartupValidator {

    private final AgentProperties agentProperties;

    @PostConstruct
    public void validate() {
        AgentProperties.OperationConfig operation =
                agentProperties.getOperation();

        Path scriptDirectory = requireDirectory(
                operation.getScriptDir(),
                "agent.operation.script-dir（Shell脚本目录）",
                false
        );
        requireDirectory(
                operation.getPackageDir(),
                "agent.operation.package-dir（Redis源码包目录）",
                true
        );
        requireDirectory(
                operation.getConfigFileDir(),
                "agent.operation.config-file-dir（配置文件暂存目录）",
                true
        );
        String installDirectoryName =
                "agent.operation.install-dir（Redis版本安装目录）";
        Path installDirectory = parseAbsolutePath(
                operation.getInstallDir(),
                installDirectoryName
        );
        if (installDirectory.getParent() == null) {
            throw new IllegalStateException(
                    installDirectoryName + "不能使用文件系统根目录："
                            + installDirectory
            );
        }
        requireDirectory(installDirectory, installDirectoryName, true);
        requireAbsoluteTargetPath(
                operation.getCurrentDir(),
                "agent.operation.current-dir（Redis当前版本软链接路径）"
        );

        Path redisConfig = requireConfigTarget(
                agentProperties.getRedis().getConfigFile(),
                "agent.redis.config-file（Redis正式配置文件）"
        );
        Path redisBackup = requireConfigTarget(
                agentProperties.getRedis().getBackupFile(),
                "agent.redis.backup-file（Redis备份配置文件）"
        );
        Path sentinelConfig = requireConfigTarget(
                agentProperties.getSentinel().getConfigFile(),
                "agent.sentinel.config-file（Sentinel正式配置文件）"
        );
        Path sentinelBackup = requireConfigTarget(
                agentProperties.getSentinel().getBackupFile(),
                "agent.sentinel.backup-file（Sentinel备份配置文件）"
        );

        // redis-cli在首次部署前可以不存在，这里只校验固定配置路径本身。
        requireAbsoluteTargetPathSyntax(
                agentProperties.getRedis().getCliBin(),
                "agent.redis.cli-bin（Redis CLI路径）"
        );

        // 检查正式配置文件跟备份配置文件是否位于同一目录。
        requireSeparateDirectories(
                redisConfig, redisBackup, "Redis");
        requireSeparateDirectories(
                sentinelConfig, sentinelBackup, "Sentinel");

        // 检查六个实际执行运维动作的脚本文件是否都存在。
        for (ScriptType scriptType : ScriptType.values()) {
            Path script = scriptDirectory.resolve(scriptType.getFileName());
            if (!Files.isRegularFile(script) || !Files.isReadable(script)) {
                throw new IllegalStateException(
                        "固定脚本不存在或不可读：" + script
                );
            }
        }
    }

    private Path requireDirectory(
            String configuredPath,
            String name,
            boolean writable) {
        Path path = parseAbsolutePath(configuredPath, name);

        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(name + "不存在或不是目录：" + path);
        }
        if (!Files.isReadable(path) || !Files.isExecutable(path)) {
            throw new IllegalStateException(name + "不可访问：" + path);
        }
        if (writable && !Files.isWritable(path)) {
            throw new IllegalStateException(name + "不可写：" + path);
        }
        return path;
    }

    private void requireAbsoluteTargetPath(
            String configuredPath,
            String name) {
        Path path = requireAbsoluteTargetPathSyntax(configuredPath, name);
        requireDirectory(path.getParent(), name + "父目录", true);
    }

    private Path requireConfigTarget(String configuredPath, String name) {
        Path path = requireAbsoluteTargetPathSyntax(configuredPath, name);
        requireDirectory(path.getParent(), name + "父目录", true);

        if (Files.exists(path)
                && (!Files.isRegularFile(path)
                || !Files.isReadable(path)
                || !Files.isWritable(path))) {
            throw new IllegalStateException(
                    name + "不是可读写的普通文件：" + path);
        }
        return path;
    }

    private void requireSeparateDirectories(
            Path configFile,
            Path backupFile,
            String name) {
        if (configFile.getParent().equals(backupFile.getParent())) {
            throw new IllegalStateException(
                    name + "正式配置与备份配置不能位于同一目录");
        }
    }

    private Path parseAbsolutePath(String configuredPath, String name) {
        if (configuredPath == null || configuredPath.isEmpty()) {
            throw new IllegalStateException(name + "不能为空");
        }
        if (!configuredPath.equals(configuredPath.trim())) {
            throw new IllegalStateException(name + "不能包含首尾空格");
        }

        try {
            Path path = Paths.get(configuredPath);
            if (!path.isAbsolute()) {
                throw new IllegalStateException(name + "必须使用绝对路径");
            }
            if (!path.equals(path.normalize())) {
                throw new IllegalStateException(
                        name + "不能包含.或..路径段：" + configuredPath
                );
            }
            return path;
        } catch (InvalidPathException exception) {
            throw new IllegalStateException(name + "不是合法路径", exception);
        }
    }

    private Path requireAbsoluteTargetPathSyntax(
            String configuredPath,
            String name) {
        Path path = parseAbsolutePath(configuredPath, name);
        if (path.getParent() == null || path.getFileName() == null) {
            throw new IllegalStateException(name + "不是合法的目标路径：" + path);
        }
        return path;
    }

    private void requireDirectory(Path path, String name, boolean writable) {
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(name + "不存在或不是目录：" + path);
        }
        if (!Files.isReadable(path) || !Files.isExecutable(path)) {
            throw new IllegalStateException(name + "不可读取或访问：" + path);
        }
        if (writable && !Files.isWritable(path)) {
            throw new IllegalStateException(name + "不可写：" + path);
        }
    }
}
