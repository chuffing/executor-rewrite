package com.icbc.nbfh.util;

import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件路径校验工具。
 */
public final class FileValidationUtil {

    /**
     * 判断路径是否为可读的普通文件，软链接按其指向的文件检查。
     */
    public static boolean isReadableFile(Path file) {
        return file != null
                && Files.isRegularFile(file)
                && Files.isReadable(file);
    }

    /**
     * 检查目标文件的写入条件，不接受目录和特殊文件；不保证后续写入成功。
     */
    public static boolean isWritableTarget(Path file) {

        if (file == null) {
            return false;
        }

        Path absolutePath = file.toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();

        if (parent == null
                || !Files.isDirectory(parent)
                || !Files.isWritable(parent)
                || !Files.isExecutable(parent)) {
            return false;
        }

        // 目标尚不存在时允许创建；无法确定是否存在时不直接放行。
        if (Files.notExists(absolutePath)) {
            return true;
        }

        // 已有目标必须是可写的普通文件。
        return Files.isRegularFile(absolutePath)
                && Files.isWritable(absolutePath);
    }

    /**
     * 读取目录所在文件系统的可用空间，只读文件系统不能用于部署。
     */
    public static long getUsableSpace(Path directory) throws IOException {

        FileStore store = Files.getFileStore(directory);
        if (store.isReadOnly()) {
            throw new IOException("目录所在文件系统为只读：" + directory);
        }
        return store.getUsableSpace();
    }

    /**
     * 检查已有目录的读取、访问及所需写入权限。
     */
    public static Path checkDirectory(String value, String name, boolean writable) throws IOException {

        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + "未配置");
        }
        Path path = Paths.get(value);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(name + "必须使用绝对路径");
        }
        path = path.normalize();
        if (!Files.isDirectory(path) || !Files.isReadable(path) || !Files.isExecutable(path)) {
            throw new IllegalArgumentException(name + "不存在或缺少读取、访问权限");
        }
        if (writable && !Files.isWritable(path)) {
            throw new IllegalArgumentException(name + "不可写");
        }
        return path.toRealPath();
    }

    /**
     * 工具类不允许实例化。
     */
    private FileValidationUtil() {
    }
}
