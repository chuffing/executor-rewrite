package com.icbc.nbfh.entity.VO;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 任务接收响应。
 */
@Data
@AllArgsConstructor
public class TaskSubmitResultVO {

    /** 任务编号。 */
    private String taskNo;

    /** 任务接收结果说明。 */
    private String message;
}
