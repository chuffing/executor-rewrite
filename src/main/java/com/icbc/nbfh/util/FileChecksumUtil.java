package com.icbc.nbfh.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文件摘要计算工具。
 */
public final class FileChecksumUtil {

    /**
     * 工具类不允许实例化。
     */
    private FileChecksumUtil() {
    }

    /**
     * 计算文件的SHA256摘要。
     */
    public static String calculateSha256(Path file) throws IOException {

        MessageDigest messageDigest;

        try {
            messageDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持SHA-256", e);
        }

        try (InputStream inputStream = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int length;

            while ((length = inputStream.read(buffer)) != -1) {
                messageDigest.update(buffer, 0, length);
            }
        }

        StringBuilder result = new StringBuilder();

        for (byte value : messageDigest.digest()) {
            result.append(String.format("%02x", value & 0xff));
        }

        return result.toString();
    }
}
