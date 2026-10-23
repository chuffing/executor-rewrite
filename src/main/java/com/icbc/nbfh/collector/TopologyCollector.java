package com.icbc.nbfh.collector;

import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.TopologySnapshotDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集本机Redis及Sentinel看到的当前拓扑。
 */
@Component
@RequiredArgsConstructor
public class TopologyCollector {

    private final AgentProperties agentProperties;

    /**
     * 采集一次拓扑快照，不在采集器内部等待或重试。
     */
    public TopologySnapshotDTO collect() {

        // ======== 1. 获取Redis和Sentinel连接配置 ========

        AgentProperties.RedisConfig redisConfig =
                agentProperties.getRedis();

        AgentProperties.SentinelConfig sentinelConfig =
                agentProperties.getSentinel();

        String serviceName = sentinelConfig.getServiceName();

        if (!StringUtils.hasText(serviceName)) {
            throw new IllegalStateException("Sentinel监控服务名称未配置");
        }

        serviceName = serviceName.trim();

        // ======== 2. 连接本机Redis和Sentinel ========

        try (Jedis redis = createAuthenticatedJedis(
                redisConfig.getHost(),
                redisConfig.getPort(),
                redisConfig.getPassword(),
                redisConfig.getTimeout());

             Jedis sentinel = createAuthenticatedJedis(
                     sentinelConfig.getHost(),
                     sentinelConfig.getPort(),
                     sentinelConfig.getPassword(),
                     sentinelConfig.getTimeout())) {

            // ======== 3. 检查Redis和Sentinel是否正常响应 ========

            if (!"PONG".equalsIgnoreCase(redis.ping())) {
                throw new IllegalStateException("本机Redis未正常响应PING");
            }

            if (!"PONG".equalsIgnoreCase(sentinel.ping())) {
                throw new IllegalStateException("本机Sentinel未正常响应PING");
            }

            // ======== 4. 获取本机Redis的信息 ========

            // 获取Redis主从复制信息。
            Map<String, String> replicationInfo =
                    parseInfo(redis.info("replication"));

            // 获取Redis服务器信息，包括run_id。
            Map<String, String> serverInfo =
                    parseInfo(redis.info("server"));

            // ======== 5. 获取Sentinel的信息 ========

            // 查询Sentinel管理的全部主节点信息。
            List<Map<String, String>> masters =
                    sentinel.sentinelMasters();

            // 找到当前配置的主从组。
            Map<String, String> sentinelMaster = null;

            for (Map<String, String> master : masters) {

                if (serviceName.equals(master.get("name"))) {
                    sentinelMaster = master;
                    break;
                }
            }

            if (sentinelMaster == null) {
                throw new IllegalStateException(
                        "Sentinel未找到指定的主从组"
                );
            }

            // 查询Sentinel当前识别的主节点IP和端口。
            List<String> masterAddress =
                    sentinel.sentinelGetMasterAddrByName(serviceName);

            if (masterAddress == null || masterAddress.size() != 2) {
                throw new IllegalStateException(
                        "Sentinel未返回有效主节点地址"
                );
            }

            // 查询Sentinel当前识别的所有从节点。
            List<Map<String, String>> sentinelReplicas =
                    sentinel.sentinelSlaves(serviceName);

            // ======== 6. 组装拓扑快照 ========

            return buildSnapshot(
                    replicationInfo,
                    serverInfo,
                    sentinelMaster,
                    masterAddress,
                    sentinelReplicas
            );
        }
    }


    /**
     * 将Redis和Sentinel原始信息转换为结构化拓扑快照。
     */
    private TopologySnapshotDTO buildSnapshot(
            Map<String, String> replicationInfo,
            Map<String, String> serverInfo,
            Map<String, String> sentinelMaster,
            List<String> masterAddress,
            List<Map<String, String>> sentinelReplicas) {

        // ======== 1. 创建拓扑快照对象 ========

        TopologySnapshotDTO snapshot =
                new TopologySnapshotDTO();

        // ======== 2. 填充本机Redis信息 ========

        // 本机Redis角色：master或slave。
        snapshot.setRedisRole(
                replicationInfo.get("role")
        );

        // 本机Redis运行ID。
        snapshot.setRedisRunId(
                serverInfo.get("run_id")
        );

        // 如果本机是master，记录连接的从节点数量。
        snapshot.setConnectedReplicas(
                parseInteger(
                        replicationInfo.get("connected_slaves"),
                        0
                )
        );

        // 如果本机是slave，记录其master地址。
        snapshot.setRedisMasterHost(
                replicationInfo.get("master_host")
        );

        snapshot.setRedisMasterPort(
                parseInteger(
                        replicationInfo.get("master_port"),
                        -1
                )
        );

        // 本机从节点与主节点之间的连接状态。
        snapshot.setMasterLinkStatus(
                replicationInfo.get("master_link_status")
        );

        // 本机是否正在进行主从同步。
        snapshot.setMasterSyncInProgress(
                parseInteger(
                        replicationInfo.get("master_sync_in_progress"),
                        -1
                )
        );

        // ======== 3. 填充Sentinel主节点信息 ========

        // Sentinel当前识别的master IP。
        snapshot.setSentinelMasterHost(
                masterAddress.get(0)
        );

        // Sentinel当前识别的master端口。
        snapshot.setSentinelMasterPort(
                parseInteger(
                        masterAddress.get(1),
                        -1
                )
        );

        // Sentinel记录的master运行ID。
        snapshot.setSentinelMasterRunId(
                sentinelMaster.get("runid")
        );

        // Sentinel记录的master状态标记。
        snapshot.setSentinelMasterFlags(
                sentinelMaster.get("flags")
        );

        // ======== 4. 填充Sentinel从节点信息 ========

        // Sentinel当前识别的从节点数量。
        snapshot.setSentinelReplicaCount(
                sentinelReplicas.size()
        );

        // Sentinel当前识别的其他Sentinel数量。
        snapshot.setSentinelOtherCount(
                parseInteger(
                        sentinelMaster.get("num-other-sentinels"),
                        0
                )
        );

        // ======== 5. 判断Sentinel从节点是否健康 ========

        boolean replicasHealthy = true;

        for (Map<String, String> replica : sentinelReplicas) {

            // 获取当前从节点的状态标记。
            String flags = replica.get("flags");

            if (StringUtils.hasText(flags)) {

                String normalized = flags.toLowerCase();

                // 出现主观下线、客观下线或断连标记，
                // 就认为当前从节点不健康。
                if (normalized.contains("s_down")
                        || normalized.contains("o_down")
                        || normalized.contains("disconnected")) {

                    replicasHealthy = false;
                    break;
                }
            }
        }

        snapshot.setSentinelReplicasHealthy(replicasHealthy);

        // ======== 6. 判断本机Redis是否被Sentinel识别 ========

        String runId = snapshot.getRedisRunId();

        boolean localRedisRegistered = false;

        if (StringUtils.hasText(runId)) {

            // 先判断本机Redis是不是Sentinel记录的master。
            if (runId.equals(sentinelMaster.get("runid"))) {

                localRedisRegistered = true;

            } else {

                // 如果不是master，再检查是否在从节点列表中。
                for (Map<String, String> replica : sentinelReplicas) {

                    if (runId.equals(replica.get("runid"))) {
                        localRedisRegistered = true;
                        break;
                    }
                }
            }
        }

        snapshot.setLocalRedisRegistered(localRedisRegistered);

        // ======== 7. 返回拓扑快照 ========

        return snapshot;
    }


    /**
     * 解析Redis INFO命令返回的文本。
     *
     * 例如：
     * role:master
     * connected_slaves:2
     *
     * 转换为Map：
     * role -> master
     * connected_slaves -> 2
     */
    private Map<String, String> parseInfo(String info) {

        Map<String, String> values = new HashMap<>();

        if (info == null) {
            return values;
        }

        // 按换行符拆分INFO文本。
        String[] lines = info.split("\\r?\\n");

        for (String line : lines) {

            // 跳过空行和以#开头的注释行。
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }

            // 查找当前行中第一个冒号的位置。
            int separator = line.indexOf(':');

            if (separator > 0) {

                // 冒号前面作为Key。
                // 冒号后面作为Value。
                values.put(
                        line.substring(0, separator),
                        line.substring(separator + 1)
                );
            }
        }

        return values;
    }


    /**
     * 将Redis返回的字符串数字转换为int。
     *
     * 如果字段为空或无法转换，则返回默认值。
     */
    private int parseInteger(
            String value,
            int defaultValue) {

        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }

        try {

            return Integer.parseInt(value.trim());

        } catch (NumberFormatException e) {

            return defaultValue;
        }
    }


    /**
     * 创建Redis或Sentinel连接，并完成密码认证。
     */
    private Jedis createAuthenticatedJedis(
            String host,
            int port,
            String password,
            int timeout) {

        // 根据IP、端口和超时时间创建Jedis客户端。
        Jedis jedis = new Jedis(host, port, timeout);

        try {

            // 使用配置的密码进行认证。
            jedis.auth(password);

            // 认证成功，返回Jedis客户端。
            return jedis;

        } catch (RuntimeException e) {

            // 认证失败时关闭连接，避免资源泄漏。
            jedis.close();

            // 将原来的异常继续抛出。
            throw e;
        }
    }
}
