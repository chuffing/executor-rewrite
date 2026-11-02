package com.icbc.nbfh.config;

import com.icbc.nbfh.common.Enum.ScriptType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OperationEnvironmentStartupValidatorTest {

    @TempDir
    Path temporaryDirectory;

    private AgentProperties properties;
    private Path scriptDirectory;
    private Path packageDirectory;
    private Path configFileDirectory;
    private Path installDirectory;
    private Path currentPath;
    private Path redisConfig;
    private Path redisBackup;
    private Path sentinelConfig;
    private Path sentinelBackup;
    private Path redisCli;
    private List<ConfiguredPath> configuredPaths;
    private OperationEnvironmentStartupValidator validator;

    @BeforeEach
    void setUp() throws IOException {
        properties = new AgentProperties();
        AgentProperties.OperationConfig operation = properties.getOperation();

        scriptDirectory = Files.createDirectory(
                temporaryDirectory.resolve("scripts"));
        packageDirectory = Files.createDirectory(
                temporaryDirectory.resolve("packages"));
        configFileDirectory = Files.createDirectory(
                temporaryDirectory.resolve("config-files"));
        installDirectory = Files.createDirectory(
                temporaryDirectory.resolve("versions"));
        Path redisConfigDirectory = Files.createDirectory(
                temporaryDirectory.resolve("redis-config"));
        Path redisBackupDirectory = Files.createDirectory(
                temporaryDirectory.resolve("redis-backup"));
        Path sentinelConfigDirectory = Files.createDirectory(
                temporaryDirectory.resolve("sentinel-config"));
        Path sentinelBackupDirectory = Files.createDirectory(
                temporaryDirectory.resolve("sentinel-backup"));

        currentPath = temporaryDirectory.resolve("current");
        redisConfig = redisConfigDirectory.resolve("redis.conf");
        redisBackup = redisBackupDirectory.resolve("redis.conf");
        sentinelConfig = sentinelConfigDirectory.resolve("sentinel.conf");
        sentinelBackup = sentinelBackupDirectory.resolve("sentinel.conf");
        redisCli = currentPath.resolve("bin").resolve("redis-cli");

        operation.setScriptDir(scriptDirectory.toString());
        operation.setPackageDir(packageDirectory.toString());
        operation.setConfigFileDir(configFileDirectory.toString());
        operation.setInstallDir(installDirectory.toString());
        operation.setCurrentDir(currentPath.toString());
        properties.getRedis().setConfigFile(redisConfig.toString());
        properties.getRedis().setBackupFile(redisBackup.toString());
        properties.getRedis().setCliBin(redisCli.toString());
        properties.getSentinel().setConfigFile(sentinelConfig.toString());
        properties.getSentinel().setBackupFile(sentinelBackup.toString());

        for (ScriptType scriptType : ScriptType.values()) {
            Files.createFile(scriptDirectory.resolve(scriptType.getFileName()));
        }

        configuredPaths = Arrays.asList(
                new ConfiguredPath(operation::setScriptDir,
                        scriptDirectory.toString()),
                new ConfiguredPath(operation::setPackageDir,
                        packageDirectory.toString()),
                new ConfiguredPath(operation::setConfigFileDir,
                        configFileDirectory.toString()),
                new ConfiguredPath(operation::setInstallDir,
                        installDirectory.toString()),
                new ConfiguredPath(operation::setCurrentDir,
                        currentPath.toString()),
                new ConfiguredPath(properties.getRedis()::setConfigFile,
                        redisConfig.toString()),
                new ConfiguredPath(properties.getRedis()::setBackupFile,
                        redisBackup.toString()),
                new ConfiguredPath(properties.getSentinel()::setConfigFile,
                        sentinelConfig.toString()),
                new ConfiguredPath(properties.getSentinel()::setBackupFile,
                        sentinelBackup.toString()),
                new ConfiguredPath(properties.getRedis()::setCliBin,
                        redisCli.toString())
        );

        validator = new OperationEnvironmentStartupValidator(properties);
    }

    @Test
    void shouldAcceptPreparedOperationEnvironment() {
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldRejectRelativePathForEveryStaticConfiguration() {
        for (ConfiguredPath configuredPath : configuredPaths) {
            assertRejected(configuredPath, "relative/path");
        }
    }

    @Test
    void shouldRejectRedisCliCommandName() {
        assertRejected(configuredPaths.get(9), "redis-cli");
    }

    @Test
    void shouldAcceptMissingRedisCliBeforeFirstDeployment() {
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldRejectLeadingOrTrailingWhitespaceForEveryConfiguration() {
        for (ConfiguredPath configuredPath : configuredPaths) {
            assertRejected(configuredPath, " " + configuredPath.validValue);
            assertRejected(configuredPath, configuredPath.validValue + " ");
        }
    }

    @Test
    void shouldRejectDotPathSegmentForEveryConfiguration() {
        for (ConfiguredPath configuredPath : configuredPaths) {
            Path validPath = Paths.get(configuredPath.validValue);
            String invalidPath = validPath.getParent()
                    .resolve(".")
                    .resolve(validPath.getFileName())
                    .toString();
            assertRejected(configuredPath, invalidPath);
        }
    }

    @Test
    void shouldRejectParentPathSegmentForEveryConfiguration() {
        for (ConfiguredPath configuredPath : configuredPaths) {
            String invalidPath = Paths.get(configuredPath.validValue)
                    .resolve("child")
                    .resolve("..")
                    .toString();
            assertRejected(configuredPath, invalidPath);
        }
    }

    @Test
    void shouldRejectEmptyStaticConfiguration() {
        for (ConfiguredPath configuredPath : configuredPaths) {
            assertRejected(configuredPath, "");
        }
    }

    @Test
    void shouldRejectMissingOperationRootDirectories() {
        AgentProperties.OperationConfig operation = properties.getOperation();

        assertRejected(
                new ConfiguredPath(operation::setScriptDir,
                        scriptDirectory.toString()),
                temporaryDirectory.resolve("missing-scripts").toString()
        );
        assertRejected(
                new ConfiguredPath(operation::setPackageDir,
                        packageDirectory.toString()),
                temporaryDirectory.resolve("missing-packages").toString()
        );
        assertRejected(
                new ConfiguredPath(operation::setConfigFileDir,
                        configFileDirectory.toString()),
                temporaryDirectory.resolve("missing-config-files").toString()
        );
        assertRejected(
                new ConfiguredPath(operation::setInstallDir,
                        installDirectory.toString()),
                temporaryDirectory.resolve("missing-versions").toString()
        );
    }

    @Test
    void shouldRejectFileSystemRootAsInstallDirectory() {
        properties.getOperation().setInstallDir(
                temporaryDirectory.getRoot().toString()
        );

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void shouldRejectMissingConfigFileParentDirectories() {
        assertMissingParent(
                properties.getRedis()::setConfigFile,
                redisConfig.toString(),
                "missing-redis-config"
        );
        assertMissingParent(
                properties.getRedis()::setBackupFile,
                redisBackup.toString(),
                "missing-redis-backup"
        );
        assertMissingParent(
                properties.getSentinel()::setConfigFile,
                sentinelConfig.toString(),
                "missing-sentinel-config"
        );
        assertMissingParent(
                properties.getSentinel()::setBackupFile,
                sentinelBackup.toString(),
                "missing-sentinel-backup"
        );
    }

    @Test
    void shouldRejectConfigAndBackupInSameDirectory() {
        properties.getRedis().setBackupFile(
                redisConfig.getParent().resolve("redis-backup.conf").toString());
        assertThrows(IllegalStateException.class, validator::validate);
        properties.getRedis().setBackupFile(redisBackup.toString());

        properties.getSentinel().setBackupFile(
                sentinelConfig.getParent()
                        .resolve("sentinel-backup.conf").toString());
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void shouldAcceptMissingCurrentLinkWhenParentIsPrepared() {
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldRejectMissingFixedScript() throws IOException {
        Files.delete(scriptDirectory.resolve(
                ScriptType.DEPLOY_VERSION.getFileName()));

        assertThrows(IllegalStateException.class, validator::validate);
    }

    private void assertMissingParent(
            Consumer<String> setter,
            String validValue,
            String missingDirectoryName) {
        String invalidValue = temporaryDirectory
                .resolve(missingDirectoryName)
                .resolve("config.conf")
                .toString();
        assertRejected(
                new ConfiguredPath(setter, validValue),
                invalidValue
        );
    }

    private void assertRejected(
            ConfiguredPath configuredPath,
            String invalidValue) {
        configuredPath.setter.accept(invalidValue);
        try {
            assertThrows(IllegalStateException.class, validator::validate);
        } finally {
            configuredPath.setter.accept(configuredPath.validValue);
        }
    }

    private static class ConfiguredPath {

        private final Consumer<String> setter;
        private final String validValue;

        private ConfiguredPath(
                Consumer<String> setter,
                String validValue) {
            this.setter = setter;
            this.validValue = validValue;
        }
    }
}
