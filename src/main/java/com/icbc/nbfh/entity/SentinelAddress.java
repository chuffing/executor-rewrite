package com.icbc.nbfh.entity;

import com.icbc.nbfh.common.constant.CollectConstant;
import com.icbc.nbfh.util.NetworkUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * Sentinel 地址（host:port）
 * 负责地址解析与合法性校验：端口 1~65535，host 需满足平台 IPv4/varchar(20) 约束
 */
@Slf4j
@Getter
public final class SentinelAddress {

    private final String host;
    private final int port;

    public SentinelAddress(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** 唯一标识：ip:port */
    public String key() {
        return host + ":" + port;
    }

    /**
     * 解析 host:port 配置项
     *
     * @return 非法地址返回 null（调用方跳过并记录日志）
     */
    public static SentinelAddress parse(String address) {
        if (!StringUtils.hasText(address)) {
            return null;
        }
        String text = address.trim();
        int idx = text.lastIndexOf(':');
        if (idx <= 0 || idx == text.length() - 1) {
            log.warn("非法 Sentinel 地址（应为 host:port）: {}", address);
            return null;
        }
        String host = text.substring(0, idx).trim();
        int port;
        try {
            port = Integer.parseInt(text.substring(idx + 1).trim());
        } catch (NumberFormatException e) {
            log.warn("非法 Sentinel 端口: {}", address);
            return null;
        }
        if (port < CollectConstant.MIN_PORT || port > CollectConstant.MAX_PORT) {
            log.warn("Sentinel 端口超出范围: {}", address);
            return null;
        }
        // 平台 node_ip 为 varchar(20)，只接受 IPv4/短主机名
        String ip = NetworkUtils.toPlatformIpv4(host);
        if (ip == null) {
            log.warn("Sentinel 地址不满足平台 IPv4/长度要求，已跳过: {}", address);
            return null;
        }
        return new SentinelAddress(ip, port);
    }
}
