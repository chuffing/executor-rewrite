package com.icbc.nbfh.schedule;

import com.icbc.nbfh.service.HeartbeatService;
import com.icbc.nbfh.service.RegisterService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期发送执行器心跳。
 */
@Component
public class HeartbeatTask {

    private final HeartbeatService heartbeatService;

    private final RegisterService registerService;

    public HeartbeatTask(HeartbeatService heartbeatService,
                         RegisterService registerService) {
        this.heartbeatService = heartbeatService;
        this.registerService = registerService;
    }

    @Scheduled(
            fixedDelayString =
                    "${agent.schedule.heartbeat-interval:30000}"
    )
    public void heartbeat() {

        if (registerService.registerIfNeeded()) {
            heartbeatService.sendHeartbeat();
        }
    }
}
