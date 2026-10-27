package com.icbc.nbfh.util;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 本机地址识别工具
 * 只返回 IPv4 地址
 * 多网卡场景优先取默认路由网卡地址，避免拿到 docker0 / 虚拟网卡 / IPv6 地址导致注册失败
 */
@Slf4j
public final class NetworkUtils {

    private static final String[] VIRTUAL_IFACE_PREFIXES = {
            "docker", "br-", "veth", "virbr", "tun", "tap", "lo"
    };

    private NetworkUtils() {
    }

    /**
     * 解析执行器对平台可见的 IPv4 地址
     *
     * @param configured 配置的 agent.host-ip，可为空
     */
    public static String resolveHostIp(String configured) {
        if (configured != null && !configured.trim().isEmpty()) {
            return validateIpv4(configured.trim());
        }

        String ip = fromDefaultRoute();
        if (ip == null) {
            ip = firstIpv4(true);
        }
        if (ip == null) {
            ip = firstIpv4(false);
        }
        if (ip == null) {
            throw new IllegalStateException(
                    "无法自动识别本机 IPv4 地址，请通过 AGENT_HOST_IP 或 agent.host-ip 配置");
        }
        log.info("自动识别到本机对外 IPv4 地址: {}", ip);
        return validateIpv4(ip);
    }

    /** 平台字段长度限制为 varchar(20)，IPv6 无法存储，直接拒绝并提示 */
    private static String validateIpv4(String ip) {
        if (ip.contains(":")) {
            throw new IllegalArgumentException(
                    "hostIp 仅支持 IPv4（平台 host_ip 字段为 varchar(20)）: " + ip);
        }
        if (ip.length() > 20) {
            throw new IllegalArgumentException("hostIp 长度不能超过 20: " + ip);
        }
        return ip;
    }

    /** 读取 Linux 默认路由所在网卡的 IPv4 地址 */
    private static String fromDefaultRoute() {
        File routeFile = new File("/proc/net/route");
        if (!routeFile.canRead()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(java.nio.file.Files.newInputStream(routeFile.toPath()),
                        StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                // Destination=00000000 表示默认路由
                if ("00000000".equals(parts[1])) {
                    String ip = ipv4OfInterface(parts[0]);
                    if (ip != null) {
                        return ip;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("读取 /proc/net/route 失败: {}", e.getMessage());
        }
        return null;
    }

    private static String ipv4OfInterface(String name) {
        try {
            NetworkInterface networkInterface = NetworkInterface.getByName(name);
            if (networkInterface == null) {
                return null;
            }
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (isUsableIpv4(address)) {
                    return address.getHostAddress();
                }
            }
        } catch (Exception e) {
            log.debug("读取网卡 {} 地址失败: {}", name, e.getMessage());
        }
        return null;
    }

    /**
     * 枚举网卡地址
     * @param siteLocalOnly true=只取内网地址（10/172.16/192.168），false=允许公网地址
     */
    private static String firstIpv4(boolean siteLocalOnly) {
        List<String> candidates = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()
                        || networkInterface.isVirtual()) {
                    continue;
                }
                if (isVirtualName(networkInterface.getName())) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!isUsableIpv4(address)) {
                        continue;
                    }
                    if (siteLocalOnly && !address.isSiteLocalAddress()) {
                        continue;
                    }
                    candidates.add(address.getHostAddress());
                }
            }
        } catch (Exception e) {
            log.debug("枚举本机网卡失败: {}", e.getMessage());
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static boolean isVirtualName(String name) {
        if (name == null) {
            return true;
        }
        String lower = name.toLowerCase();
        for (String prefix : VIRTUAL_IFACE_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUsableIpv4(InetAddress address) {
        return address instanceof Inet4Address
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isAnyLocalAddress();
    }
}
