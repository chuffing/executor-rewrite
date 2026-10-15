package com.icbc.nbfh.service.impl;

import cn.hutool.core.net.NetUtil;
import cn.hutool.system.HostInfo;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.HeartbeatRequestDTO;
import com.icbc.nbfh.service.HeartbeatService;
import com.icbc.nbfh.service.RegisterService;
import com.icbc.nbfh.service.client.PlatformClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class HeartbeatServiceImpl implements HeartbeatService {

    private final PlatformClient platformClient;
    private final RegisterService registerService;
    private final AgentProperties properties;

    public HeartbeatServiceImpl(PlatformClient platformClient,
                                RegisterService registerService,
                                AgentProperties properties) {
        this.platformClient = platformClient;
        this.registerService = registerService;
        this.properties = properties;
    }

    @Override
    public void sendHeartbeat() {
        if (!registerService.isRegistered()) {
            return;
        }

        String token = registerService.getToken();

        try {
            HeartbeatRequestDTO request = new HeartbeatRequestDTO();
            request.setToken(token);
            request.setAgentId(registerService.getAgentId());
            request.setStatus("UP");
            request.setHostname(new HostInfo().getName());
            request.setIp(NetUtil.getLocalhostStr());
            request.setLastCollectTime(
                    System.currentTimeMillis() - properties.getSchedule().getCollectInterval());

            platformClient.heartbeat(request, properties.getPlatform().getUrl(), token);
        } catch (Exception e) {
            log.error("心跳发送失败", e);
        }
    }
}
