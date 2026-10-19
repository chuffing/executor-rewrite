package com.icbc.nbfh.common.constant;

/**
 * 故障恢复拓扑验收常量。
 */
public final class RecoveryTopologyConstants {

    /** 故障恢复后应存在的从节点数量。 */
    public static final int EXPECTED_REPLICA_COUNT = 2;

    /** 每个Sentinel应识别出的其他Sentinel数量。 */
    public static final int EXPECTED_OTHER_SENTINEL_COUNT = 2;

    /** 工具类不允许实例化。 */
    private RecoveryTopologyConstants() {
    }
}
