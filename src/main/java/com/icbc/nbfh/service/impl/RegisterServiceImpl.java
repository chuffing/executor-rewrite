package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.client.PlatformClient;
import com.icbc.nbfh.collector.RedisConnectionManager;
import com.icbc.nbfh.collector.RedisInfoCollector;
import com.icbc.nbfh.common.constant.CollectConstant;
import com.icbc.nbfh.common.constant.CommonConstant;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.RegisterRequestDTO;
import com.icbc.nbfh.entity.DTO.RegisterResultDTO;
import com.icbc.nbfh.service.RegisterService;
import com.icbc.nbfh.store.AgentState;
import com.icbc.nbfh.store.AgentStateStore;
import com.icbc.nbfh.util.NetworkUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 注册服务实现
 * 平台端以 hostIp + executorPort 作为执行器唯一标识，注册分两个阶段：
 *
 *   执行器启动时先探测本地 Redis，把结果作为 redisReady 上报；
 *   Redis 未就绪时只注册执行器（平台不创建/绑定实例），先让平台能看到执行器；
 *   Redis 就绪后（注册任务每 30s 补齐），再次注册并携带{redisReady=true,redisInfo}，
 *   平台新增redis_instance记录，返回instanceId，
 *   执行器持久化 bound/instanceId，之后允许采集上报
 */
@Slf4j
@Service
public class RegisterServiceImpl implements RegisterService {

    private final AgentProperties properties;

    private final PlatformClient platformClient;

    private final RedisInfoCollector redisInfoCollector;

    private final RedisConnectionManager redisConnectionManager;

    private final AgentStateStore stateStore;

    private final int agentPort;

    private volatile String token;

    private volatile String hostIp;

    private volatile String executorPort;

    private volatile String instanceId;

    private volatile boolean bound;

    private volatile long lastRegisterAttemptAt = 0L;

    private volatile long lastFailureLogAt = 0L;

    private volatile boolean stateLoaded;

    public RegisterServiceImpl(AgentProperties properties,
                               PlatformClient platformClient,
                               RedisInfoCollector redisInfoCollector,
                               RedisConnectionManager redisConnectionManager,
                               AgentStateStore stateStore,
                               @Value("${server.port:9101}") int agentPort) {
        this.properties = properties;
        this.platformClient = platformClient;
        this.redisInfoCollector = redisInfoCollector;
        this.redisConnectionManager = redisConnectionManager;
        this.stateStore = stateStore;
        this.agentPort = agentPort;
    }

    @Override
    public synchronized boolean registerIfNeeded() {
        // 1. 校验平台地址和 token 文件
        String platformUrl = properties.getPlatform().getUrl();
        String tokenFilePath = properties.getPlatform().getTokenFile();

        if (!StringUtils.hasText(platformUrl)) {
            log.error("注册失败：agent.platform.url 未配置");
            return false;
        }
        if (!StringUtils.hasText(tokenFilePath)) {
            log.error("注册失败：agent.platform.token-file 未配置");
            return false;
        }
        if (!StringUtils.hasText(properties.getPlatform().getRegisterCode())) {
            log.error("注册失败：agent.platform.register-code 未配置");
            return false;
        }

        // 读取本地注册凭证
        loadStateIfNeeded();
        if (hasToken() && isBound()) {
            return true;
        }

        // 已注册但未绑定 / 未注册：按重试间隔节流，避免每 30s 心跳都冲击平台
        long now = System.currentTimeMillis();
        long retryInterval = Math.max(1000L, properties.getRegisterRetryInterval());
        if (now - lastRegisterAttemptAt < retryInterval) {
            return hasToken();
        }
        lastRegisterAttemptAt = now;

        try {
            String resolvedHostIp = NetworkUtils.resolveHostIp(properties.getHostIp());
            String resolvedPort = String.valueOf(agentPort);

            // 注册地址变化，丢弃旧 token重新注册
            if (StringUtils.hasText(hostIp)
                    && (!hostIp.equals(resolvedHostIp)
                    || (StringUtils.hasText(executorPort) && !executorPort.equals(resolvedPort)))) {
                log.warn("执行器注册地址变化（{}:{} -> {}:{}），丢弃旧 token 重新注册",
                        hostIp, executorPort, resolvedHostIp, resolvedPort);
                clearLocalCredential();
            }

            // 注册前自检 Redis，未就绪时不带 redisInfo，只注册执行器
            boolean redisReady = redisConnectionManager.isRedisReady();
            Map<String, String> redisInfo = null;
            if (redisReady) {
                try {
                    redisInfo = buildRedisInfo(resolvedHostIp);
                } catch (Exception e) {
                    // 探测通过但读取身份信息失败（如刚好被重启）：本轮按未就绪注册，下一轮重试
                    redisReady = false;
                    log.warn("Redis 就绪但读取身份信息失败，本轮按未就绪注册: {}", e.getMessage());
                }
            } else {
                log.info("Redis 尚未就绪，本轮只注册执行器，等待 Redis 恢复后二次注册绑定");
            }

            // 调平台注册接口
            RegisterRequestDTO request = new RegisterRequestDTO();
            request.setRegisterCode(properties.getPlatform().getRegisterCode().trim());
            request.setHostIp(resolvedHostIp);
            request.setExecutorPort(resolvedPort);
            request.setRedisReady(redisReady);
            request.setRedisInfo(redisInfo);

            RegisterResultDTO result = platformClient.register(request, platformUrl.trim());
            if (result == null || !StringUtils.hasText(result.getExecutorToken())) {
                log.error("平台注册响应缺少 executorToken");
                throw new IllegalStateException("平台注册响应缺少 executorToken");
            }

            // 平台返回内容校验通过后再更新本地注册状态
            String oldToken = token;
            token = result.getExecutorToken().trim();
            instanceId = StringUtils.hasText(result.getInstanceId())
                    ? result.getInstanceId().trim() : "";
            bound = Boolean.TRUE.equals(result.getBound()) && StringUtils.hasText(instanceId);
            hostIp = resolvedHostIp;
            executorPort = resolvedPort;

            saveState();
            lastFailureLogAt = 0L;

            if (bound) {
                log.info("执行器注册成功并完成实例绑定: hostIp={}, executorPort={}, instanceId={}, "
                                + "redisReady={}, token={}",
                        resolvedHostIp, resolvedPort, instanceId, redisReady, maskToken(token));
            } else {
                log.warn("执行器注册成功但 Redis 未就绪/未绑定: hostIp={}, executorPort={}, redisReady={}, "
                                + "将在 Redis 就绪后自动二次注册绑定",
                        resolvedHostIp, resolvedPort, redisReady);
            }
            if (StringUtils.hasText(oldToken) && !oldToken.equals(token)) {
                log.info("平台重新下发了执行器 token，已更新本地凭证");
            }
            return true;

        } catch (Exception e) {
            logRegisterFailure(e);
            return hasToken();
        }
    }

    /**
     * 首次使用时从本地凭证文件恢复状态
     *
     * 凭证中的执行器地址与当前地址不一致时丢弃旧 token，避免平台记录与执行器错位</p>
     */
    private void loadStateIfNeeded() {
        if (stateLoaded) {
            return;
        }
        stateLoaded = true;

        AgentState state = stateStore.load();
        if (state == null || !state.hasToken()) {
            return;
        }

        String currentHostIp = safeResolveHostIp();
        String currentPort = String.valueOf(agentPort);
        if (addressChanged(state.getHostIp(), state.getExecutorPort(), currentHostIp, currentPort)) {
            log.warn("本地 token 对应的执行器地址已变化（{}:{} -> {}:{}），丢弃旧 token 并重新注册",
                    state.getHostIp(), state.getExecutorPort(), currentHostIp, currentPort);
            clearLocalCredential();
            return;
        }

        token = state.getToken();
        hostIp = state.getHostIp();
        executorPort = StringUtils.hasText(state.getExecutorPort())
                ? state.getExecutorPort() : currentPort;
        instanceId = state.getInstanceId();
        bound = state.isBound();
    }

    private boolean addressChanged(String savedHostIp, String savedPort,
                                   String currentHostIp, String currentPort) {
        if (!StringUtils.hasText(savedHostIp) || !StringUtils.hasText(currentHostIp)) {
            return false;
        }
        if (!savedHostIp.equals(currentHostIp)) {
            return true;
        }
        return StringUtils.hasText(savedPort) && !savedPort.equals(currentPort);
    }

    /**
     * 组装注册用 Redis 信息（仅在 redisReady=true 时调用）
     */
    private Map<String, String> buildRedisInfo(String agentHostIp) {
        Map<String, String> redisInfo = new LinkedHashMap<>();
        Map<String, String> collected = redisInfoCollector.collectRegistrationInfo();
        if (collected != null) {
            redisInfo.putAll(collected);
        }
        redisInfo.put(CollectConstant.INFO_HOST, redisHostForPlatform(agentHostIp));
        redisInfo.putIfAbsent(CollectConstant.INFO_TCP_PORT,
                String.valueOf(properties.getRedis().getPort()));
        redisInfo.putIfAbsent(CollectConstant.INFO_ROLE, CollectConstant.ROLE_MASTER);
        redisInfo.putIfAbsent(CollectConstant.INFO_VERSION, CollectConstant.DEFAULT_REDIS_VERSION);
        redisInfo.putIfAbsent(CollectConstant.INFO_RUN_ID, CollectConstant.EMPTY_RUN_ID);
        return redisInfo;
    }

    /**
     * 平台端 redis_instance.host_ip 为 varchar(20)，这里把本机/主机名转换为 IPv4
     *
     * Redis 与应用同机（127.0.0.1/localhost）时，用执行器对平台可见的地址代替</p>
     */
    private String redisHostForPlatform(String agentHostIp) {
        String redisHost = properties.getRedis().getHost();
        if (NetworkUtils.isLoopbackHost(redisHost)) {
            return StringUtils.hasText(agentHostIp) ? agentHostIp : CommonConstant.DEFAULT_LOCALHOST_IP;
        }
        String ipv4 = NetworkUtils.toPlatformIpv4(redisHost.trim());
        return ipv4 != null ? ipv4 : redisHost.trim();
    }

    private void saveState() {
        stateStore.save(new AgentState(token, hostIp, executorPort, instanceId, bound));
    }

    private String safeResolveHostIp() {
        try {
            return NetworkUtils.resolveHostIp(properties.getHostIp());
        } catch (Exception e) {
            log.debug("解析本机 IPv4 失败: {}", e.getMessage());
            return null;
        }
    }

    private void logRegisterFailure(Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLogAt > 60_000L) {
            lastFailureLogAt = now;
            if (hasToken() && !isBound()) {
                log.warn("执行器已注册但尚未绑定实例（Redis 未就绪或注册信息不完整），"
                        + "每 60s 最多提示一次；将在 Redis 就绪后自动二次注册");
            } else {
                log.error("执行器注册失败（每 60s 最多提示一次）: {}", e.getMessage());
            }
            log.debug("注册失败详情", e);
        } else {
            log.debug("执行器注册失败: {}", e.getMessage());
        }
    }

    private String maskToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 8) + "****";
    }

    /** 清空内存和本地文件中的注册凭证 */
    private void clearLocalCredential() {
        token = null;
        instanceId = null;
        bound = false;
        hostIp = null;
        executorPort = null;
        lastRegisterAttemptAt = 0L;
        stateStore.delete();
    }

    @Override
    public synchronized void invalidateToken() {
        token = null;
        instanceId = null;
        bound = false;
        // 下次调用立即重新注册，不等待重试间隔
        lastRegisterAttemptAt = 0L;
        stateStore.delete();
        log.warn("已清除本地执行器 token 与绑定状态，等待重新注册");
    }

    @Override
    public boolean isRegistered() {
        return hasToken();
    }

    @Override
    public boolean hasToken() {
        return StringUtils.hasText(token);
    }

    @Override
    public boolean isBound() {
        return bound && StringUtils.hasText(instanceId);
    }

    @Override
    public String getInstanceId() {
        return StringUtils.hasText(instanceId) ? instanceId : null;
    }

    @Override
    public String getToken() {
        return token;
    }

    @Override
    public String getAgentId() {
        if (StringUtils.hasText(hostIp) && StringUtils.hasText(executorPort)) {
            return hostIp + ":" + executorPort;
        }
        return null;
    }

    @Override
    public String getHostIp() {
        if (StringUtils.hasText(hostIp)) {
            return hostIp;
        }
        hostIp = safeResolveHostIp();
        return hostIp;
    }

    @Override
    public String getExecutorPort() {
        if (!StringUtils.hasText(executorPort)) {
            executorPort = String.valueOf(agentPort);
        }
        return executorPort;
    }
}
