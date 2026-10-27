package com.icbc.nbfh.entity.DTO;

import com.icbc.nbfh.common.constant.CommonConstant;
import lombok.Data;

import java.util.List;

/**
 * 指标上报报文
 * 对应平台端 POST /metric/report 直接接收，token 放请求体
 * 一次请求同时上报 Redis 指标、执行器 ping 平台延迟和 Sentinel 节点
 */
@Data
public class MetricReportDTO {

    /** 执行器认证 token（平台注册时下发） */
    private String token;

    /** CPU 占用率（%） */
    private String cpuPer;

    /** 服务器内存占用率（%） */
    private String memServerPer;

    /** Redis 内存使用量（GB） */
    private String memUs;

    /** Redis 命令加权平均延迟（微秒） */
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

    /** Redis 运行 ID */
    private String runId;

    /** 主节点 host */
    private String masterHost;

    /** 主节点 port */
    private String masterPort;

    /** 主从复制延迟（秒） */
    private String replDelay;

    /** 采集类型：0=自动，1=手动 */
    private String collectType;

    /** 执行器 ping 平台的 HTTP 往返延迟（毫秒） */
    private String pingLatency;

    /** 随本次指标一起上报的 Sentinel 节点列表 */
    private List<SentinelNodeDTO> sentinels;

    /** 采集失败原因，成功时为空串 */
    private String errorMsg;

    /**
     * 由本地采集快照构建上报对象
     *
     * @param metric      采集快照
     * @param token       执行器 token；手动采集接口可为 null，由平台回填
     * @param collectType 0=自动上报，1=手动采集
     * @param sentinels   本次采集到的 Sentinel 节点，可为 null/空
     */
    public static MetricReportDTO from(MetricDataDTO metric,
                                       String token,
                                       String collectType,
                                       List<SentinelNodeDTO> sentinels) {
        MetricReportDTO request = new MetricReportDTO();
        request.setToken(token);
        request.setCpuPer(metric.getCpuPer());
        request.setMemServerPer(metric.getMemServerPer());
        request.setMemUs(metric.getMemUs());
        request.setLatency(metric.getLatency());
        request.setIoIik(metric.getIoIik());
        request.setIoIok(metric.getIoIok());
        request.setOps(metric.getOps());
        request.setRole(metric.getRole());
        request.setRedisVersion(metric.getRedisVersion());
        request.setRunId(metric.getRunId());
        // 平台端 masterHost/masterPort 为必填；采集器已回填，这里再兜底一次
        request.setMasterHost(hasText(metric.getMasterHost()) ? metric.getMasterHost() : CommonConstant.DEFAULT_LOCALHOST_IP);
        request.setMasterPort(hasText(metric.getMasterPort()) ? metric.getMasterPort() : "6379");
        request.setReplDelay(hasText(metric.getReplDelay()) ? metric.getReplDelay() : "0");
        request.setPingLatency(hasText(metric.getPingLatency()) ? metric.getPingLatency() : "0.00");
        request.setCollectType(collectType);
        // 成功场景 errorMsg 显式空串，满足平台 notNull 校验，也便于排查
        request.setErrorMsg(metric.getErrorMsg() == null ? "" : metric.getErrorMsg());
        request.setSentinels(sentinels);
        return request;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
