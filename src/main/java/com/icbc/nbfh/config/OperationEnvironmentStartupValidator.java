package com.icbc.nbfh.config;

import com.icbc.nbfh.common.Enum.ScriptType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 执行器启动时一次性准备并校验静态运维环境。
 */
@Component
@RequiredArgsConstructor
public class OperationEnvironmentStartupValidator {

    private static final String SCRIPT_RESOURCE_DIRECTORY = "scripts/";

    private final AgentProperties agentProperties;
    private final OperationPathResolver operationPaths;
    private final OperationSystemdUnitInstaller systemdUnitInstaller;

    @PostConstruct
    public void validate() {
        String baseDirectoryName =
                "agent.operation.base-dir（Redis运维根目录）";

        // 1. 先校验唯一根目录，错误配置不得产生任何派生目录。
        Path baseDirectory = parseAbsolutePath(
                agentProperties.getOperation().getBaseDir(),
                baseDirectoryName
        );
        if (baseDirectory.getParent() == null
                || baseDirectory.getFileName() == null) {
            throw new IllegalStateException(
                    baseDirectoryName + "不能使用文件系统根目录："
                            + baseDirectory
            );
        }

        Path scriptDirectory = operationPaths.getScriptDirectory();
        Path packageDirectory = operationPaths.getPackageDirectory();
        Path configFileDirectory =
                operationPaths.getConfigFileDirectory();
        Path installDirectory = operationPaths.getInstallDirectory();
        Path currentDirectory = operationPaths.getCurrentDirectory();
        Path redisConfig = operationPaths.getRedisConfigFile();
        Path redisBackup = operationPaths.getRedisBackupFile();
        Path sentinelConfig = operationPaths.getSentinelConfigFile();
        Path sentinelBackup = operationPaths.getSentinelBackupFile();

        // 2. 配置合法后，创建执行器负责管理的目录。
        prepareDirectory(baseDirectory, baseDirectoryName);
        prepareDirectory(scriptDirectory, "固定Shell脚本目录");
        prepareDirectory(packageDirectory, "Redis源码包目录");
        prepareDirectory(configFileDirectory, "配置文件暂存目录");
        prepareDirectory(installDirectory, "Redis版本安装目录");
        prepareDirectory(redisConfig.getParent(), "正式配置目录");
        prepareDirectory(redisBackup.getParent(), "备份配置目录");

        // current代表当前Redis版本软链接，只创建父目录，不创建假的current。
        installFixedScripts(scriptDirectory);

        // 3. 目录和脚本集中准备完成后，统一检查最终状态。
        requireDirectory(baseDirectory, baseDirectoryName, true);
        requireDirectory(scriptDirectory, "固定Shell脚本目录", true);
        requireDirectory(packageDirectory, "Redis源码包目录", true);
        requireDirectory(
                configFileDirectory, "配置文件暂存目录", true);
        requireDirectory(installDirectory, "Redis版本安装目录", true);
        requireDirectory(currentDirectory.getParent(),
                "Redis当前版本软链接父目录", true);
        requireConfigTarget(redisConfig, "Redis正式配置文件");
        requireConfigTarget(redisBackup, "Redis备份配置文件");
        requireConfigTarget(sentinelConfig, "Sentinel正式配置文件");
        requireConfigTarget(sentinelBackup, "Sentinel备份配置文件");

        for (ScriptType scriptType : ScriptType.values()) {
            Path script = scriptDirectory.resolve(scriptType.getFileName());
            if (!Files.isRegularFile(script) || !Files.isReadable(script)) {
                throw new IllegalStateException(
                        "固定脚本不存在或不可读：" + script
                );
            }
        }

        // 4. 运维目录和脚本就绪后，安装独立的Redis/Sentinel服务单元。
        // 这里只准备systemd定义，不在执行器启动阶段启停生产实例。
        systemdUnitInstaller.install(operationPaths);
    }

    private void prepareDirectory(Path path, String name) {
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "无法创建" + name + "：" + path, exception);
        }
    }

    private void installFixedScripts(Path scriptDirectory) {
        for (ScriptType scriptType : ScriptType.values()) {
            String fileName = scriptType.getFileName();
            ClassPathResource resource = new ClassPathResource(
                    SCRIPT_RESOURCE_DIRECTORY + fileName);
            Path target = scriptDirectory.resolve(fileName);
            Path temporaryFile = null;

            try {
                if (!resource.exists()) {
                    throw new IllegalStateException(
                            "JAR内缺少固定脚本资源："
                                    + SCRIPT_RESOURCE_DIRECTORY + fileName);
                }

                temporaryFile = Files.createTempFile(
                        scriptDirectory, "." + fileName + ".", ".tmp");
                try (InputStream inputStream = resource.getInputStream()) {
                    Files.copy(
                            inputStream,
                            temporaryFile,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                }
                setScriptPermissions(temporaryFile);
                replaceScript(temporaryFile, target);
                temporaryFile = null;
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "无法释放固定脚本到" + target, exception);
            } finally {
                if (temporaryFile != null) {
                    try {
                        Files.deleteIfExists(temporaryFile);
                    } catch (IOException ignored) {
                        // 启动失败时尽力清理临时文件，不覆盖原始异常。
                    }
                }
            }
        }
    }

    private void setScriptPermissions(Path script) throws IOException {
        if (script.getFileSystem()
                .supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(
                    script, PosixFilePermissions.fromString("rwxr-x---"));
        }
    }

    private void replaceScript(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private void requireConfigTarget(Path path, String name) {
        requireDirectory(path.getParent(), name + "父目录", true);

        if (Files.exists(path)
                && (!Files.isRegularFile(path)
                || !Files.isReadable(path)
                || !Files.isWritable(path))) {
            throw new IllegalStateException(
                    name + "不是可读写的普通文件：" + path);
        }
    }

    private Path parseAbsolutePath(String configuredPath, String name) {
        if (configuredPath == null || configuredPath.isEmpty()) {
            throw new IllegalStateException(name + "不能为空");
        }
        if (!configuredPath.equals(configuredPath.trim())) {
            throw new IllegalStateException(name + "不能包含首尾空格");
        }
        if (configuredPath.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException(name + "不能包含空白字符");
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
            if (!configuredPath.equals(path.toString())) {
                throw new IllegalStateException(
                        name + "必须使用无冗余分隔符的干净路径："
                                + configuredPath
                );
            }
            return path;
        } catch (InvalidPathException exception) {
            throw new IllegalStateException(name + "不是合法路径", exception);
        }
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
