package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.collector.RecoveryTopologyCollector;
import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.constant.RecoveryTopologyConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.entity.DTO.RecoveryTopologySnapshotDTO;
import com.icbc.nbfh.service.RecoveryTopologyVerifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * 故障恢复拓扑验收服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryTopologyVerifyServiceImpl
        implements RecoveryTopologyVerifyService {

    private final RecoveryTopologyCollector recoveryTopologyCollector;

    private final AgentProperties agentProperties;

    /**
     * 重复采集拓扑，直到验收通过或达到超时时间。
     */
    @Override
    public OperationResultDTO verifyTopology() {

        // ======== 1. 初始化超时参数 ========

        // 记录本次拓扑验收的开始时间。
        long startTime = System.currentTimeMillis();

        // 从配置中读取验收超时时间，并将秒转换成毫秒。
        long timeoutMillis = TimeUnit.SECONDS.toMillis(
                agentProperties.getOperation()
                        .getTopologyCheckTimeoutSeconds()
        );

        // 从配置中读取每次拓扑检查的间隔时间。
        long intervalMillis = agentProperties.getOperation()
                .getTopologyCheckIntervalMillis();

        // 计算验收截止时间。
        long deadline = startTime + timeoutMillis;

        // 保存最后一次验收失败的原因。
        String lastFailureReason = "尚未采集到拓扑";

        // ======== 2. 循环采集并校验拓扑 ========

        // 当前时间没有超过截止时间，就继续检查。
        while (System.currentTimeMillis() < deadline) {

            try {

                // 采集本机Redis和Sentinel看到的拓扑信息。
                RecoveryTopologySnapshotDTO snapshot =
                        recoveryTopologyCollector.collect();

                // 校验拓扑，返回null表示通过。
                // 返回字符串表示未通过的原因。
                lastFailureReason = validateSnapshot(snapshot);

                // ======== 3. 拓扑验收成功 ========

                if (lastFailureReason == null) {

                    // 组装成功提示语。
                    String message = "故障恢复拓扑验收通过，本机角色="
                            + snapshot.getRedisRole()
                            + "，主节点="
                            + snapshot.getSentinelMasterHost()
                            + ":"
                            + snapshot.getSentinelMasterPort()
                            + "，从节点数量="
                            + snapshot.getSentinelReplicaCount()
                            + "，Sentinel总数="
                            + (snapshot.getSentinelOtherCount() + 1);

                    // 返回成功结果。
                    return OperationResultDTO.success(
                            message,
                            startTime,
                            System.currentTimeMillis()
                    );
                }

            } catch (RuntimeException e) {

                // Redis或Sentinel暂时无法连接时，
                // 记录原因，后面继续重试采集。
                lastFailureReason = e.getMessage();

                log.warn(
                        "故障恢复拓扑采集暂未通过：{}",
                        lastFailureReason
                );
            }

            // ======== 4. 等待下一次采集 ========

            try {

                // 计算距离截止时间还剩多少毫秒。
                long remaining =
                        deadline - System.currentTimeMillis();

                // 如果还有剩余时间，就等待。
                if (remaining > 0) {

                    // 不让等待时间超过剩余时间。
                    Thread.sleep(
                            Math.min(intervalMillis, remaining)
                    );
                }

            } catch (InterruptedException e) {

                // 恢复当前线程的中断标记。
                Thread.currentThread().interrupt();

                // 返回验收被中断的失败结果。
                return OperationResultDTO.failure(
                        OperationErrorCode.UNKNOWN_ERROR,
                        "故障恢复拓扑验收被中断",
                        startTime,
                        System.currentTimeMillis()
                );
            }
        }

        // ======== 5. 超时后返回失败 ========

        return OperationResultDTO.failure(
                OperationErrorCode.REPLICATION_FAILED,
                "故障恢复拓扑未在规定时间内通过验收："
                        + lastFailureReason,
                startTime,
                System.currentTimeMillis()
        );
    }


    /**
     * 校验单台执行器看到的Redis和Sentinel拓扑。
     *
     * 返回null：验收通过。
     * 返回字符串：验收未通过的原因。
     */
    private String validateSnapshot(
            RecoveryTopologySnapshotDTO snapshot) {

        // ======== 1. 校验Sentinel识别的从节点数量 ========

        // 三台Redis应当是一主两从。
        if (snapshot.getSentinelReplicaCount()
                != RecoveryTopologyConstants.EXPECTED_REPLICA_COUNT) {

            return "Sentinel识别出的从节点数量不是2";
        }

        // ======== 2. 校验Sentinel数量 ========

        // 每台Sentinel应当识别另外两台Sentinel。
        if (snapshot.getSentinelOtherCount()
                != RecoveryTopologyConstants.EXPECTED_OTHER_SENTINEL_COUNT) {

            return "Sentinel识别出的其他Sentinel数量不是2";
        }

        // ======== 3. 校验Sentinel记录的主节点状态 ========

        String flags = snapshot.getSentinelMasterFlags();

        if (StringUtils.hasText(flags)) {

            String normalized = flags.toLowerCase();

            // 主节点存在故障或断连标记，验收不通过。
            if (normalized.contains("s_down")
                    || normalized.contains("o_down")
                    || normalized.contains("disconnected")) {

                return "Sentinel记录的主节点存在故障标记";
            }
        }

        // ======== 4. 校验Sentinel记录的从节点状态 ========

        // 从节点存在故障标记，验收不通过。
        if (!snapshot.isSentinelReplicasHealthy()) {

            return "Sentinel记录的从节点存在故障标记";
        }

        // ======== 5. 检查本机Redis是否在Sentinel拓扑中 ========

        if (!snapshot.isLocalRedisRegistered()) {

            return "本机Redis未出现在Sentinel拓扑中";
        }

        // ======== 6. 根据本机Redis角色进行校验 ========

        String role = snapshot.getRedisRole();

        // ---------- 情况一：本机是Master ----------

        if ("master".equalsIgnoreCase(role)) {

            // Master必须连接两个从节点。
            if (snapshot.getConnectedReplicas()
                    != RecoveryTopologyConstants.EXPECTED_REPLICA_COUNT) {

                return "Redis主节点已连接的从节点数量不是2";
            }

            // Redis主节点的runId应当与Sentinel识别的主节点一致。
            String redisRunId = snapshot.getRedisRunId();

            if (!StringUtils.hasText(redisRunId)
                    || !redisRunId.equals(
                    snapshot.getSentinelMasterRunId())) {

                return "Redis主节点与Sentinel识别的主节点不一致";
            }

            // Master校验通过。
            return null;
        }

        // ---------- 情况二：本机是Slave/Replica ----------

        if ("slave".equalsIgnoreCase(role)
                || "replica".equalsIgnoreCase(role)) {

            // 从节点与主节点的连接状态必须为up。
            if (!"up".equalsIgnoreCase(
                    snapshot.getMasterLinkStatus())) {

                return "Redis从节点与主节点连接未恢复";
            }

            // master_sync_in_progress必须为0。
            // 0表示当前没有正在进行全量同步。
            if (snapshot.getMasterSyncInProgress() != 0) {

                return "Redis从节点仍在进行全量同步";
            }

            // ======== 7. 比较Redis与Sentinel记录的Master地址 ========

            String redisMasterHost =
                    snapshot.getRedisMasterHost();

            int redisMasterPort =
                    snapshot.getRedisMasterPort();

            String sentinelMasterHost =
                    snapshot.getSentinelMasterHost();

            int sentinelMasterPort =
                    snapshot.getSentinelMasterPort();

            // 检查Master IP和端口是否一致。
            if (!StringUtils.hasText(redisMasterHost)
                    || !redisMasterHost.equalsIgnoreCase(
                    sentinelMasterHost)
                    || redisMasterPort != sentinelMasterPort) {

                return "Redis从节点和Sentinel记录的主节点不一致";
            }

            // Replica校验通过。
            return null;
        }

        // ======== 8. 无法识别Redis角色 ========

        // 既不是master，也不是slave/replica。
        return "本机Redis角色无法识别";
    }
}