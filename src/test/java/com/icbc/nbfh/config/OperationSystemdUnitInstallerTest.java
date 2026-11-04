package com.icbc.nbfh.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationSystemdUnitInstallerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldInstallUnitsAndReloadOnlyWhenContentChanges()
            throws IOException {
        Path unitDirectory = temporaryDirectory.resolve("systemd");
        Files.createDirectory(unitDirectory);
        AgentProperties properties = new AgentProperties();
        properties.getOperation().setBaseDir(
                temporaryDirectory.resolve("redis-root").toString()
        );
        OperationPathResolver operationPaths =
                new OperationPathResolver(properties);
        AtomicInteger reloadCount = new AtomicInteger();
        OperationSystemdUnitInstaller installer =
                new OperationSystemdUnitInstaller(
                        unitDirectory,
                        true,
                        false,
                        reloadCount::incrementAndGet
                );

        installer.install(operationPaths);

        Path redisUnit = unitDirectory.resolve("redis-managed.service");
        Path sentinelUnit =
                unitDirectory.resolve("sentinel-managed.service");
        assertRenderedUnits(redisUnit, sentinelUnit, operationPaths);
        assertEquals(1, reloadCount.get());
        assertUnitPermissions(redisUnit);
        assertUnitPermissions(sentinelUnit);

        installer.install(operationPaths);
        assertEquals(1, reloadCount.get());

        properties.getOperation().setBaseDir(
                temporaryDirectory.resolve("redis-root-2").toString()
        );
        installer.install(operationPaths);

        assertRenderedUnits(redisUnit, sentinelUnit, operationPaths);
        assertEquals(2, reloadCount.get());
    }

    @Test
    void shouldRejectNonRegularUnitTarget() throws IOException {
        Path unitDirectory = temporaryDirectory.resolve("systemd");
        Files.createDirectory(unitDirectory);
        Files.createDirectory(
                unitDirectory.resolve("redis-managed.service")
        );
        OperationSystemdUnitInstaller installer =
                new OperationSystemdUnitInstaller(
                        unitDirectory,
                        true,
                        false,
                        () -> {
                        }
                );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> installer.install(createOperationPaths())
        );
        assertTrue(exception.getMessage().contains("不是普通文件"));
    }

    @Test
    void shouldFailWhenSystemdReloadFails() throws IOException {
        Path unitDirectory = temporaryDirectory.resolve("systemd");
        Files.createDirectory(unitDirectory);
        OperationSystemdUnitInstaller installer =
                new OperationSystemdUnitInstaller(
                        unitDirectory,
                        true,
                        false,
                        () -> {
                            throw new IOException("reload failed");
                        }
                );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> installer.install(createOperationPaths())
        );
        assertTrue(exception.getMessage().contains("daemon-reload"));
        assertTrue(Files.notExists(
                unitDirectory.resolve("redis-managed.service")
        ));
        assertTrue(Files.notExists(
                unitDirectory.resolve("sentinel-managed.service")
        ));
    }

    @Test
    void shouldRestorePreviousUnitsWhenSystemdReloadFails()
            throws IOException {
        Path unitDirectory = temporaryDirectory.resolve("systemd");
        Files.createDirectory(unitDirectory);
        Path redisUnit = unitDirectory.resolve("redis-managed.service");
        Path sentinelUnit =
                unitDirectory.resolve("sentinel-managed.service");
        byte[] previousRedis =
                "previous redis".getBytes(StandardCharsets.UTF_8);
        byte[] previousSentinel =
                "previous sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(redisUnit, previousRedis);
        Files.write(sentinelUnit, previousSentinel);

        OperationSystemdUnitInstaller installer =
                new OperationSystemdUnitInstaller(
                        unitDirectory,
                        true,
                        false,
                        () -> {
                            throw new IOException("reload failed");
                        }
                );

        assertThrows(
                IllegalStateException.class,
                () -> installer.install(createOperationPaths())
        );
        assertArrayEquals(previousRedis, Files.readAllBytes(redisUnit));
        assertArrayEquals(
                previousSentinel, Files.readAllBytes(sentinelUnit)
        );
    }

    @Test
    void shouldSkipInstallationWhenDisabled() {
        Path unitDirectory = temporaryDirectory.resolve("systemd");
        OperationSystemdUnitInstaller installer =
                new OperationSystemdUnitInstaller(
                        unitDirectory,
                        false,
                        false,
                        () -> {
                            throw new IOException("不应执行reload");
                        }
                );

        installer.install(createOperationPaths());

        assertTrue(Files.notExists(unitDirectory));
    }

    private OperationPathResolver createOperationPaths() {
        AgentProperties properties = new AgentProperties();
        properties.getOperation().setBaseDir(
                temporaryDirectory.resolve("redis-root").toString()
        );
        return new OperationPathResolver(properties);
    }

    private void assertRenderedUnits(
            Path redisUnit,
            Path sentinelUnit,
            OperationPathResolver operationPaths) throws IOException {
        String redisContent = new String(
                Files.readAllBytes(redisUnit), StandardCharsets.UTF_8
        );
        String sentinelContent = new String(
                Files.readAllBytes(sentinelUnit), StandardCharsets.UTF_8
        );

        assertTrue(redisContent.contains(
                operationPaths.getCurrentDirectory()
                        + "/bin/redis-server"
        ));
        assertTrue(redisContent.contains(
                operationPaths.getRedisConfigFile().toString()
        ));
        assertTrue(sentinelContent.contains(
                operationPaths.getCurrentDirectory()
                        + "/bin/redis-sentinel"
        ));
        assertTrue(sentinelContent.contains(
                operationPaths.getSentinelConfigFile().toString()
        ));
        assertTrue(!redisContent.contains("__CURRENT_DIR__"));
        assertTrue(!sentinelContent.contains("__CURRENT_DIR__"));
    }

    private void assertUnitPermissions(Path unit) throws IOException {
        if (unit.getFileSystem()
                .supportedFileAttributeViews().contains("posix")) {
            assertEquals(
                    PosixFilePermissions.fromString("rw-r--r--"),
                    Files.getPosixFilePermissions(unit)
            );
        }
    }
}
