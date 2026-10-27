package com.icbc.nbfh.entity.DTO;

import lombok.Data;

import java.util.Map;

/**
 * Redis 单次采集快照
 * 只用于执行器本地留存和日志，不参与平台上报
 */
@Data
public class MetricDataDTO {

    /** CPU 占用率（%），Redis 进程 CPU 占整机逻辑核数比例 */
    private String cpuPer;

    /** 服务器内存占用率（%） */
    private String memServerPer;

    /** Redis 内存使用量（GB，used_memory 字节 / 1024^3） */
    private String memUs;

    /** Redis 命令加权平均延迟（毫秒，对应平台 latency/avgLatency） */
    private String latency;

    /** 网络输入带宽（kbps） */
    private String ioIik;

    /** 网络输出带宽（kbps） */
    private String ioIok;

    /** 瞬时 QPS（次/秒） */
    private String ops;

    /** 节点角色：MASTER / REPLICA */
    private String role;

    /** Redis 版本 */
    private String redisVersion;

    /** Redis 运行 ID（40 位小写十六进制） */
    private String runId;

    /** 主节点 IP；主节点回填自身地址，从节点为上游 master */
    private String masterHost;

    /** 主节点端口；主节点回填自身端口，从节点为上游 master */
    private String masterPort;

    /** 已连接从节点数（仅本地留存/排查使用） */
    private String connectedSlaves;

    /** 主从复制延迟（秒），主节点为 0 */
    private String replDelay;

    /** 执行器 ping 平台的 HTTP 往返延迟（毫秒） */
    private String pingLatency;

    /** 采集时间戳（毫秒），仅本地留存/重传使用 */
    private Long collectTime;

    /** 采集状态：up / down，仅本地使用 */
    private String status;

    /** 采集失败原因 */
    private String errorMsg;

    /** 扩展信息（预留） */
    private Map<String, String> extra;
}
