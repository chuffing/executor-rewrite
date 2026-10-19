package com.icbc.nbfh.entity.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 执行器向平台上报的任务结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskResultReportDTO {

    /**
     * 任务编号。
     */
    private String taskNo;

    /**
     * 步骤编号。
     */
    private Integer stepNo;

    /**
     * 执行器编号。
     */
    private String agentId;

    /**
     * 运维操作类型。
     */
    private String operationType;

    /**
     * 执行状态，只能为SUCCESS或FAILED。
     */
    private String status;

    /**
     * OperationResultDTO序列化后的JSON字符串。
     */
    private String result;
}
