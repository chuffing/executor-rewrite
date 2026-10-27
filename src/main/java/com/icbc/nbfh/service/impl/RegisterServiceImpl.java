package com.icbc.nbfh.service.impl;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.icbc.nbfh.client.PlatformClient;
import com.icbc.nbfh.collector.RedisInfoCollector;
import com.icbc.nbfh.common.constant.CommonConstant;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.RegisterRequestDTO;
import com.icbc.nbfh.entity.DTO.RegisterResultDTO;
import com.icbc.nbfh.service.RegisterService;
import com.icbc.nbfh.util.NetworkUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 注册服务实现
 *
 * 平台端以 hostIp + executorPort 作为执行器唯一标识，注册分两个阶段：
 *   执行器启动时先探测本地 Redis，把结果作为 {@code redisReady} 上报：
 *       Redis 未就绪时只注册执行器（平台不创建/绑定实例），先让平台能看到执行器；
 *   本地 Redis 就绪后（注册任务每 30s 补齐），再次注册并携带
 *       {@code redisReady=true} + {@code redisInfo}，平台创建 redis_instance 并写回
 *       instanceId；执行器持久化 bound/instanceId，之后才允许采集上报
 */
@Slf4j
@Service
public class RegisterServiceImpl implements RegisterService {

    private final AgentProperties properties;

    private final PlatformClient platformClient;

    private final RedisInfoCollector redisInfoCollector;

    private final int agentPort;

    private volatile String token;

    private volatile String hostIp;

    private volatile String executorPort;

    private volatile String instanceId;

    private volatile boolean bound;

    private volatile long lastRegisterAttemptAt = 0L;

    private volatile long lastFailureLogAt = 0L;

    private boolean stateLoaded;

    public RegisterServiceImpl(AgentProperties properties,
                               PlatformClient platformClient,
                               RedisInfoCollector redisInfoCollector,
                               @Value("${server.port:9101}") int agentPort) {
        this.properties = properties;
        this.platformClient = platformClient;
        this.redisInfoCollector = redisInfoCollector;
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
            boolean redisReady = redisInfoCollector.isRedisReady();
            Map<String, String> redisInfo = null;
            if (redisReady) {
                try {
                    redisInfo = buildRedisInfo(resolvedHostIp);
                } catch (Exception e) {
                    // 探测通过但读取身份信息失败（如刚好被重启）：本轮按未就绪注册，下一轮重试
                    redisReady = false;
                    log.warn("Redis 就绪但读取身份信息失败，本轮按未就绪注册: {}", e.getMessage());
                }
            } else if (properties.isRedisReady()) {
                log.warn("agent.redisReady=true 但 Redis 连接异常，本轮按未就绪注册，等待 Redis 恢复后二次注册");
            } else {
                log.info("Redis 尚未就绪，本轮只注册执行器，等待 Redis 恢复后二次注册绑定");
            }

            if (redisReady && !properties.isRedisReady()) {
                log.info("agent.redisReady=false 但 Redis 实际可连接，按就绪注册并绑定实例");
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
     * 读取本地 token 文件
     *
     * 新格式为 JSON：{token, hostIp, executorPort, instanceId, bound}；
     * 兼容旧格式：agentId|token 或纯 token，以及无 instanceId 的旧 JSON
     */
    private void loadStateIfNeeded() {
        if (stateLoaded) {
            return;
        }
        stateLoaded = true;

        String tokenFilePath = properties.getPlatform().getTokenFile();
        if (!StringUtils.hasText(tokenFilePath)) {
            return;
        }
        File tokenFile = new File(tokenFilePath.trim());
        if (!tokenFile.isFile()) {
            return;
        }
        try {
            String content = FileUtil.readString(tokenFile, StandardCharsets.UTF_8).trim();
            if (!StringUtils.hasText(content)) {
                return;
            }
            if (content.startsWith("{")) {
                loadJsonState(content, tokenFile);
            } else {
                // 兼容历史格式：agentId|token 或纯 token，绑定信息缺失，下次注册时补齐
                String legacyToken = content.contains("|")
                        ? content.substring(content.lastIndexOf('|') + 1).trim()
                        : content;
                if (StringUtils.hasText(legacyToken)) {
                    token = legacyToken;
                    bound = false;
                    instanceId = "";
                    log.info("从 {} 读取到旧格式执行器 token，将重新注册以补齐绑定关系",
                            tokenFile.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            log.warn("读取本地 token 文件失败，将重新注册: {}", e.getMessage());
            clearLocalCredential();
        }
    }

    private void loadJsonState(String content, File tokenFile) {
        JSONObject state = JSON.parseObject(content);
        String savedToken = state.getString("token");
        if (!StringUtils.hasText(savedToken)) {
            savedToken = state.getString("executorToken");
        }
        if (!StringUtils.hasText(savedToken)) {
            return;
        }

        String savedHostIp = state.getString("hostIp");
        String savedPort = state.getString("executorPort");
        String currentHostIp = safeResolveHostIp();
        String currentPort = String.valueOf(agentPort);

        boolean addressChanged = StringUtils.hasText(savedHostIp)
                && StringUtils.hasText(currentHostIp)
                && (!savedHostIp.equals(currentHostIp)
                || (StringUtils.hasText(savedPort) && !savedPort.equals(currentPort)));
        if (addressChanged) {
            log.warn("本地 token 对应的执行器地址已变化（{}:{} -> {}:{}），丢弃旧 token 并重新注册",
                    savedHostIp, savedPort, currentHostIp, currentPort);
            clearLocalCredential();
            return;
        }

        token = savedToken.trim();
        hostIp = savedHostIp;
        executorPort = StringUtils.hasText(savedPort) ? savedPort : currentPort;
        instanceId = state.getString("instanceId");
        Boolean savedBound = state.getBoolean("bound");
        // 兼容旧版本文件：无 bound 时按 instanceId 是否为空判断
        bound = savedBound != null ? savedBound
                : StringUtils.hasText(instanceId);
        if (!StringUtils.hasText(instanceId)) {
            bound = false;
        }
        log.info("从 {} 读取到执行器状态（hostIp={}, executorPort={}, bound={}, instanceId={}）",
                tokenFile.getAbsolutePath(), hostIp, executorPort, bound, instanceId);
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
        redisInfo.put("host", redisHostForPlatform(agentHostIp));
        if (!redisInfo.containsKey("tcp_port")) {
            redisInfo.put("tcp_port", String.valueOf(properties.getRedis().getPort()));
        }
        if (!redisInfo.containsKey("role")) {
            redisInfo.put("role", "MASTER");
        }
        if (!redisInfo.containsKey("version")) {
            redisInfo.put("version", "0.0.0");
        }
        if (!redisInfo.containsKey("run_id")) {
            redisInfo.put("run_id", "0000000000000000000000000000000000000000");
        }
        return redisInfo;
    }

    /**
     * 平台端 redis_instance.host_ip 为 varchar(20)，这里把本机/主机名转换为 IPv4
     * Redis 与应用同机（127.0.0.1/localhost）时，用执行器对平台可见的地址代替
     */
    private String redisHostForPlatform(String agentHostIp) {
        String redisHost = properties.getRedis().getHost();
        if (!StringUtils.hasText(redisHost)) {
            return StringUtils.hasText(agentHostIp) ? agentHostIp : CommonConstant.DEFAULT_LOCALHOST_IP;
        }
        redisHost = redisHost.trim();
        if (CommonConstant.DEFAULT_LOCALHOST_IP.equals(redisHost)
                || CommonConstant.ANY_IP.equals(redisHost)
                || CommonConstant.LOCALHOST.equalsIgnoreCase(redisHost)
                || CommonConstant.LOCALHOST_IPV6.equals(redisHost)) {
            return StringUtils.hasText(agentHostIp) ? agentHostIp : CommonConstant.DEFAULT_LOCALHOST_IP;
        }
        try {
            InetAddress address = InetAddress.getByName(redisHost);
            if (address instanceof Inet4Address) {
                String ip = address.getHostAddress();
                return ip.length() <= 20 ? ip : redisHost;
            }
        } catch (Exception e) {
            log.debug("解析 Redis host {} 失败，按原值上报: {}", redisHost, e.getMessage());
        }
        return redisHost;
    }

    private void saveState() {
        File tokenFile = new File(properties.getPlatform().getTokenFile().trim());
        try {
            FileUtil.mkParentDirs(tokenFile);
            JSONObject state = new JSONObject(true);
            state.put("token", token);
            state.put("hostIp", hostIp);
            state.put("executorPort", executorPort);
            state.put("instanceId", instanceId);
            state.put("bound", bound);
            state.put("registeredAt", System.currentTimeMillis());
            FileUtil.writeString(state.toJSONString(), tokenFile, StandardCharsets.UTF_8);
            restrictFilePermission(tokenFile);
        } catch (Exception e) {
            log.warn("持久化 token 失败（重启后会重新注册）: {}", e.getMessage());
        }
    }

    /** token 属于敏感信息，Linux 下尽量设置为仅属主可读写 */
    private void restrictFilePermission(File file) {
        try {
            Files.setPosixFilePermissions(file.toPath(),
                    PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // Windows 等非 POSIX 文件系统忽略
        }
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
        deleteTokenFile();
    }

    private void deleteTokenFile() {
        String path = properties.getPlatform().getTokenFile();
        if (!StringUtils.hasText(path)) {
            return;
        }
        try {
            File tokenFile = new File(path.trim());
            if (tokenFile.exists() && !tokenFile.delete()) {
                log.warn("删除本地 token 文件失败: {}", tokenFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("删除本地 token 文件异常: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void invalidateToken() {
        token = null;
        instanceId = null;
        bound = false;
        // 下次调用立即重新注册，不等待重试间隔
        lastRegisterAttemptAt = 0L;
        deleteTokenFile();
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
