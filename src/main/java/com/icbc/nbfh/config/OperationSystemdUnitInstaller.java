package com.icbc.nbfh.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 将执行器内置的 Redis/Sentinel systemd 单元安装到系统固定目录。
 */
@Slf4j
@Component
public class OperationSystemdUnitInstaller {

    private static final String RESOURCE_DIRECTORY = "systemd/";
    private static final String REDIS_UNIT = "redis-managed.service";
    private static final String SENTINEL_UNIT = "sentinel-managed.service";
    private static final Path SYSTEMD_UNIT_DIRECTORY =
            Paths.get("/etc/systemd/system");
    private static final long RELOAD_TIMEOUT_SECONDS = 15L;

    private final Path unitDirectory;
    private final boolean enabled;
    private final boolean manageRootOwnership;
    private final SystemdReloader systemdReloader;

    public OperationSystemdUnitInstaller() {
        this(
                SYSTEMD_UNIT_DIRECTORY,
                isLinux(),
                isLinux(),
                OperationSystemdUnitInstaller::reloadSystemd
        );
    }

    OperationSystemdUnitInstaller(
            Path unitDirectory,
            boolean enabled,
            boolean manageRootOwnership,
            SystemdReloader systemdReloader) {
        this.unitDirectory = unitDirectory;
        this.enabled = enabled;
        this.manageRootOwnership = manageRootOwnership;
        this.systemdReloader = systemdReloader;
    }

    /**
     * 幂等安装两个固定单元；只有文件内容变化时才重新加载systemd。
     */
    public void install(OperationPathResolver operationPaths) {
        if (!enabled) {
            log.info("当前不是Linux环境，跳过Redis/Sentinel systemd单元安装");
            return;
        }

        requireWritableUnitDirectory();
        UnitSnapshot redisSnapshot = snapshotUnit(REDIS_UNIT);
        UnitSnapshot sentinelSnapshot = snapshotUnit(SENTINEL_UNIT);
        try {
            boolean changed = installUnit(REDIS_UNIT, operationPaths);
            changed = installUnit(SENTINEL_UNIT, operationPaths) || changed;

            if (changed) {
                systemdReloader.reload();
                log.info("Redis/Sentinel systemd服务单元已安装并重新加载");
            } else {
                log.info("Redis/Sentinel systemd服务单元内容未变化");
            }
        } catch (IOException exception) {
            IllegalStateException failure = new IllegalStateException(
                    "Redis/Sentinel服务单元已写入，但systemctl daemon-reload执行失败",
                    exception
            );
            rollbackUnits(redisSnapshot, sentinelSnapshot, failure);
            throw failure;
        } catch (RuntimeException exception) {
            rollbackUnits(redisSnapshot, sentinelSnapshot, exception);
            throw exception;
        }
    }

    private void requireWritableUnitDirectory() {
        if (!Files.isDirectory(unitDirectory)
                || !Files.isWritable(unitDirectory)
                || !Files.isExecutable(unitDirectory)) {
            throw new IllegalStateException(
                    "systemd服务单元目录不存在或不可写，请确认执行器以root运行："
                            + unitDirectory
            );
        }
    }

    private boolean installUnit(
            String fileName,
            OperationPathResolver operationPaths) {
        ClassPathResource resource = new ClassPathResource(
                RESOURCE_DIRECTORY + fileName
        );
        Path target = unitDirectory.resolve(fileName);
        Path temporaryFile = null;

        try {
            if (!resource.exists()) {
                throw new IllegalStateException(
                        "JAR内缺少systemd服务单元："
                                + RESOURCE_DIRECTORY + fileName
                );
            }

            byte[] expectedContent;
            try (InputStream inputStream = resource.getInputStream()) {
                String template = new String(
                        readAllBytes(inputStream), StandardCharsets.UTF_8
                );
                expectedContent = renderUnit(
                        template, operationPaths
                ).getBytes(StandardCharsets.UTF_8);
            }

            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException(
                            "systemd服务单元目标不是普通文件：" + target
                    );
                }
                if (Arrays.equals(expectedContent, Files.readAllBytes(target))) {
                    setUnitMetadata(target);
                    return false;
                }
            }

            temporaryFile = Files.createTempFile(
                    unitDirectory, "." + fileName + ".", ".tmp"
            );
            Files.write(temporaryFile, expectedContent);
            setUnitMetadata(temporaryFile);
            replaceFile(temporaryFile, target);
            temporaryFile = null;
            return true;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "无法安装systemd服务单元到" + target, exception
            );
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // 安装失败时尽力清理临时文件，不覆盖原始异常。
                }
            }
        }
    }

    private String renderUnit(
            String template,
            OperationPathResolver operationPaths) {
        String rendered = template.replace(
                "__CURRENT_DIR__",
                operationPaths.getCurrentDirectory().toString()
        ).replace(
                "__REDIS_CONFIG_FILE__",
                operationPaths.getRedisConfigFile().toString()
        ).replace(
                "__SENTINEL_CONFIG_FILE__",
                operationPaths.getSentinelConfigFile().toString()
        );
        if (rendered.contains("__CURRENT_DIR__")
                || rendered.contains("__REDIS_CONFIG_FILE__")
                || rendered.contains("__SENTINEL_CONFIG_FILE__")) {
            throw new IllegalStateException(
                    "systemd服务单元模板包含未替换的运维路径占位符"
            );
        }
        return rendered;
    }

    private UnitSnapshot snapshotUnit(String fileName) {
        Path target = unitDirectory.resolve(fileName);
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return new UnitSnapshot(target, null);
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(
                        "systemd服务单元目标不是普通文件：" + target
                );
            }
            return new UnitSnapshot(target, Files.readAllBytes(target));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "无法读取现有systemd服务单元：" + target,
                    exception
            );
        }
    }

    private void rollbackUnits(
            UnitSnapshot redisSnapshot,
            UnitSnapshot sentinelSnapshot,
            RuntimeException originalException) {
        try {
            restoreUnit(redisSnapshot);
            restoreUnit(sentinelSnapshot);
        } catch (IOException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

    private void restoreUnit(UnitSnapshot snapshot) throws IOException {
        if (snapshot.content == null) {
            Files.deleteIfExists(snapshot.target);
            return;
        }

        Path temporaryFile = Files.createTempFile(
                unitDirectory,
                "." + snapshot.target.getFileName() + ".rollback.",
                ".tmp"
        );
        try {
            Files.write(temporaryFile, snapshot.content);
            setUnitMetadata(temporaryFile);
            replaceFile(temporaryFile, snapshot.target);
            temporaryFile = null;
        } finally {
            if (temporaryFile != null) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    private void setUnitMetadata(Path unit) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(
                unit, PosixFileAttributeView.class
        );
        if (view == null) {
            return;
        }

        view.setPermissions(PosixFilePermissions.fromString("rw-r--r--"));
        if (!manageRootOwnership) {
            return;
        }

        UserPrincipalLookupService lookupService =
                unit.getFileSystem().getUserPrincipalLookupService();
        UserPrincipal rootUser = lookupService.lookupPrincipalByName("root");
        GroupPrincipal rootGroup =
                lookupService.lookupPrincipalByGroupName("root");
        view.setOwner(rootUser);
        view.setGroup(rootGroup);
    }

    private void replaceFile(Path source, Path target) throws IOException {
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

    private static byte[] readAllBytes(InputStream inputStream)
            throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, length);
        }
        return outputStream.toByteArray();
    }

    private static void reloadSystemd() throws IOException {
        Process process = new ProcessBuilder(
                "systemctl", "daemon-reload"
        ).redirectErrorStream(true).start();

        boolean finished;
        try {
            finished = process.waitFor(
                    RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "等待systemctl daemon-reload时线程被中断", exception
            );
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException(
                    "systemctl daemon-reload执行超过"
                            + RELOAD_TIMEOUT_SECONDS + "秒"
            );
        }

        String output;
        try (InputStream inputStream = process.getInputStream()) {
            output = new String(readAllBytes(inputStream), "UTF-8").trim();
        }
        if (process.exitValue() != 0) {
            throw new IOException(
                    "systemctl daemon-reload退出码为"
                            + process.exitValue()
                            + (output.isEmpty() ? "" : "：" + output)
            );
        }
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ENGLISH)
                .contains("linux");
    }

    @FunctionalInterface
    interface SystemdReloader {

        void reload() throws IOException;
    }

    private static class UnitSnapshot {

        private final Path target;
        private final byte[] content;

        private UnitSnapshot(Path target, byte[] content) {
            this.target = target;
            this.content = content;
        }
    }
}
