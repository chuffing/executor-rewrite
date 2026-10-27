package com.icbc.nbfh.collector;

import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.SentinelNodeDTO;
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
 * 哨兵节点不保存业务数据，执行器通过 {@code SENTINEL sentinels <master-name>}
 * 获取该 master 下所有哨兵节点的 ip/port/flags，转换为平台
 * {@code sentinel_node_info} 需要的节点名、IP、端口、在线状态
 *
 * 采集策略：
 *   遍历 {@code agent.sentinel.nodes} 与 {@code agent.sentinel.host:port}，
 *       任一可达即可拿到完整哨兵列表；
 *   不可达的配置节点也会以“离线”上报，便于平台及时发现哨兵故障；
 *   未启用或未配置哨兵时返回空列表，不影响 Redis 指标采集
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
     * 采集 Sentinel 节点列表
     *
     * @return 节点列表；未启用/未配置时返回空列表，不抛异常
     */
    public List<SentinelNodeDTO> collect() {
        AgentProperties.SentinelConfig config = properties.getSentinel();
        if (config == null || !config.isEnabled()) {
            return Collections.emptyList();
        }

        String masterName = resolveMasterName(config);
        List<HostPort> configuredNodes = configuredNodes(config);
        if (configuredNodes.isEmpty()) {
            log.debug("未配置 Sentinel 地址，跳过 Sentinel 采集");
            return Collections.emptyList();
        }

        // ip:port -> node，保持插入顺序；先放离线兜底，在线结果覆盖
        Map<String, SentinelNodeDTO> nodes = new LinkedHashMap<>();
        for (HostPort hostPort : configuredNodes) {
            nodes.put(hostPort.key(), offlineNode(hostPort, masterName));
        }

        for (HostPort hostPort : configuredNodes) {
            try {
                List<Map<String, String>> sentinels =
                        querySentinels(hostPort, masterName, config.getTimeout());
                if (sentinels.isEmpty()) {
                    // 能连上但未返回节点：保留离线兜底，避免伪造在线节点
                    continue;
                }
                // 返回列表是权威数据：移除按配置地址预置的兜底节点，
                // 避免 127.0.0.1 之类的配置地址与真实节点 IP 重复上报
                nodes.remove(hostPort.key());
                for (Map<String, String> item : sentinels) {
                    SentinelNodeDTO node = toNode(item, masterName);
                    if (node != null) {
                        nodes.put(node.getNodeIp() + ":" + node.getNodePort(), node);
                    }
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
        return result;
    }

    /** master 名称优先取 service-name，未配置时使用 mymaster */
    private String resolveMasterName(AgentProperties.SentinelConfig config) {
        String masterName = StringUtils.hasText(config.getServiceName())
                ? config.getServiceName().trim() : "mymaster";
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
     * 向指定哨兵发送 {@code SENTINEL sentinels <masterName>}，解析返回的节点数组
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
                return Collections.emptyList();
            }

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
        } finally {
            try {
                jedis.close();
            } catch (Exception ignored) {
                // 关闭失败不影响采集结果
            }
        }
    }

    private SentinelNodeDTO toNode(Map<String, String> item, String masterName) {
        String ip = resolveNodeIp(item.get("ip"));
        String port = item.get("port");
        if (!StringUtils.hasText(ip) || !isValidPort(port)) {
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
