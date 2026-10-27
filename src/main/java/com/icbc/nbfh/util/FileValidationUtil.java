package com.icbc.nbfh.util;

import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件路径校验工具
 */
public final class FileValidationUtil {

    /**
     * 判断路径是否为可读的普通文件，软链接按其指向的文件检查
     */
    public static boolean isReadableFile(Path file) {
        return file != null
                && Files.isRegularFile(file)
                && Files.isReadable(file);
    }

    /**
     * 检查目标文件的写入条件，不接受目录和特殊文件；不保证后续写入成功
     */
    public static boolean isWritableTarget(Path file) {

        if (file == null) {
            return false;
        }

        Path absolutePath = file.toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();

        if (parent == null
                || !Files.isDirectory(parent)
                || !isWritable(parent)
                || !isSearchable(parent)) {
            return false;
        }

        // 目标尚不存在时允许创建；无法确定是否存在时不直接放行
        if (Files.notExists(absolutePath)) {
            return true;
        }

        // 已有目标必须是可写的普通文件
        return Files.isRegularFile(absolutePath)
                && isWritable(absolutePath);
    }

    /**
     * 读取目录所在文件系统的可用空间，只读文件系统不能用于部署
     */
    public static long getUsableSpace(Path directory) throws IOException {

        FileStore store = Files.getFileStore(directory);

        if (store.isReadOnly()) {
            throw new IOException("目录所在文件系统为只读：" + directory);

        }
        return store.getUsableSpace();
    }

    /**
     * 检查已有目录的读取、访问及所需写入权限
     */
    public static Path checkDirectory(String value, String name, boolean writable) throws IOException {

        // 检查配置文件中是否配置了该目录
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + "未配置");
        }

        Path path = Paths.get(value);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(name + "必须使用绝对路径");
        }

        path = path.normalize();
        if (!Files.isDirectory(path) || !Files.isReadable(path) || !isSearchable(path)) {
            throw new IllegalArgumentException(name + "不存在或缺少读取、访问权限");
        }

        if (writable && !isWritable(path)) {
            throw new IllegalArgumentException(name + "不可写");
        }

        return path.toRealPath();
    }

    /**
     * 判断目录是否具有“进入/搜索”权限
     *
     * Linux 下由 isExecutable(Path)判断；Windows 的 ACL 模型
     * 对目录不提供同等 POSIX 执行位，普通可访问目录可能返回 false，
     * 因此 Windows 下退化为目录存在即可（生产目标为 Linux）
     */
    private static boolean isSearchable(Path path) {
        if (Files.isExecutable(path)) {
            return true;
        }
        return isWindows() && Files.isDirectory(path);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    /**
     * 判断路径是否可写
     *
     * Windows 下 isWritable(Path) 受 ACL 继承影响可能返回 false，
     * 因此退化为实际创建/打开探测；Linux 下仍使用 JDK 权限位判断
     */
    private static boolean isWritable(Path path) {
        if (!isWindows()) {
            return Files.isWritable(path);
        }
        if (Files.isDirectory(path)) {
            Path probe = null;
            try {
                probe = Files.createTempFile(path, ".write-check-", ".tmp");
                return true;
            } catch (IOException e) {
                return false;
            } finally {
                if (probe != null) {
                    try {
                        Files.deleteIfExists(probe);
                    } catch (IOException ignored) {
                        // 探测文件清理失败不影响判断结果
                    }
                }
            }
        }
        try (java.io.OutputStream ignored = Files.newOutputStream(
                path, java.nio.file.StandardOpenOption.APPEND)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 工具类不允许实例化
     */
    private FileValidationUtil() {
    }
}
