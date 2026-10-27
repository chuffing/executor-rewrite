package com.icbc.nbfh.schedule;

import com.icbc.nbfh.service.RegisterService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期执行注册/二次注册
 *
 * Redis 未就绪时每 30s 只注册执行器；Redis 恢复后下一轮自动携带 redisInfo
 * 二次注册并完成实例绑定，不需要人工重启执行器
 */
@Component
@RequiredArgsConstructor
public class RegisterTask {

    private final RegisterService registerService;

    @Scheduled(
            initialDelayString = "${agent.register-initial-delay:5000}",
            fixedDelayString = "${agent.schedule.register-interval:30000}"
    )
    public void register() {
        registerService.registerIfNeeded();
    }
}
