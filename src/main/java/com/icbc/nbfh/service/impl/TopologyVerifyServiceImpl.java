package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.collector.TopologyCollector;
import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.constant.TopologyConstants;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.entity.DTO.TopologySnapshotDTO;
import com.icbc.nbfh.service.TopologyVerifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
     * 重复采集拓扑，直到验收通过或达到超时时间。
     */
    @Override
    public OperationResultDTO verifyTopology() {
        return verifyTopology(Collections.<String, String>emptyMap());
    }

    /**
     * 重复采集拓扑，并按任务中的可选期望值核对主节点和本机角色。
     */
    @Override
    public OperationResultDTO verifyTopology(Map<String, String> params) {

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
                TopologySnapshotDTO snapshot =
                        topologyCollector.collect();

                // 校验拓扑，返回null表示通过。
                // 返回字符串表示未通过的原因。
                lastFailureReason = validateSnapshot(snapshot);

                // 首次初始化会传入用户选择的主节点和本机角色，
                // 其他运维任务不传参数时保持原有验收规则。
                if (lastFailureReason == null) {
                    lastFailureReason = validateExpectedTopology(
                            snapshot,
                            params
                    );
                }

                // ======== 3. 拓扑验收成功 ========

                if (lastFailureReason == null) {

                    // 拓扑验收通过已经证明本机Redis和Sentinel可用。
                    // 只有新服务器的redisReady为false，需要持久化一次。
                    if (!agentProperties.isRedisReady()) {
                        markRedisReady();
                    }

                    // 组装成功提示语。
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
                        "拓扑采集暂未通过：{}",
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
                        "拓扑验收被中断",
                        startTime,
                        System.currentTimeMillis()
                );
            }
        }

        // ======== 5. 超时后返回失败 ========

        return OperationResultDTO.failure(
                OperationErrorCode.REPLICATION_FAILED,
                "Redis和Sentinel拓扑未在规定时间内通过验收："
                        + lastFailureReason,
                startTime,
                System.currentTimeMillis()
        );
    }

    /**
     * 原子替换外置application.yml中的redisReady字段。
     */
    private void markRedisReady() {
        Path configPath = Paths.get(agentProperties.getConfigFile())
                .toAbsolutePath()
                .normalize();
        Path parent = configPath.getParent();
        if (parent == null || !Files.isRegularFile(configPath)) {
            throw new IllegalStateException(
                    "执行器外置配置文件不存在：" + configPath
            );
        }

        Path temporaryFile = null;
        try {
            List<String> originalLines = Files.readAllLines(
                    configPath,
                    StandardCharsets.UTF_8
            );
            List<String> updatedLines = new ArrayList<>(originalLines);
            int matchedLineCount = 0;
            for (int index = 0; index < originalLines.size(); index++) {
                String line = originalLines.get(index);
                String trimmedLine = line.trim();
                if (!trimmedLine.startsWith("redisReady:")) {
                    continue;
                }

                int indentationLength = line.indexOf('r');
                String indentation = indentationLength <= 0
                        ? "" : line.substring(0, indentationLength);
                updatedLines.set(index, indentation + "redisReady: true");
                matchedLineCount++;
            }
            if (matchedLineCount != 1) {
                throw new IllegalStateException(
                        "执行器配置中必须且只能存在一个redisReady字段"
                );
            }

            temporaryFile = Files.createTempFile(
                    parent,
                    ".application-",
                    ".yml"
            );
            Files.write(
                    temporaryFile,
                    updatedLines,
                    StandardCharsets.UTF_8
            );
            try {
                Files.move(
                        temporaryFile,
                        configPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporaryFile,
                        configPath,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
            agentProperties.setRedisReady(true);

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "更新执行器redisReady配置失败："
                            + exception.getMessage(),
                    exception
            );
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException exception) {
                    log.warn("清理redisReady临时配置文件失败：{}",
                            temporaryFile);
                }
            }
        }
    }

    /**
     * 校验实际拓扑是否与平台本次选择一致。
     */
    private String validateExpectedTopology(
            TopologySnapshotDTO snapshot,
            Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }

        String expectedMasterHostIp = params.get(
                TaskParamConstants.EXPECTED_MASTER_HOST_IP
        );
        if (StringUtils.hasText(expectedMasterHostIp)
                && !expectedMasterHostIp.equals(
                snapshot.getSentinelMasterHost())) {
            return "Sentinel识别的主节点与平台选择的主节点不一致";
        }

        String expectedRole = params.get(TaskParamConstants.EXPECTED_ROLE);
        String actualRole = snapshot.getRedisRole();
        if ("MASTER".equalsIgnoreCase(expectedRole)
                && !"master".equalsIgnoreCase(actualRole)) {
            return "本机Redis实际角色不是平台选择的主节点";
        }
        if ("REPLICA".equalsIgnoreCase(expectedRole)
                && !"replica".equalsIgnoreCase(actualRole)
                && !"slave".equalsIgnoreCase(actualRole)) {
            return "本机Redis实际角色不是平台选择的从节点";
        }
        return null;
    }


    /**
     * 校验单台执行器看到的Redis和Sentinel拓扑。
     *
     * 返回null：验收通过。
     * 返回字符串：验收未通过的原因。
     */
    private String validateSnapshot(
            TopologySnapshotDTO snapshot) {

        // ======== 1. 校验Sentinel识别的从节点数量 ========

        // 三台Redis应当是一主两从。
        if (snapshot.getSentinelReplicaCount()
                != TopologyConstants.EXPECTED_REPLICA_COUNT) {

            return "Sentinel识别出的从节点数量不是2";
        }

        // ======== 2. 校验Sentinel数量 ========

        // 每台Sentinel应当识别另外两台Sentinel。
        if (snapshot.getSentinelOtherCount()
                != TopologyConstants.EXPECTED_OTHER_SENTINEL_COUNT) {

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
                    != TopologyConstants.EXPECTED_REPLICA_COUNT) {

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
