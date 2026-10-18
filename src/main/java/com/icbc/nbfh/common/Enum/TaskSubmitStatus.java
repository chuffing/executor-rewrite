package com.icbc.nbfh.common.Enum;

/**
 * 执行器接收任务后的状态。
 */
public enum TaskSubmitStatus {

    /** 任务已接收。 */
    ACCEPTED,

    /** 执行器繁忙，任务未接收。 */
    BUSY
}
