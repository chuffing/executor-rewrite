package com.icbc.nbfh.entity.dto;

import lombok.Data;

import java.util.Map;

/**
 * 采集到的指标数据，上报给平台
 */
@Data
public class MetricDataDTO {

    /** CPU 占用率（%） */
    private String cpuPer;

    /** 服务器内存占用率（%） */
    private String memServerPer;

    /** Redis 内存使用量（字节） */
    private String memUs;

    /** 命令加权平均瞬时延迟（微秒） */
    private String cmdLatency;

    /** 网络输入带宽（kbps） */
    private String ioIik;

    /** 网络输出带宽（kbps） */
    private String ioIok;

    /** 瞬时 QPS */
    private String ops;

    /** 节点身份（master/slave） */
    private String role;

    /** Redis 版本 */
    private String redisVersion;

    /** 节点运行 ID */
    private String runId;

    /** 主节点 IP */
    private String masterHost;

    /** 主节点端口 */
    private String masterPort;

    /** 已连接从节点数 */
    private String connectedSlaves;

    /** 主从复制延迟（秒） */
    private String replDelay;

    /** 采集时间戳（毫秒） */
    private Long collectTime;

    /** 状态（up/down） */
    private String status;

    /** 失败原因 */
    private String errorMsg;

    /** 扩展信息（key-value） */
    private Map<String, String> extra;
}
