package com.icbc.nbfh.collector;

import com.icbc.nbfh.util.RedisInfoParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INFO 文本解析单元测试。
 */
class RedisInfoParserTest {

    @Test
    @DisplayName("解析 INFO 时应忽略注释与空行，并保留冒号后的完整值")
    void shouldParseInfoSectionsAndSkipComments() {
        String info = "# Server\r\n"
                + "redis_version:6.2.7\r\n"
                + "\r\n"
                + "tcp_port:6379\r\n"
                + "cmdstat_get:calls=10,usec=250,usec_per_call=25.00\r\n"
                + "invalid-line-without-colon\r\n";

        Map<String, String> parsed = RedisInfoParser.parse(info);

        assertEquals("6.2.7", parsed.get("redis_version"));
        assertEquals("6379", parsed.get("tcp_port"));
        assertEquals("calls=10,usec=250,usec_per_call=25.00", parsed.get("cmdstat_get"));
        // 注释行、空行、无冒号行均被忽略，仅保留 3 个有效字段
        assertEquals(3, parsed.size());
    }

    @Test
    @DisplayName("null 与空白 INFO 应返回空 Map")
    void shouldReturnEmptyMapForNullAndBlank() {
        assertTrue(RedisInfoParser.parse(null).isEmpty());
        assertTrue(RedisInfoParser.parse("").isEmpty());
    }

    @Test
    @DisplayName("commandstats 应汇总所有命令的 calls 与 usec")
    void shouldSumCommandStats() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("cmdstat_get", "calls=10,usec=250,usec_per_call=25.00");
        info.put("cmdstat_set", "calls=5,usec=100,usec_per_call=20.00");
        info.put("redis_version", "6.2.7");

        long[] stats = RedisInfoParser.sumCommandStats(info);

        assertArrayEquals(new long[]{15L, 350L}, stats);
    }

    @Test
    @DisplayName("非法 commandstats：非法 calls 记 0，usec 仍按可解析值累计")
    void shouldIgnoreMalformedCommandStats() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("cmdstat_get", "calls=abc,usec=250");
        info.put("cmdstat_set", "calls=1,usec=2");

        assertArrayEquals(new long[]{1L, 252L}, RedisInfoParser.sumCommandStats(info));
        assertArrayEquals(new long[]{0L, 0L}, RedisInfoParser.sumCommandStats(null));
    }

    @Test
    @DisplayName("human 内存字符串应换算为字节")
    void shouldParseHumanBytes() {
        assertEquals(1024L, RedisInfoParser.parseHumanBytes("1024"));
        assertEquals(1024L, RedisInfoParser.parseHumanBytes("1K"));
        assertEquals(1024L * 1024L, RedisInfoParser.parseHumanBytes("1M"));
        assertEquals((long) (1.5 * (1L << 30)), RedisInfoParser.parseHumanBytes("1.5G"));
        assertEquals(1L << 40, RedisInfoParser.parseHumanBytes("1T"));
        assertEquals(0L, RedisInfoParser.parseHumanBytes(" "));
        assertEquals(0L, RedisInfoParser.parseHumanBytes("not-a-number"));
    }

    @Test
    @DisplayName("宽松数字解析：空白/非法返回 0")
    void shouldParseLenientNumbers() {
        assertEquals(42L, RedisInfoParser.toLong("42"));
        assertEquals(0L, RedisInfoParser.toLong("x"));
        assertEquals(0L, RedisInfoParser.toLong(null));
        assertEquals(1.5D, RedisInfoParser.toDouble("1.5"));
        assertEquals(0D, RedisInfoParser.toDouble(""));
    }
}
