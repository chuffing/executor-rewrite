package com.icbc.nbfh.service;

import com.icbc.nbfh.collector.TopologyCollector;
import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.entity.DTO.TopologySnapshotDTO;
import com.icbc.nbfh.service.impl.TopologyVerifyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证Redis和Sentinel拓扑的重试和验收规则。
 */
class TopologyVerifyServiceTest {

    private TopologyCollector collector;

    private AgentProperties properties;

    /** 准备较短的测试轮询参数。 */
    @BeforeEach
    void prepare() {
        collector = mock(TopologyCollector.class);
        properties = new AgentProperties();
        properties.getOperation().setTopologyCheckTimeoutSeconds(1);
        properties.getOperation().setTopologyCheckIntervalMillis(100);
    }

    /** 第一次采集失败后继续等待，拓扑恢复正常即可验收成功。 */
    @Test
    void shouldRetryAndAcceptHealthyMasterTopology() {
        when(collector.collect())
                .thenThrow(new IllegalStateException("Sentinel尚未完成发现"))
                .thenReturn(healthyMasterSnapshot());

        OperationResultDTO result = service().verifyTopology();

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("Sentinel总数=3"));
    }

    /** Sentinel数量始终不足时，超时后返回复制拓扑失败。 */
    @Test
    void shouldRejectIncompleteSentinelTopologyAfterTimeout() {
        TopologySnapshotDTO snapshot = healthyMasterSnapshot();
        snapshot.setSentinelOtherCount(1);
        when(collector.collect()).thenReturn(snapshot);

        OperationResultDTO result = service().verifyTopology();

        assertEquals(OperationErrorCode.REPLICATION_FAILED,
                result.getErrorCode());
        assertTrue(result.getMessage().contains("其他Sentinel数量不是2"));
    }

    /** 实际角色与平台选择不一致时不能通过首次初始化验收。 */
    @Test
    void shouldRejectTopologyDifferentFromPlatformSelection() {
        when(collector.collect()).thenReturn(healthyMasterSnapshot());
        Map<String, String> params = new HashMap<>();
        params.put("expectedMasterHostIp", "10.0.0.11");
        params.put("expectedRole", "REPLICA");

        OperationResultDTO result = service().verifyTopology(params);

        assertEquals(OperationErrorCode.REPLICATION_FAILED,
                result.getErrorCode());
        assertTrue(result.getMessage().contains("实际角色不是平台选择的从节点"));
    }

    /** 新服务器拓扑验收成功后持久化redisReady。 */
    @Test
    void shouldMarkRedisReadyAfterSuccessfulTopologyVerification()
            throws Exception {
        Path temporaryDirectory = Files.createTempDirectory(
                Paths.get("target"),
                "redis-ready-"
        );
        Path configFile = temporaryDirectory.resolve("application.yml");
        Files.write(
                configFile,
                ("agent:\n"
                        + "  redisReady: false\n"
                        + "  agent-name: target-agent\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        properties.setConfigFile(configFile.toString());
        properties.setRedisReady(false);
        when(collector.collect()).thenReturn(healthyMasterSnapshot());

        OperationResultDTO result = service().verifyTopology();

        assertTrue(result.isSuccess());
        assertTrue(properties.isRedisReady());
        String updatedConfig = new String(
                Files.readAllBytes(configFile),
                StandardCharsets.UTF_8
        );
        assertTrue(updatedConfig.contains("redisReady: true"));
    }

    /** 创建一个主节点视角下完整健康的三机拓扑。 */
    private TopologySnapshotDTO healthyMasterSnapshot() {
        TopologySnapshotDTO snapshot =
                new TopologySnapshotDTO();
        snapshot.setRedisRole("master");
        snapshot.setRedisRunId("master-run-id");
        snapshot.setConnectedReplicas(2);
        snapshot.setSentinelMasterHost("10.0.0.11");
        snapshot.setSentinelMasterPort(6379);
        snapshot.setSentinelMasterRunId("master-run-id");
        snapshot.setSentinelMasterFlags("master");
        snapshot.setSentinelReplicaCount(2);
        snapshot.setSentinelOtherCount(2);
        snapshot.setSentinelReplicasHealthy(true);
        snapshot.setLocalRedisRegistered(true);
        return snapshot;
    }

    /** 创建待测服务。 */
    private TopologyVerifyService service() {
        return new TopologyVerifyServiceImpl(
                collector,
                properties
        );
    }
}
