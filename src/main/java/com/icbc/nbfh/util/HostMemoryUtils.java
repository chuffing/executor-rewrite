package com.icbc.nbfh.util;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 主机内存使用率读取（Linux /proc/meminfo）
 *
 * 非 Linux 或读取失败返回 null，由调用方决定回退口径
 */
@Slf4j
public final class HostMemoryUtils {

    private static final String MEMINFO_PATH = "/proc/meminfo";

    private HostMemoryUtils() {
    }

    /**
     * 读取 /proc/meminfo 计算服务器内存占用率（%）
     *
     * @return 无法读取时返回 null
     */
    public static Double readUsagePercent() {
        File meminfo = new File(MEMINFO_PATH);
        if (!meminfo.canRead()) {
            return null;
        }
        long totalKb = 0;
        long availableKb = -1;
        long freeKb = 0;
        long buffersKb = 0;
        long cachedKb = 0;
        long sReclaimableKb = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(meminfo.toPath()),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int idx = line.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim().split("\\s+")[0];
                long kb = parseKb(value);
                switch (key) {
                    case "MemTotal":
                        totalKb = kb;
                        break;
                    case "MemAvailable":
                        availableKb = kb;
                        break;
                    case "MemFree":
                        freeKb = kb;
                        break;
                    case "Buffers":
                        buffersKb = kb;
                        break;
                    case "Cached":
                        cachedKb = kb;
                        break;
                    case "SReclaimable":
                        sReclaimableKb = kb;
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            log.debug("读取 /proc/meminfo 失败: {}", e.getMessage());
            return null;
        }
        if (totalKb <= 0) {
            return null;
        }
        long available = availableKb >= 0
                ? availableKb
                : freeKb + buffersKb + cachedKb + sReclaimableKb;
        long used = Math.max(0, totalKb - available);
        return used * 100.0 / totalKb;
    }

    private static long parseKb(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
