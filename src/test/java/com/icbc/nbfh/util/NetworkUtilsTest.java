package com.icbc.nbfh.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本机地址判定单元测试。
 */
class NetworkUtilsTest {

    @Test
    @DisplayName("未配置或 loopback 主机判定")
    void shouldDetectLoopbackHost() {
        assertTrue(NetworkUtils.isLoopbackHost(null));
        assertTrue(NetworkUtils.isLoopbackHost("  "));
        assertTrue(NetworkUtils.isLoopbackHost("127.0.0.1"));
        assertTrue(NetworkUtils.isLoopbackHost("localhost"));
        assertTrue(NetworkUtils.isLoopbackHost("0.0.0.0"));
        assertTrue(NetworkUtils.isLoopbackHost("::1"));
        assertFalse(NetworkUtils.isLoopbackHost("10.10.10.31"));
    }

    @Test
    @DisplayName("loopback IP 判定：空白入参不视为 loopback")
    void shouldDetectLoopbackIp() {
        assertFalse(NetworkUtils.isLoopbackIp(null));
        assertFalse(NetworkUtils.isLoopbackIp(""));
        assertTrue(NetworkUtils.isLoopbackIp("127.0.0.1"));
        assertTrue(NetworkUtils.isLoopbackIp("127.10.20.30"));
        assertTrue(NetworkUtils.isLoopbackIp("0.0.0.0"));
        assertFalse(NetworkUtils.isLoopbackIp("10.10.10.31"));
    }

    @Test
    @DisplayName("平台 IPv4 转换：IPv4 原样返回，空值/非法返回 null")
    void shouldConvertToPlatformIpv4() {
        assertEquals("10.10.10.31", NetworkUtils.toPlatformIpv4("10.10.10.31"));
        assertEquals("10.10.10.31", NetworkUtils.toPlatformIpv4(" 10.10.10.31 "));
        assertNull(NetworkUtils.toPlatformIpv4(null));
        assertNull(NetworkUtils.toPlatformIpv4(""));
        assertNull(NetworkUtils.toPlatformIpv4("hostname-way-too-long-for-platform"));
    }
}
