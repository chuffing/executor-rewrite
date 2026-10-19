package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;

/**
 * 任务结果本地保存及上报服务。
 */
public interface TaskResultReportService {

    /**
     * 先保存任务结果，再立即尝试上报平台。
     *
     * @param report 任务执行结果
     */
    void saveAndReport(TaskResultReportDTO report);

    /**
     * 重试尚未成功上报的本地任务结果。
     */
    void retryPendingResults();
}
