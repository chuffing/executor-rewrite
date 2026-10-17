package com.icbc.nbfh.entity.DTO;

import lombok.Data;

import java.util.Map;

/**
 * 平台下发给执行器的运维任务。
 */
@Data
public class PlatformTaskDTO {

    /**
     * 任务编号。
     */
    private String taskNo;

    /**
     * 步骤序号。
     */
    private Integer stepNo;

    /**
     * 运维操作类型。
     */
    private String OperationType;

    /**
     * 当前操作需要的参数。
     */
    private Map<String, String> params;
}
