package com.icbc.nbfh.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 只使用测试临时目录验证文件检查边界。 */
class FileValidationUtilTest {

    @TempDir
    Path root;

    /** 普通文件和待创建文件仍可作为写入目标。 */
    @Test
    void shouldAllowRegularAndMissingFileTargets() throws Exception {
        Path existing = Files.write(root.resolve("redis.conf"),
                "keep".getBytes(StandardCharsets.UTF_8));
        assertTrue(FileValidationUtil.isWritableTarget(existing));
        assertTrue(FileValidationUtil.isWritableTarget(root.resolve("sentinel.conf")));
        assertEquals("keep", new String(Files.readAllBytes(existing), StandardCharsets.UTF_8));
        assertFalse(Files.exists(root.resolve("sentinel.conf")));
    }

    /** 目录、空路径和缺失父目录不能作为普通文件写入目标。 */
    @Test
    void shouldRejectInvalidFileTargets() {
        assertFalse(FileValidationUtil.isWritableTarget(null));
        assertFalse(FileValidationUtil.isWritableTarget(root));
        assertFalse(FileValidationUtil.isWritableTarget(root.resolve("missing/redis.conf")));
    }

    /** 目录检查拒绝空值和相对路径，且不会创建缺失目录。 */
    @Test
    void shouldCheckConfiguredDirectoryWithoutCreatingIt() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> FileValidationUtil.checkDirectory(" ", "目录", true));
        assertThrows(IllegalArgumentException.class,
                () -> FileValidationUtil.checkDirectory("relative", "目录", true));
        assertThrows(IllegalArgumentException.class,
                () -> FileValidationUtil.checkDirectory(root.resolve("absent").toString(), "目录", true));
        assertFalse(Files.exists(root.resolve("absent")));
        assertEquals(root.toRealPath(),
                FileValidationUtil.checkDirectory(root.toString(), "目录", false));
        assertEquals(root.toRealPath(),
                FileValidationUtil.checkDirectory(root.toString(), "目录", true));
    }
}
