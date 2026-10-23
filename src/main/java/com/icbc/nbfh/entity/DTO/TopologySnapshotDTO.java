package com.icbc.nbfh.entity.DTO;

import lombok.Data;

/**
 * 单次采集到的Redis和Sentinel拓扑快照。
 */
@Data
public class TopologySnapshotDTO {

    /** 本机Redis角色。 */
    private String redisRole;

    /** 本机Redis运行ID。 */
    private String redisRunId;

    /** 本机为主节点时已连接的从节点数量。 */
    private int connectedReplicas;

    /** 本机为从节点时记录的主节点地址。 */
    private String redisMasterHost;

    /** 本机为从节点时记录的主节点端口。 */
    private int redisMasterPort;

    /** 本机为从节点时与主节点的连接状态。 */
    private String masterLinkStatus;

    /** 本机为从节点时是否正在进行全量同步。 */
    private int masterSyncInProgress;

    /** Sentinel识别出的主节点地址。 */
    private String sentinelMasterHost;

    /** Sentinel识别出的主节点端口。 */
    private int sentinelMasterPort;

    /** Sentinel识别出的主节点运行ID。 */
    private String sentinelMasterRunId;

    /** Sentinel主节点状态标记。 */
    private String sentinelMasterFlags;

    /** Sentinel识别出的从节点数量。 */
    private int sentinelReplicaCount;

    /** 当前Sentinel识别出的其他Sentinel数量。 */
    private int sentinelOtherCount;

    /** Sentinel记录的两个从节点是否都没有故障标记。 */
    private boolean sentinelReplicasHealthy;

    /** Sentinel拓扑中是否包含本机Redis运行ID。 */
    private boolean localRedisRegistered;
}
