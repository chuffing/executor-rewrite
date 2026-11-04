package com.icbc.nbfh.collector;

import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redis INFO 文本解析
 * 纯函数工具，便于单元测试；不持有连接、不感知采集流程
 */
public final class RedisInfoParser {

    /** Redis 内存 human 字符串解析，如 15.62G / 800M / 123K */
    private static final Pattern MEM_PATTERN =
            Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?)", Pattern.CASE_INSENSITIVE);

    private static final String CMD_STAT_PREFIX = "cmdstat_";
    private static final String CALLS_PREFIX = "calls=";
    private static final String USEC_PREFIX = "usec=";

    private RedisInfoParser() {
    }

    /**
     * 解析 INFO 返回为 key-value
     * 忽略空行、注释行（# 开头）与无冒号行
     */
    public static Map<String, String> parse(String infoText) {
        Map<String, String> map = new HashMap<>();
        if (infoText == null) {
            return map;
        }
        for (String line : infoText.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int idx = trimmed.indexOf(':');
            if (idx > 0) {
                map.put(trimmed.substring(0, idx), trimmed.substring(idx + 1).trim());
            }
        }
        return map;
    }

    /**
     * 汇总 commandstats：返回 [总 calls, 总 usec]
     */
    public static long[] sumCommandStats(Map<String, String> info) {
        long totalCalls = 0;
        long totalUsec = 0;
        if (info == null) {
            return new long[]{totalCalls, totalUsec};
        }
        for (Map.Entry<String, String> entry : info.entrySet()) {
            if (!entry.getKey().startsWith(CMD_STAT_PREFIX)) {
                continue;
            }
            if (!StringUtils.hasText(entry.getValue())) {
                continue;
            }
            for (String pair : entry.getValue().split(",")) {
                String item = pair.trim();
                if (item.startsWith(CALLS_PREFIX)) {
                    totalCalls += toLong(item.substring(CALLS_PREFIX.length()));
                } else if (item.startsWith(USEC_PREFIX)) {
                    totalUsec += toLong(item.substring(USEC_PREFIX.length()));
                }
            }
        }
        return new long[]{totalCalls, totalUsec};
    }

    /** 解析 Redis human 内存字符串（15.62G / 800M / 123K / 1024B），失败返回 0 */
    public static long parseHumanBytes(String raw) {
        if (!StringUtils.hasText(raw)) {
            return 0L;
        }
        Matcher matcher = MEM_PATTERN.matcher(raw.trim());
        if (!matcher.find()) {
            return toLong(raw);
        }
        double value = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2).toUpperCase(Locale.ROOT);
        long multiplier;
        switch (unit) {
            case "T":
                multiplier = 1L << 40;
                break;
            case "G":
                multiplier = 1L << 30;
                break;
            case "M":
                multiplier = 1L << 20;
                break;
            case "K":
                multiplier = 1L << 10;
                break;
            default:
                multiplier = 1L;
                break;
        }
        return (long) (value * multiplier);
    }

    /** 宽松解析 long，空白/非法返回 0 */
    public static long toLong(String value) {
        if (!StringUtils.hasText(value)) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 宽松解析 double，空白/非法返回 0 */
    public static double toDouble(String value) {
        if (!StringUtils.hasText(value)) {
            return 0D;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0D;
        }
    }
}
