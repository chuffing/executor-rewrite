package com.icbc.nbfh.entity.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TaskSubmitResultDTO {

    /**
     * 任务编号。
     */
    private String taskNo;

    /**
     * 状态码。
     */
    private String status;

    private String message;
}
