package com.icbc.nbfh.collector;

import com.icbc.nbfh.entity.SentinelAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Sentinel 地址解析单元测试。
 */
class SentinelAddressTest {

    @Test
    @DisplayName("合法地址应解析出 host、port 与 key")
    void shouldParseValidAddress() {
        SentinelAddress address = SentinelAddress.parse("10.10.10.31:26379");

        assertEquals("10.10.10.31", address.getHost());
        assertEquals(26379, address.getPort());
        assertEquals("10.10.10.31:26379", address.key());
    }

    @Test
    @DisplayName("非法地址应返回 null")
    void shouldRejectInvalidAddress() {
        assertNull(SentinelAddress.parse(null));
        assertNull(SentinelAddress.parse(""));
        assertNull(SentinelAddress.parse("   "));
        assertNull(SentinelAddress.parse("10.10.10.31"));
        assertNull(SentinelAddress.parse(":26379"));
        assertNull(SentinelAddress.parse("10.10.10.31:"));
        assertNull(SentinelAddress.parse("10.10.10.31:abc"));
        assertNull(SentinelAddress.parse("10.10.10.31:0"));
        assertNull(SentinelAddress.parse("10.10.10.31:70000"));
    }

    @Test
    @DisplayName("超过平台字段长度的地址应返回 null")
    void shouldRejectOverlongHost() {
        assertNull(SentinelAddress.parse("hostname-way-too-long-for-platform:26379"));
    }
}
