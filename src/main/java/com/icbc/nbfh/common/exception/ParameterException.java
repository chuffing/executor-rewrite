package com.icbc.nbfh.common.exception;

import com.icbc.nbfh.common.Enum.ParameterErrorCode;

/**
 * 参数校验异常。
 */
public class ParameterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    private final String msg;

    private final String errorMsg;

    /**
     * 创建参数校验异常。
     */
    public ParameterException(ParameterErrorCode errorCode,
                              String errorMsg) {
        super(errorMsg);
        this.code = errorCode.getCode();
        this.msg = errorCode.getMsg();
        this.errorMsg = errorMsg;
    }

    /**
     * 获取错误码。
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取错误说明。
     */
    public String getMsg() {
        return msg;
    }

    /**
     * 获取详细错误信息。
     */
    public String getErrorMsg() {
        return errorMsg;
    }
}
