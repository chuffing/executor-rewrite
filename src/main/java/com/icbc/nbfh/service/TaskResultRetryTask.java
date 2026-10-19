package com.icbc.nbfh.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期重试尚未成功上报的任务结果。
 */
@Component
@RequiredArgsConstructor
public class TaskResultRetryTask {

    private final TaskResultReportService taskResultReportService;

    /**
     * 按配置的间隔扫描执行器本地结果文件。
     */
    @Scheduled(
            fixedDelayString =
                    "${agent.task-result.retry-interval-millis:10000}"
    )
    public void retryPendingResults() {
        taskResultReportService.retryPendingResults();
    }
}
