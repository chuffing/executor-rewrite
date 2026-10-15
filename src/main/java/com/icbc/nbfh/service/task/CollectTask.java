package com.icbc.nbfh.service.task;

import com.icbc.nbfh.service.CollectService;
import com.icbc.nbfh.service.RegisterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 负责启动注册和周期指标采集。
 */
@Slf4j
@Component
public class CollectTask {

    private final CollectService collectService;

    private final RegisterService registerService;

    public CollectTask(CollectService collectService,
                       RegisterService registerService) {
        this.collectService = collectService;
        this.registerService = registerService;
    }

    @PostConstruct
    public void init() {

        Thread startupThread = new Thread(() -> {
            try {
                Thread.sleep(2000);

                if (registerService.registerIfNeeded()) {
                    collectService.collectAndReport();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

            } catch (Exception e) {
                log.error("启动注册或首次指标上报失败", e);
            }
        }, "agent-startup-register");

        startupThread.start();
    }

    @Scheduled(
            fixedDelayString =
                    "${agent.schedule.collect-interval:300000}"
    )
    public void collect() {

        if (registerService.registerIfNeeded()) {
            collectService.collectAndReport();
        }
    }
}