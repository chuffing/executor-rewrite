package com.icbc.nbfh.entity.DTO;

import lombok.Data;

/**
 * 执行器本地保存的待上报任务结果。
 */
@Data
public class TaskResultReportRecordDTO {

    /** 需要上报给平台的任务结果。 */
    private TaskResultReportDTO report;

    /** 已经尝试上报的次数。 */
    private Integer attemptCount;

    /** 最近一次上报时间，毫秒时间戳。 */
    private Long lastAttemptTime;

    /** 下一次允许重试的时间，毫秒时间戳。 */
    private Long nextAttemptTime;

    /** 最近一次上报失败原因。 */
    private String lastError;
}
