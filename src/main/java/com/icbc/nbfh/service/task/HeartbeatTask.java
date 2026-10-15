package com.icbc.nbfh.service.task;

import com.icbc.nbfh.service.HeartbeatService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期发送执行器心跳。
 */
@Component
public class HeartbeatTask {

    private final HeartbeatService heartbeatService;

    public HeartbeatTask(HeartbeatService heartbeatService) {
        this.heartbeatService = heartbeatService;
    }

    @Scheduled(fixedDelayString = "${agent.heartbeat-interval:30000}")
    public void heartbeat() {
        heartbeatService.sendHeartbeat();
    }
}
