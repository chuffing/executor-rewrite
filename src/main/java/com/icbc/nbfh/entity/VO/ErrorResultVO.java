package com.icbc.nbfh.entity.VO;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 接口错误响应。
 */
@Data
@AllArgsConstructor
public class ErrorResultVO {

    /** 错误码。 */
    private String code;

    /** 错误说明。 */
    private String msg;

    /** 详细错误信息。 */
    private String errorMsg;
}
