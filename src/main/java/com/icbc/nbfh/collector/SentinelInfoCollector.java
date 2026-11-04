package com.icbc.nbfh.collector;

import com.icbc.nbfh.common.constant.CollectConstant;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.SentinelNodeDTO;
import com.icbc.nbfh.util.NetworkUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import redis.clients.jedis.Client;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Protocol;

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
 * Redis 的 {@code SENTINEL sentinels <master-name>} 只返回其他哨兵，不包含当前接收命令的哨兵自身，
 * 因此执行器对每个可达的配置哨兵：
 *   用“当前服务器 IP + 该 Sentinel 配置端口”组装自身节点，并标记在线；
 *   再调用 {@code SENTINEL sentinels}，把返回的其他哨兵合并进来；
 *   按 ip:port 去重，避免配置为 127.0.0.1 时出现重复或错误地址
 *
 * 不可达的配置节点保留为离线兜底；未启用时返回空列表
 * 采集结果过滤 localhost/127.0.0.1；自身 IP 解析失败时不再使用 loopback 兜底，
 * 最终未采集到任何有效节点时抛出异常
 */
@Slf4j
@Component
public class SentinelInfoCollector {

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
        List<SentinelAddress> configuredNodes = configuredNodes(config);
        if (configuredNodes.isEmpty()) {
            throw new IllegalStateException("Sentinel 已启用但未配置有效的 Sentinel 地址");
        }

        // ip:port -> node 先预置配置节点为离线，查询成功的节点覆盖为在线
        // 过滤 loopback 地址：平台上不允许出现 127.0.0.1/localhost 的离线兜底
        Map<String, SentinelNodeDTO> nodes = offlineFallbacks(configuredNodes, masterName);

        for (SentinelAddress configured : configuredNodes) {
            SentinelAddress selfAddress;
            try {
                // 自身地址必须解析为真实 IP，禁止 localhost/127.0.0.1 兜底
                selfAddress = resolveSelfAddress(configured);
            } catch (Exception e) {
                log.error("解析 Sentinel 自身地址失败，跳过 {}: {}", configured.key(), e.getMessage());
                continue;
            }
            try {
                mergeFromSentinel(nodes, configured, selfAddress, masterName, config.getTimeout());
            } catch (Exception e) {
                // 单个哨兵不可达：保留离线兜底，不阻断其它节点
                log.debug("查询 Sentinel {} 失败: {}", configured.key(), e.getMessage());
            }
        }

        return finalizeNodes(nodes);
    }

    /** 配置节点预置为离线兜底，过滤 loopback 地址 */
    private Map<String, SentinelNodeDTO> offlineFallbacks(List<SentinelAddress> configuredNodes,
                                                          String masterName) {
        Map<String, SentinelNodeDTO> nodes = new LinkedHashMap<>();
        for (SentinelAddress address : configuredNodes) {
            if (NetworkUtils.isLoopbackIp(address.getHost())) {
                log.debug("跳过 loopback 配置节点的离线兜底: {}", address.key());
                continue;
            }
            nodes.put(address.key(), offlineNode(address, masterName));
        }
        return nodes;
    }

    /**
     * 查询单个哨兵并合并结果：自身节点标记在线，其他哨兵按 ip:port 去重合并
     */
    private void mergeFromSentinel(Map<String, SentinelNodeDTO> nodes,
                                   SentinelAddress configured,
                                   SentinelAddress selfAddress,
                                   String masterName,
                                   int timeout) {
        // SENTINEL sentinels 只返回其他哨兵
        List<Map<String, String>> otherSentinels = querySentinels(configured, masterName, timeout);

        nodes.remove(configured.key());
        nodes.put(selfAddress.key(), buildNode(
                selfAddress.getHost(), String.valueOf(selfAddress.getPort()),
                CollectConstant.SENTINEL_STATUS_ONLINE, masterName));

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
            if (nodes.size() >= CollectConstant.MAX_SENTINEL_NODES) {
                break;
            }
        }
    }

    /** 排序、截断并校验结果 */
    private List<SentinelNodeDTO> finalizeNodes(Map<String, SentinelNodeDTO> nodes) {
        List<SentinelNodeDTO> result = new ArrayList<>(nodes.values());
        result.sort((a, b) -> (a.getNodeIp() + ":" + a.getNodePort())
                .compareTo(b.getNodeIp() + ":" + b.getNodePort()));
        if (result.size() > CollectConstant.MAX_SENTINEL_NODES) {
            result = new ArrayList<>(result.subList(0, CollectConstant.MAX_SENTINEL_NODES));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("未采集到任何有效的 Sentinel 节点（已过滤 localhost/127.0.0.1）");
        }
        return result;
    }

    /** master 名称优先取 service-name，未配置时使用默认组名 */
    private String resolveMasterName(AgentProperties.SentinelConfig config) {
        String masterName = StringUtils.hasText(config.getServiceName())
                ? config.getServiceName().trim() : CollectConstant.DEFAULT_SENTINEL_MASTER_NAME;
        return masterName.length() > CollectConstant.MAX_NODE_NAME_LENGTH
                ? masterName.substring(0, CollectConstant.MAX_NODE_NAME_LENGTH) : masterName;
    }

    /** 合并 host:port 与 nodes 配置，去重 */
    private List<SentinelAddress> configuredNodes(AgentProperties.SentinelConfig config) {
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

        List<SentinelAddress> result = new ArrayList<>();
        for (String address : addresses) {
            SentinelAddress parsed = SentinelAddress.parse(address);
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
    }

    /**
     * 查询一个 Sentinel 已发现的其他哨兵
     *
     * PING 失败会抛异常，由调用方保留离线兜底；PING 成功但
     * {@code SENTINEL sentinels} 查询失败时返回空列表，当前哨兵自身仍会上报在线
     */
    private List<Map<String, String>> querySentinels(SentinelAddress address,
                                                     String masterName,
                                                     int timeout) {
        Jedis jedis = new Jedis(address.getHost(), address.getPort(), timeout);
        try {
            String password = properties.getSentinel().getPassword();
            if (StringUtils.hasText(password)) {
                jedis.auth(password);
            }
            if (!CollectConstant.PONG.equalsIgnoreCase(jedis.ping())) {
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
    private SentinelAddress resolveSelfAddress(SentinelAddress configured) {
        String serverIp = resolveServerIp(configured.getHost());
        return new SentinelAddress(serverIp, configured.getPort());
    }

    /**
     * 解析当前服务器对平台可见的 IP
     *
     * 优先使用 agent.host-ip / 自动识别的本机 IPv4；无法识别时，非 loopback 配置回退配置地址；
     * loopback 配置不再兜底，直接抛异常
     */
    private String resolveServerIp(String configuredHost) {
        try {
            String normalized = NetworkUtils.toPlatformIpv4(
                    NetworkUtils.resolveHostIp(properties.getHostIp()));
            if (StringUtils.hasText(normalized) && !NetworkUtils.isLoopbackIp(normalized)) {
                return normalized;
            }
        } catch (Exception e) {
            log.debug("解析当前服务器 IP 失败: {}", e.getMessage());
        }

        if (!NetworkUtils.isLoopbackHost(configuredHost)) {
            String normalized = NetworkUtils.toPlatformIpv4(configuredHost);
            if (StringUtils.hasText(normalized) && !NetworkUtils.isLoopbackIp(normalized)) {
                return normalized;
            }
        }
        // 去除 localhost / 127.0.0.1 兜底，无法解析真实 IP 时直接报错
        throw new IllegalStateException(
                "无法解析当前服务器有效 IP，禁止使用 localhost/127.0.0.1 兜底，配置地址: "
                        + configuredHost);
    }

    /** 返回的其他哨兵是否与自身地址相同 */
    private boolean isSameAddress(Map<String, String> item, SentinelAddress selfAddress) {
        String ip = NetworkUtils.toPlatformIpv4(item.get("ip"));
        String port = item.get("port");
        if (!StringUtils.hasText(ip) || !isValidPort(port)) {
            return false;
        }
        return selfAddress.key().equals(ip + ":" + port.trim());
    }

    private SentinelNodeDTO toNode(Map<String, String> item, String masterName) {
        String ip = NetworkUtils.toPlatformIpv4(item.get("ip"));
        String port = item.get("port");
        if (!StringUtils.hasText(ip) || !isValidPort(port)) {
            return null;
        }
        if (NetworkUtils.isLoopbackIp(ip)) {
            log.debug("忽略 loopback 哨兵节点: {}:{}", ip, port);
            return null;
        }
        String status = isOfflineFlags(item.get("flags"))
                ? CollectConstant.SENTINEL_STATUS_OFFLINE
                : CollectConstant.SENTINEL_STATUS_ONLINE;
        return buildNode(ip, port.trim(), status, masterName);
    }

    private SentinelNodeDTO offlineNode(SentinelAddress address, String masterName) {
        return buildNode(address.getHost(), String.valueOf(address.getPort()),
                CollectConstant.SENTINEL_STATUS_OFFLINE, masterName);
    }

    private SentinelNodeDTO buildNode(String ip, String port, String status, String masterName) {
        String nodeName = CollectConstant.SENTINEL_NODE_NAME_PREFIX + ip + ":" + port;
        if (nodeName.length() > CollectConstant.MAX_NODE_NAME_LENGTH) {
            nodeName = nodeName.substring(0, CollectConstant.MAX_NODE_NAME_LENGTH);
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
        return lower.contains(CollectConstant.SENTINEL_FLAG_S_DOWN)
                || lower.contains(CollectConstant.SENTINEL_FLAG_O_DOWN)
                || lower.contains(CollectConstant.SENTINEL_FLAG_DISCONNECTED);
    }

    private boolean isValidPort(String port) {
        if (!StringUtils.hasText(port)) {
            return false;
        }
        try {
            int value = Integer.parseInt(port.trim());
            return value >= CollectConstant.MIN_PORT && value <= CollectConstant.MAX_PORT;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
