package com.icbc.nbfh.collector;

import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.SentinelNodeDTO;
import com.icbc.nbfh.util.NetworkUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import redis.clients.jedis.Client;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Protocol;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sentinel 节点采集器
 *
 * Redis 的 SENTINEL sentinels <master-name> 只返回其他哨兵，
 * 不包含当前接收命令的哨兵自身因此执行器对每个可达的配置哨兵：
 *   用“当前服务器 IP + 该 Sentinel 配置端口”组装自身节点，并标记在线；
 *   再调用 SENTINEL sentinels，把返回的其他哨兵合并进来；
 *   按 ip:port 去重，避免配置为 127.0.0.1 时出现重复或错误地址
 *
 * 不可达的配置节点保留为离线兜底；未启用时返回空列表
 * 采集结果过滤 localhost/127.0.0.1；自身 IP 解析失败时不再使用 loopback 兜底，
 * 最终未采集到任何有效节点时抛出异常
 */
@Slf4j
@Component
public class SentinelInfoCollector {

    /** 平台端单次最多接收的哨兵节点数 */
    private static final int MAX_NODE_SIZE = 50;

    /** 平台端 sentinel_node_info.node_ip 字段长度 */
    private static final int MAX_NODE_IP_LENGTH = 20;

    /** 平台端 nodeName/masterName 字段长度 */
    private static final int MAX_NAME_LENGTH = 64;

    private final AgentProperties properties;

    public SentinelInfoCollector(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 采集 Sentinel 节点列表，包含每台可达哨兵自身和其他已发现哨兵
     *
     * @return 节点列表；未启用时返回空列表
     * @throws IllegalStateException 已启用但未配置有效地址，或最终未采集到任何有效节点
     */
    public List<SentinelNodeDTO> collect() {
        AgentProperties.SentinelConfig config = properties.getSentinel();
        if (config == null || !config.isEnabled()) {
            return Collections.emptyList();
        }

        String masterName = resolveMasterName(config);
        List<HostPort> configuredNodes = configuredNodes(config);
        if (configuredNodes.isEmpty()) {
            throw new IllegalStateException("Sentinel 已启用但未配置有效的 Sentinel 地址");
        }

        // ip:port -> node 先预置配置节点为离线，查询成功的节点覆盖为在线
        // 过滤 loopback 地址：平台上不允许出现 127.0.0.1/localhost 的离线兜底
        Map<String, SentinelNodeDTO> nodes = new LinkedHashMap<>();
        for (HostPort hostPort : configuredNodes) {
            if (isLoopbackIp(hostPort.host)) {
                log.debug("跳过 loopback 配置节点的离线兜底: {}", hostPort.key());
                continue;
            }
            nodes.put(hostPort.key(), offlineNode(hostPort, masterName));
        }

        for (HostPort hostPort : configuredNodes) {
            HostPort selfAddress;
            try {
                // 自身地址必须解析为真实 IP，禁止 localhost/127.0.0.1 兜底
                selfAddress = resolveSelfAddress(hostPort);
            } catch (Exception e) {
                log.error("解析 Sentinel 自身地址失败，跳过 {}: {}", hostPort.key(), e.getMessage());
                continue;
            }

            try {
                // SENTINEL sentinels 只返回其他哨兵
                List<Map<String, String>> otherSentinels =
                        querySentinels(hostPort, masterName, config.getTimeout());

                nodes.remove(hostPort.key());
                nodes.put(selfAddress.key(), buildNode(
                        selfAddress.host, String.valueOf(selfAddress.port), "1", masterName));

                // 合并其他哨兵，与自身地址相同的记录跳过，避免重复
                for (Map<String, String> item : otherSentinels) {
                    if (isSameAddress(item, selfAddress)) {
                        continue;
                    }
                    SentinelNodeDTO node = toNode(item, masterName);
                    if (node == null) {
                        continue;
                    }
                    nodes.put(node.getNodeIp() + ":" + node.getNodePort(), node);
                    if (nodes.size() >= MAX_NODE_SIZE) {
                        break;
                    }
                }
            } catch (Exception e) {
                // 单个哨兵不可达：保留离线兜底，不阻断其它节点
                log.debug("查询 Sentinel {} 失败: {}", hostPort.key(), e.getMessage());
            }
        }

        List<SentinelNodeDTO> result = new ArrayList<>(nodes.values());
        result.sort((a, b) -> (a.getNodeIp() + ":" + a.getNodePort())
                .compareTo(b.getNodeIp() + ":" + b.getNodePort()));
        if (result.size() > MAX_NODE_SIZE) {
            result = new ArrayList<>(result.subList(0, MAX_NODE_SIZE));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("未采集到任何有效的 Sentinel 节点（已过滤 localhost/127.0.0.1）");
        }
        return result;
    }

    /** master 名称优先取 service-name，未配置时使用 mymaster */
    private String resolveMasterName(AgentProperties.SentinelConfig config) {
        String masterName = StringUtils.hasText(config.getServiceName())
                ? config.getServiceName().trim() : "mymaster1";
        return masterName.length() > MAX_NAME_LENGTH
                ? masterName.substring(0, MAX_NAME_LENGTH) : masterName;
    }

    /** 合并 host:port 与 nodes 配置，去重 */
    private List<HostPort> configuredNodes(AgentProperties.SentinelConfig config) {
        Set<String> addresses = new LinkedHashSet<>();
        if (StringUtils.hasText(config.getHost())) {
            addresses.add(config.getHost().trim() + ":" + config.getPort());
        }
        if (config.getNodes() != null) {
            for (String raw : config.getNodes()) {
                if (!StringUtils.hasText(raw)) {
                    continue;
                }
                // 兼容 SENTINEL_NODES 未被 Spring 拆分的场景
                for (String part : raw.split(",")) {
                    if (StringUtils.hasText(part)) {
                        addresses.add(part.trim());
                    }
                }
            }
        }

        List<HostPort> result = new ArrayList<>();
        for (String address : addresses) {
            HostPort hostPort = parseAddress(address);
            if (hostPort != null) {
                result.add(hostPort);
            }
        }
        return result;
    }

    /**
     * 查询一个 Sentinel 已发现的其他哨兵
     *
     * PING 失败会抛异常，由调用方保留离线兜底；PING 成功但
     * SENTINEL sentinels 查询失败时返回空列表，当前哨兵自身仍会上报在线
     */
    private List<Map<String, String>> querySentinels(HostPort hostPort,
                                                     String masterName,
                                                     int timeout) {
        Jedis jedis = new Jedis(hostPort.host, hostPort.port, timeout);
        try {
            String password = properties.getSentinel().getPassword();
            if (StringUtils.hasText(password)) {
                jedis.auth(password);
            }
            if (!"PONG".equalsIgnoreCase(jedis.ping())) {
                throw new IllegalStateException("Sentinel PING 未返回 PONG");
            }

            try {
                Client client = jedis.getClient();
                client.sendCommand(Protocol.Command.SENTINEL, "sentinels", masterName);
                List<Object> reply = client.getObjectMultiBulkReply();
                List<Map<String, String>> result = new ArrayList<>();
                if (reply == null) {
                    return result;
                }
                for (Object entry : reply) {
                    Map<String, String> item = toMap(entry);
                    if (!item.isEmpty()) {
                        result.add(item);
                    }
                }
                return result;
            } catch (Exception e) {
                // 已经 PING 成功，至少把当前哨兵自身上报为在线
                log.debug("查询 Sentinel 列表失败: {}", e.getMessage());
                return Collections.emptyList();
            }
        } finally {
            try {
                jedis.close();
            } catch (Exception ignored) {
                // 关闭失败不影响采集结果
            }
        }
    }

    /**
     * 当前查询哨兵的自身地址：当前服务器 IP + 该 Sentinel 配置端口
     */
    private HostPort resolveSelfAddress(HostPort configured) {
        String serverIp = resolveServerIp(configured.host);
        return new HostPort(serverIp, configured.port);
    }

    /**
     * 解析当前服务器对平台可见的 IP
     * 优先使用 agent.host-ip / 自动识别的本机 IPv4；无法识别时，非 loopback 配置回退配置地址；loopback 配置不再兜底，直接抛异常
     */
    private String resolveServerIp(String configuredHost) {
        try {
            String serverIp = NetworkUtils.resolveHostIp(properties.getHostIp());
            String normalized = resolveNodeIp(serverIp);
            if (StringUtils.hasText(normalized) && !isLoopbackIp(normalized)) {
                return normalized;
            }
        } catch (Exception e) {
            log.debug("解析当前服务器 IP 失败: {}", e.getMessage());
        }

        if (!isLoopbackHost(configuredHost)) {
            String normalized = resolveNodeIp(configuredHost);
            if (StringUtils.hasText(normalized) && !isLoopbackIp(normalized)) {
                return normalized;
            }
        }
        // 去除 localhost / 127.0.0.1 兜底，无法解析真实 IP 时直接报错
        throw new IllegalStateException(
                "无法解析当前服务器有效 IP，禁止使用 localhost/127.0.0.1 兜底，配置地址: "
                        + configuredHost);
    }

    /** 返回的其他哨兵是否与自身地址相同 */
    private boolean isSameAddress(Map<String, String> item, HostPort selfAddress) {
        String ip = resolveNodeIp(item.get("ip"));
        String port = item.get("port");
        if (!StringUtils.hasText(ip) || !isValidPort(port)) {
            return false;
        }
        return selfAddress.key().equals(ip + ":" + port.trim());
    }

    private boolean isLoopbackHost(String host) {
        if (!StringUtils.hasText(host)) {
            return true;
        }
        String value = host.trim();
        return "127.0.0.1".equals(value)
                || "0.0.0.0".equals(value)
                || "localhost".equalsIgnoreCase(value)
                || "::1".equals(value);
    }

    /**
     * 是否为 loopback/不可用地址（127.0.0.0/8、0.0.0.0、::1、localhost）
     * 这类地址不允许上报到平台
     */
    private boolean isLoopbackIp(String ip) {
        if (!StringUtils.hasText(ip)) {
            return false;
        }
        String value = ip.trim();
        if ("0.0.0.0".equals(value) || "::1".equals(value)
                || "localhost".equalsIgnoreCase(value)) {
            return true;
        }
        return value.startsWith("127.");
    }

    private SentinelNodeDTO toNode(Map<String, String> item, String masterName) {
        String ip = resolveNodeIp(item.get("ip"));
        String port = item.get("port");
        if (!StringUtils.hasText(ip) || !isValidPort(port)) {
            return null;
        }
        if (isLoopbackIp(ip)) {
            log.debug("忽略 loopback 哨兵节点: {}:{}", ip, port);
            return null;
        }
        String status = isOfflineFlags(item.get("flags")) ? "0" : "1";
        return buildNode(ip, port.trim(), status, masterName);
    }

    private SentinelNodeDTO offlineNode(HostPort hostPort, String masterName) {
        return buildNode(hostPort.host, String.valueOf(hostPort.port), "0", masterName);
    }

    private SentinelNodeDTO buildNode(String ip, String port, String status, String masterName) {
        String nodeName = "sentinel-" + ip + ":" + port;
        if (nodeName.length() > MAX_NAME_LENGTH) {
            nodeName = nodeName.substring(0, MAX_NAME_LENGTH);
        }
        return new SentinelNodeDTO(nodeName, ip, port, status, masterName);
    }

    /** 嵌套数组（字段名/字段值交替）转 Map */
    private Map<String, String> toMap(Object entry) {
        if (!(entry instanceof List)) {
            return Collections.emptyMap();
        }
        List<?> list = (List<?>) entry;
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < list.size(); i += 2) {
            String key = asString(list.get(i));
            String value = asString(list.get(i + 1));
            if (StringUtils.hasText(key)) {
                map.put(key, value);
            }
        }
        return map;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    /** flags 中包含 s_down/o_down/disconnected 视为离线 */
    private boolean isOfflineFlags(String flags) {
        if (!StringUtils.hasText(flags)) {
            return false;
        }
        String lower = flags.toLowerCase(Locale.ROOT);
        return lower.contains("s_down") || lower.contains("o_down")
                || lower.contains("disconnected");
    }

    private boolean isValidPort(String port) {
        if (!StringUtils.hasText(port)) {
            return false;
        }
        try {
            int value = Integer.parseInt(port.trim());
            return value >= 1 && value <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 平台端 node_ip 为 varchar(20)，只接受 IPv4/短主机名
     * Sentinel 返回主机名时尽量解析为 IPv4
     */
    private String resolveNodeIp(String host) {
        if (!StringUtils.hasText(host)) {
            return null;
        }
        String value = host.trim();
        if (value.length() <= MAX_NODE_IP_LENGTH && !value.contains(":")) {
            return value;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            String ip = address.getHostAddress();
            return ip.length() <= MAX_NODE_IP_LENGTH && !ip.contains(":") ? ip : null;
        } catch (Exception e) {
            log.debug("解析 Sentinel 节点地址 {} 失败: {}", value, e.getMessage());
            return null;
        }
    }

    private HostPort parseAddress(String address) {
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
        String portText = text.substring(idx + 1).trim();
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            log.warn("非法 Sentinel 端口: {}", address);
            return null;
        }
        if (port < 1 || port > 65535) {
            log.warn("Sentinel 端口超出范围: {}", address);
            return null;
        }
        String ip = resolveNodeIp(host);
        if (ip == null) {
            log.warn("Sentinel 地址不满足平台 IPv4/长度要求，已跳过: {}", address);
            return null;
        }
        return new HostPort(ip, port);
    }

    private static final class HostPort {
        private final String host;
        private final int port;

        private HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        private String key() {
            return host + ":" + port;
        }
    }
}