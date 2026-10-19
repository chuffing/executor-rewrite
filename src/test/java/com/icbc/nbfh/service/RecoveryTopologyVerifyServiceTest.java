package com.icbc.nbfh.service;

import com.icbc.nbfh.collector.RecoveryTopologyCollector;
import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.entity.DTO.RecoveryTopologySnapshotDTO;
import com.icbc.nbfh.service.impl.RecoveryTopologyVerifyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证故障恢复拓扑的重试和验收规则。
 */
class RecoveryTopologyVerifyServiceTest {

    private RecoveryTopologyCollector collector;

    private AgentProperties properties;

    /** 准备较短的测试轮询参数。 */
    @BeforeEach
    void prepare() {
        collector = mock(RecoveryTopologyCollector.class);
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
        RecoveryTopologySnapshotDTO snapshot = healthyMasterSnapshot();
        snapshot.setSentinelOtherCount(1);
        when(collector.collect()).thenReturn(snapshot);

        OperationResultDTO result = service().verifyTopology();

        assertEquals(OperationErrorCode.REPLICATION_FAILED,
                result.getErrorCode());
        assertTrue(result.getMessage().contains("其他Sentinel数量不是2"));
    }

    /** 创建一个主节点视角下完整健康的三机拓扑。 */
    private RecoveryTopologySnapshotDTO healthyMasterSnapshot() {
        RecoveryTopologySnapshotDTO snapshot =
                new RecoveryTopologySnapshotDTO();
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
    private RecoveryTopologyVerifyService service() {
        return new RecoveryTopologyVerifyServiceImpl(
                collector,
                properties
        );
    }
}
