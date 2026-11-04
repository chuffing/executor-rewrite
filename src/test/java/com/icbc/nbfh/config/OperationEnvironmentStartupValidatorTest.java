package com.icbc.nbfh.config;

import com.icbc.nbfh.common.Enum.ScriptType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationEnvironmentStartupValidatorTest {

    @TempDir
    Path temporaryDirectory;

    private AgentProperties properties;
    private OperationPathResolver operationPaths;
    private OperationEnvironmentStartupValidator validator;
    private Path validBaseDirectory;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        validBaseDirectory = temporaryDirectory.resolve("redis-root");
        properties.getOperation().setBaseDir(
                validBaseDirectory.toString()
        );
        operationPaths = new OperationPathResolver(properties);

        OperationSystemdUnitInstaller systemdUnitInstaller =
                new OperationSystemdUnitInstaller(
                        temporaryDirectory.resolve("systemd"),
                        false,
                        false,
                        () -> {
                        }
                );
        validator = new OperationEnvironmentStartupValidator(
                properties,
                operationPaths,
                systemdUnitInstaller
        );
    }

    @Test
    void shouldCreateDerivedEnvironmentAndInstallFixedScripts() {
        assertDoesNotThrow(validator::validate);

        assertTrue(Files.isDirectory(validBaseDirectory));
        assertTrue(Files.isDirectory(operationPaths.getScriptDirectory()));
        assertTrue(Files.isDirectory(operationPaths.getPackageDirectory()));
        assertTrue(Files.isDirectory(
                operationPaths.getConfigFileDirectory()
        ));
        assertTrue(Files.isDirectory(operationPaths.getInstallDirectory()));
        assertTrue(Files.isDirectory(
                operationPaths.getRedisConfigFile().getParent()
        ));
        assertTrue(Files.isDirectory(
                operationPaths.getRedisBackupFile().getParent()
        ));

        for (ScriptType scriptType : ScriptType.values()) {
            Path script = operationPaths.getScriptDirectory().resolve(
                    scriptType.getFileName()
            );
            assertTrue(Files.isRegularFile(script));
            assertTrue(assertDoesNotThrow(() -> Files.size(script)) > 0);
        }
    }

    @Test
    void shouldDeriveEveryFixedOperationPathFromBaseDirectory() {
        assertEquals(validBaseDirectory.resolve("scripts"),
                operationPaths.getScriptDirectory());
        assertEquals(validBaseDirectory.resolve("packages"),
                operationPaths.getPackageDirectory());
        assertEquals(validBaseDirectory.resolve("configfiles"),
                operationPaths.getConfigFileDirectory());
        assertEquals(validBaseDirectory.resolve("versions"),
                operationPaths.getInstallDirectory());
        assertEquals(validBaseDirectory.resolve("current"),
                operationPaths.getCurrentDirectory());
        assertEquals(validBaseDirectory.resolve("conf/redis.conf"),
                operationPaths.getRedisConfigFile());
        assertEquals(validBaseDirectory.resolve("backup/redis.conf"),
                operationPaths.getRedisBackupFile());
        assertEquals(validBaseDirectory.resolve("conf/sentinel.conf"),
                operationPaths.getSentinelConfigFile());
        assertEquals(validBaseDirectory.resolve("backup/sentinel.conf"),
                operationPaths.getSentinelBackupFile());
        assertEquals(validBaseDirectory.resolve("current/bin/redis-cli"),
                operationPaths.getRedisCli());
    }

    @Test
    void shouldRejectRelativeBaseDirectoryWithoutCreatingDirectories() {
        properties.getOperation().setBaseDir("relative/redis");

        assertThrows(IllegalStateException.class, validator::validate);
        assertFalse(Files.exists(validBaseDirectory));
    }

    @Test
    void shouldRejectEmptyBaseDirectory() {
        assertRejected("");
    }

    @Test
    void shouldRejectLeadingOrTrailingWhitespace() {
        assertRejected(" " + validBaseDirectory);
        assertRejected(validBaseDirectory + " ");
    }

    @Test
    void shouldRejectWhitespaceInsideBaseDirectory() {
        assertRejected(
                temporaryDirectory.resolve("redis root").toString()
        );
    }

    @Test
    void shouldRejectDotPathSegment() {
        String invalidPath = validBaseDirectory.getParent()
                .resolve(".")
                .resolve(validBaseDirectory.getFileName())
                .toString();
        assertRejected(invalidPath);
    }

    @Test
    void shouldRejectParentPathSegment() {
        String invalidPath = validBaseDirectory
                .resolve("child")
                .resolve("..")
                .toString();
        assertRejected(invalidPath);
    }

    @Test
    void shouldRejectRedundantTrailingSeparator() {
        assertRejected(validBaseDirectory + File.separator);
    }

    @Test
    void shouldRejectFileSystemRoot() {
        assertRejected(temporaryDirectory.getRoot().toString());
    }

    @Test
    void shouldAcceptMissingCurrentAndRedisCliBeforeFirstDeployment() {
        assertDoesNotThrow(validator::validate);
        assertFalse(Files.exists(operationPaths.getCurrentDirectory()));
        assertFalse(Files.exists(operationPaths.getRedisCli()));
    }

    @Test
    void shouldNotCreateEmptyRedisOrSentinelConfigFiles() {
        assertDoesNotThrow(validator::validate);

        assertFalse(Files.exists(operationPaths.getRedisConfigFile()));
        assertFalse(Files.exists(operationPaths.getRedisBackupFile()));
        assertFalse(Files.exists(operationPaths.getSentinelConfigFile()));
        assertFalse(Files.exists(operationPaths.getSentinelBackupFile()));
    }

    @Test
    void shouldRestoreMissingFixedScriptFromJar() throws IOException {
        validator.validate();
        Path deployScript = operationPaths.getScriptDirectory().resolve(
                ScriptType.DEPLOY_VERSION.getFileName()
        );
        Files.delete(deployScript);

        assertDoesNotThrow(validator::validate);
        assertTrue(Files.isRegularFile(deployScript));
        assertTrue(Files.size(deployScript) > 0);
    }

    @Test
    void shouldReplaceModifiedFixedScriptWithJarVersion()
            throws IOException {
        validator.validate();
        Path deployScript = operationPaths.getScriptDirectory().resolve(
                ScriptType.DEPLOY_VERSION.getFileName()
        );
        byte[] expectedContent = Files.readAllBytes(deployScript);
        Files.write(
                deployScript,
                "modified".getBytes(StandardCharsets.UTF_8)
        );

        validator.validate();

        assertArrayEquals(expectedContent, Files.readAllBytes(deployScript));
    }

    private void assertRejected(String invalidValue) {
        String validValue = properties.getOperation().getBaseDir();
        properties.getOperation().setBaseDir(invalidValue);
        try {
            assertThrows(IllegalStateException.class, validator::validate);
        } finally {
            properties.getOperation().setBaseDir(validValue);
        }
    }
}
