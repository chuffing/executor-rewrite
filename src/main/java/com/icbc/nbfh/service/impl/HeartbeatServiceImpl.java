package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.client.PlatformClient;
import com.icbc.nbfh.common.exception.BusinessException;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.HeartbeatRequestDTO;
import com.icbc.nbfh.service.HeartbeatService;
import com.icbc.nbfh.service.RegisterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 执行器心跳服务
 *
 * 平台端心跳只接收 token（Authorization）+ hostIp + executorPort，
 * 用于刷新执行器及其绑定实例的在线状态；心跳不处理 Redis 绑定
 */
@Slf4j
@Service
public class HeartbeatServiceImpl implements HeartbeatService {

    private final PlatformClient platformClient;
    private final RegisterService registerService;
    private final AgentProperties properties;

    /** 心跳失败日志节流，避免平台不可达时每 30s 刷屏 */
    private volatile long lastFailureLogAt = 0L;

    public HeartbeatServiceImpl(PlatformClient platformClient,
                                RegisterService registerService,
                                AgentProperties properties) {
        this.platformClient = platformClient;
        this.registerService = registerService;
        this.properties = properties;
    }

    @Override
    public void sendHeartbeat() {
        if (!registerService.hasToken()) {
            return;
        }

        String hostIp = registerService.getHostIp();
        String executorPort = registerService.getExecutorPort();
        if (!StringUtils.hasText(hostIp) || !StringUtils.hasText(executorPort)) {
            log.warn("执行器 IP 或端口未就绪，跳过本轮心跳");
            return;
        }

        HeartbeatRequestDTO request = new HeartbeatRequestDTO();
        request.setHostIp(hostIp);
        request.setExecutorPort(executorPort);

        try {
            boolean accepted = platformClient.heartbeat(request,
                    properties.getPlatform().getUrl(), registerService.getToken());
            if (!accepted) {
                log.warn("平台未刷新执行器心跳，hostIp={}, executorPort={}", hostIp, executorPort);
            }
            lastFailureLogAt = 0L;
        } catch (BusinessException e) {
            if (isAuthenticationError(e.getMessage())) {
                // token 失效/平台记录被清理：清空本地凭证，注册任务会立即重新注册
                registerService.invalidateToken();
                log.warn("平台拒绝执行器心跳（{}），已清除本地 token 并等待重新注册", e.getMessage());
                return;
            }
            logHeartbeatFailure(e);
        } catch (Exception e) {
            logHeartbeatFailure(e);
        }
    }

    private void logHeartbeatFailure(Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLogAt > 60_000L) {
            lastFailureLogAt = now;
            log.error("心跳发送失败（每 60s 最多提示一次）: {}", e.getMessage());
        } else {
            log.debug("心跳发送失败: {}", e.getMessage());
        }
    }

    /** 平台端 token 失效/身份不匹配时返回的错误信息 */
    private boolean isAuthenticationError(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("token")
                || message.contains("无效的注册码")
                || message.contains("身份不匹配");
    }
}
