package com.icbc.nbfh.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TaskSubmitResultDTO {

    private String taskNo;

    private String status;

    private String message;
}
