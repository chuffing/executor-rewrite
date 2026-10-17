package com.icbc.nbfh.util;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件路径校验工具。
 */
public final class FileValidationUtil {

    /**
     * 判断路径是否为可读的普通文件。
     */
    public static boolean isReadableFile(Path file) {
        return file != null
                && Files.isRegularFile(file)
                && Files.isReadable(file);
    }

    /**
     * 判断目标文件及其所在目录是否可写。
     */
    public static boolean isWritableTarget(Path file) {

        if (file == null) {
            return false;
        }

        Path absolutePath = file.toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();

        if (parent == null
                || !Files.isDirectory(parent)
                || !Files.isWritable(parent)) {
            return false;
        }

        return !Files.exists(absolutePath)
                || Files.isWritable(absolutePath);
    }

    /**
     * 工具类不允许实例化。
     */
    private FileValidationUtil() {
    }
}
