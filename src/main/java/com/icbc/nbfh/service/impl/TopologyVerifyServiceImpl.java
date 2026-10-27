package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.collector.TopologyCollector;
import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.constant.TopologyConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.entity.DTO.TopologySnapshotDTO;
import com.icbc.nbfh.service.TopologyVerifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * Redis和Sentinel拓扑验收服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopologyVerifyServiceImpl
        implements TopologyVerifyService {

    private final TopologyCollector topologyCollector;

    private final AgentProperties agentProperties;

    /**
     * 重复采集拓扑，直到当前运行拓扑通过验收。
     */
    @Override
    public OperationResultDTO verifyTopology() {

        long startTime = System.currentTimeMillis();
        long timeoutMillis = TimeUnit.SECONDS.toMillis(
                agentProperties.getOperation().getTopologyCheckTimeoutSeconds()
        );
        long intervalMillis = agentProperties.getOperation()
                .getTopologyCheckIntervalMillis();
        long deadline = startTime + timeoutMillis;
        String lastFailureReason = "尚未采集到拓扑";

        // 1. 重复采集拓扑，直到校验通过或达到超时时间。
        while (System.currentTimeMillis() < deadline) {

            try {
                TopologySnapshotDTO snapshot =
                        topologyCollector.collect();

                // 返回null表示本次拓扑校验通过。
                lastFailureReason = validateTopology(snapshot);

                if (lastFailureReason == null) {
                    String message = "拓扑验收通过，本机Redis角色="
                            + snapshot.getRedisRole()
                            + "，主节点="
                            + snapshot.getSentinelMasterHost()
                            + ":"
                            + snapshot.getSentinelMasterPort()
                            + "，从节点数量="
                            + snapshot.getSentinelReplicaCount()
                            + "，Sentinel总数="
                            + (snapshot.getSentinelOtherCount() + 1);

                    return OperationResultDTO.success(
                            message,
                            startTime,
                            System.currentTimeMillis()
                    );
                }

            } catch (RuntimeException e) {
                lastFailureReason = e.getMessage();
                log.warn("拓扑采集暂未通过：{}", lastFailureReason);
            }

            // 2. 本次校验未通过，等待后重新采集。
            try {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining > 0) {
                    Thread.sleep(Math.min(intervalMillis, remaining));
                }

            } catch (InterruptedException e) {
                return OperationResultDTO.failure(
                        OperationErrorCode.UNKNOWN_ERROR,
                        "拓扑验收被中断",
                        startTime,
                        System.currentTimeMillis()
                );
            }
        }

        // 3. 超时后仍未通过，返回最后一次失败原因。
        return OperationResultDTO.failure(
                OperationErrorCode.REPLICATION_FAILED,
                "Redis和Sentinel拓扑未在规定时间内通过验收："
                        + lastFailureReason,
                startTime,
                System.currentTimeMillis()
        );
    }

    /**
     * 按照业务顺序校验本机看到的Redis和Sentinel拓扑。
     *
     * 返回null：验收通过。
     * 返回字符串：验收未通过的原因。
     */
    private String validateTopology(TopologySnapshotDTO snapshot) {

        // 1. Sentinel必须识别出两台从节点和另外两台Sentinel。
        if (snapshot.getSentinelReplicaCount()
                != TopologyConstants.EXPECTED_REPLICA_COUNT) {
            return "Sentinel识别出的从节点数量不是2";
        }

        if (snapshot.getSentinelOtherCount()
                != TopologyConstants.EXPECTED_OTHER_SENTINEL_COUNT) {
            return "Sentinel识别出的其他Sentinel数量不是2";
        }

        // 2. Sentinel记录的主从节点不能存在故障状态。
        String flags = snapshot.getSentinelMasterFlags();
        if (StringUtils.hasText(flags)) {
            String normalized = flags.toLowerCase();
            if (normalized.contains("s_down")
                    || normalized.contains("o_down")
                    || normalized.contains("disconnected")) {
                return "Sentinel记录的主节点存在故障标记";
            }
        }

        if (!snapshot.isSentinelReplicasHealthy()) {
            return "Sentinel记录的从节点存在故障标记";
        }

        // 3. 本机Redis必须存在于Sentinel拓扑中。
        if (!snapshot.isLocalRedisRegistered()) {
            return "本机Redis未出现在Sentinel拓扑中";
        }

        // 4. 根据本机Redis角色检查主从关系。
        String role = snapshot.getRedisRole();
        if ("master".equalsIgnoreCase(role)) {
            if (snapshot.getConnectedReplicas()
                    != TopologyConstants.EXPECTED_REPLICA_COUNT) {
                return "Redis主节点已连接的从节点数量不是2";
            }

            String redisRunId = snapshot.getRedisRunId();
            if (!StringUtils.hasText(redisRunId)
                    || !redisRunId.equals(snapshot.getSentinelMasterRunId())) {
                return "Redis主节点与Sentinel识别的主节点不一致";
            }

        } else if ("replica".equalsIgnoreCase(role)) {
            if (!"up".equalsIgnoreCase(snapshot.getMasterLinkStatus())) {
                return "Redis从节点与主节点连接未恢复";
            }

            if (snapshot.getMasterSyncInProgress() != 0) {
                return "Redis从节点仍在进行全量同步";
            }

            String redisMasterHost = snapshot.getRedisMasterHost();
            if (!StringUtils.hasText(redisMasterHost)
                    || !redisMasterHost.equalsIgnoreCase(
                    snapshot.getSentinelMasterHost())
                    || snapshot.getRedisMasterPort()
                    != snapshot.getSentinelMasterPort()) {
                return "Redis从节点和Sentinel记录的主节点不一致";
            }

        } else {
            return "本机Redis角色无法识别";
        }

        return null;
    }
}
