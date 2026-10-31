package com.icbc.nbfh.entity.DTO;

import com.icbc.nbfh.common.exception.BusinessCode;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 运维操作执行结果。
 */
@Data
@AllArgsConstructor
public class OperationResultDTO {

    /**
     * 是否执行成功。
     */
    private boolean success;

    /**
     * 失败错误码，成功时为空。
     */
    private Integer errorCode;

    /**
     * 执行结果说明。
     */
    private String message;

    /**
     * 操作开始时间。
     */
    private long startTime;

    /**
     * 操作结束时间。
     */
    private long endTime;


    public static OperationResultDTO success(String message,
                                             long startTime,
                                             long endTime) {
        return new OperationResultDTO(
                true,
                null,
                message,
                startTime,
                endTime
        );
    }

    public static OperationResultDTO failure(BusinessCode businessCode,
                                             String message,
                                             long startTime,
                                             long endTime) {
        return new OperationResultDTO(
                false,
                businessCode.getCode(),
                message,
                startTime,
                endTime
        );
    }
}
