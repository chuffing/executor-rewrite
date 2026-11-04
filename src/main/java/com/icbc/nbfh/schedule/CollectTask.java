package com.icbc.nbfh.schedule;

import com.icbc.nbfh.service.CollectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 负责启动后首次采集和周期指标采集
 */
@Slf4j
@Component
public class CollectTask {

    private final CollectService collectService;

    public CollectTask(CollectService collectService) {
        this.collectService = collectService;
    }

    /** 容器启动完成后立即执行一次采集，缩短首条指标延迟 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            collectService.collectAndReport();
        } catch (Exception e) {
            log.error("启动后首次指标采集失败", e);
        }
    }

    @Scheduled(fixedDelayString = "${agent.schedule.collect-interval:300000}")
    public void collect() {
        collectService.collectAndReport();
    }
}
