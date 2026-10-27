package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.client.PlatformClient;
import com.icbc.nbfh.collector.RedisInfoCollector;
import com.icbc.nbfh.common.exception.BusinessCode;
import com.icbc.nbfh.common.exception.BusinessException;
import com.icbc.nbfh.collector.SentinelInfoCollector;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.MetricDataDTO;
import com.icbc.nbfh.entity.DTO.MetricReportDTO;
import com.icbc.nbfh.entity.DTO.SentinelNodeDTO;
import com.icbc.nbfh.service.CollectService;
import com.icbc.nbfh.service.RegisterService;
import com.icbc.nbfh.store.LocalMetricStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * 采集上报服务：默认 5 分钟一次
 *
 * 一次采集/上报同时包含：Redis 指标 + 执行器 ping 平台延迟 + Sentinel 节点
 * 流程：先重传本地积压数据 → 采集 → 本地留存 → 一次请求上报平台 → 成功删除本地文件
 * 上报失败保留本地文件，下个周期继续重传，避免平台/网络短时不可用导致数据丢失
 */
@Slf4j
@Service
public class CollectServiceImpl implements CollectService {

    /** 每轮最多重传的历史数据条数，避免平台恢复后单轮阻塞过久 */
    private static final int MAX_RETRY_BATCH = 50;

    private final RedisInfoCollector collector;
    private final SentinelInfoCollector sentinelInfoCollector;
    private final PlatformClient platformClient;
    private final RegisterService registerService;
    private final LocalMetricStore localMetricStore;
    private final AgentProperties properties;

    /** 未绑定实例/未注册时避免每 5 分钟刷屏 */
    private volatile long lastUnboundLogAt = 0L;

    public CollectServiceImpl(RedisInfoCollector collector,
                              SentinelInfoCollector sentinelInfoCollector,
                              PlatformClient platformClient,
                              RegisterService registerService,
                              LocalMetricStore localMetricStore,
                              AgentProperties properties) {
        this.collector = collector;
        this.sentinelInfoCollector = sentinelInfoCollector;
        this.platformClient = platformClient;
        this.registerService = registerService;
        this.localMetricStore = localMetricStore;
        this.properties = properties;
    }

    @Override
    public void collectAndReport() {
        // 未注册时先尝试注册；注册失败则跳过本轮，避免无效采集与堆积
        if (!registerService.hasToken() && !registerService.registerIfNeeded()) {
            logUnregistered();
            return;
        }

        // 已注册但 Redis 未就绪/未绑定：平台尚未创建实例，此时上报会被拒绝
        //    每轮都再尝试一次二次注册，Redis 恢复后无需等到下一个 5 分钟采集周期
        if (!registerService.isBound()) {
            registerService.registerIfNeeded();
            if (!registerService.isBound()) {
                logUnbound();
                return;
            }
        }

        // 重传之前上报失败、缓存在本地的数据
        retryPending();
        if (!registerService.hasToken()) {
            log.warn("执行器 token 不可用，本轮采集暂不上报");
            return;
        }

        // 本轮采集（Redis 指标 + 身份/主从信息）
        MetricDataDTO metric;
        try {
            metric = collector.collect();
        } catch (Exception e) {
            log.error("Redis 指标采集异常", e);
            return;
        }

        if (!"up".equals(metric.getStatus())) {
            // 采集失败时不上报空指标：平台端收到任何一条指标都会把实例置为正常
            log.warn("Redis 采集失败，跳过本轮上报: {}", metric.getErrorMsg());
            return;
        }

        // 采集执行器 ping 平台的 HTTP 往返延迟
        metric.setPingLatency(platformClient.measurePingLatency(
                properties.getPlatform().getUrl()));

        // 采集 Sentinel 节点（与指标、ping 延迟一次上报；失败不影响 Redis 指标上报）
        List<SentinelNodeDTO> sentinels = collectSentinels();

        // 先本地留存，防止上报过程中丢失（历史重传不含 Sentinel，下一次实时采集会重新上报）
        localMetricStore.save(metric);

        String token = registerService.getToken();
        if (!StringUtils.hasText(token)) {
            log.warn("执行器 token 不可用，本轮数据已本地留存，待重新注册后补传");
            return;
        }

        // 一次请求上报 Redis 指标 + ping 平台延迟 + Sentinel 节点
        try {
            platformClient.reportMetric(
                    MetricReportDTO.from(metric, token, "0", sentinels),
                    properties.getPlatform().getUrl(),
                    token);
            localMetricStore.remove(metric);
            log.info("采集上报成功: instanceId={}, role={}, qps={}, latency={}μs, "
                            + "replDelay={}s, pingLatency={}ms, sentinels={}",
                    registerService.getInstanceId(), metric.getRole(), metric.getOps(),
                    metric.getLatency(), metric.getReplDelay(), metric.getPingLatency(),
                    sentinels.size());
        } catch (BusinessException e) {
            if (isAuthenticationError(e.getMessage())) {
                // token 失效或平台侧实例绑定丢失：清除本地凭证，注册任务会重新注册/绑定
                registerService.invalidateToken();
            }
            log.error("本轮数据上报失败，已本地留存，待下轮重传（当前待重传 {} 条）: {}",
                    localMetricStore.pendingCount(), e.getMessage());
        } catch (Exception e) {
            log.error("本轮数据上报失败，已本地留存，待下轮重传（当前待重传 {} 条）: {}",
                    localMetricStore.pendingCount(), e.getMessage());
        }
    }

    @Override
    public MetricReportDTO collectForManualCheck() {
        MetricDataDTO metric = collector.collect();
        if (!"up".equals(metric.getStatus())) {
            String error = StringUtils.hasText(metric.getErrorMsg())
                    ? metric.getErrorMsg() : "Redis 采集失败";
            throw new BusinessException(BusinessCode.OPERATION_ERROR.getCode(), error);
        }
        metric.setPingLatency(platformClient.measurePingLatency(
                properties.getPlatform().getUrl()));
        List<SentinelNodeDTO> sentinels = collectSentinels();
        log.info("平台手动采集完成: role={}, qps={}, latency={}μs, replDelay={}s, "
                        + "pingLatency={}ms, sentinels={}",
                metric.getRole(), metric.getOps(), metric.getLatency(),
                metric.getReplDelay(), metric.getPingLatency(), sentinels.size());
        // 手动采集由平台回填 token 并写库，执行器不重复上报
        return MetricReportDTO.from(metric, null, "1", sentinels);
    }

    private List<SentinelNodeDTO> collectSentinels() {
        try {
            List<SentinelNodeDTO> sentinels = sentinelInfoCollector.collect();
            return sentinels == null ? Collections.emptyList() : sentinels;
        } catch (Exception e) {
            log.warn("Sentinel 节点采集失败，本轮仅上报 Redis 指标与 ping 延迟: {}",
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 未注册时按 60s 节流提示 */
    private void logUnregistered() {
        long now = System.currentTimeMillis();
        if (now - lastUnboundLogAt > 60_000L) {
            lastUnboundLogAt = now;
            log.warn("执行器尚未注册，跳过本轮采集；请检查 agent.platform.url / register-code / 平台连通性");
        }
    }

    /** 未绑定实例时按 60s 节流提示，明确告知等待 Redis 就绪后的二次注册 */
    private void logUnbound() {
        long now = System.currentTimeMillis();
        if (now - lastUnboundLogAt > 60_000L) {
            lastUnboundLogAt = now;
            log.warn("执行器尚未绑定 Redis 实例（redisReady=false 或平台绑定失败），本轮跳过采集；"
                    + "Redis 就绪后注册任务会自动二次注册并补齐绑定");
        }
    }

    /**
     * 按时间顺序重传本地缓存的历史采集数据
     * 网络/平台异常时停止本轮重传，剩余数据继续保留
     */
    private void retryPending() {
        List<MetricDataDTO> pending = localMetricStore.loadPending(MAX_RETRY_BATCH);
        if (pending.isEmpty()) {
            return;
        }

        log.info("发现 {} 条待重传历史采集数据，开始重传", pending.size());
        for (MetricDataDTO metric : pending) {
            if (!registerService.hasToken()) {
                log.warn("token 不可用，暂停历史数据重传");
                return;
            }
            String token = registerService.getToken();
            try {
                platformClient.reportMetric(
                        MetricReportDTO.from(metric, token, "0", null),
                        properties.getPlatform().getUrl(),
                        token);
                localMetricStore.remove(metric);
                log.info("历史数据重传成功: collectTime={}", metric.getCollectTime());
            } catch (BusinessException e) {
                if (isAuthenticationError(e.getMessage())) {
                    registerService.invalidateToken();
                }
                log.warn("历史数据重传中断，剩余 {} 条继续保留: {}",
                        localMetricStore.pendingCount(), e.getMessage());
                return;
            } catch (Exception e) {
                log.warn("历史数据重传中断，剩余 {} 条继续保留: {}",
                        localMetricStore.pendingCount(), e.getMessage());
                return;
            }
        }
    }

    private boolean isAuthenticationError(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("token")
                || message.contains("身份不匹配")
                || message.contains("无效的注册码")
                || message.contains("尚未完成 Redis 安装与实例绑定");
    }
}
