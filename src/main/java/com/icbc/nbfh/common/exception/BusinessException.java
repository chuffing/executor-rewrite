package com.icbc.nbfh.common.exception;




public class BusinessException extends RuntimeException {
    private Integer code;
    private String msg;
    private String errorMsg;

    public BusinessException() {super();}

    public BusinessException(String msg) {
        super(msg);
        this.code = BusinessCode.OPERATION_ERROR.getCode();
        this.msg = msg;
    }

    public BusinessException(Integer code, String msg) {
        super(msg);
        this.code = code;
        this.msg = msg;
    }

    public BusinessException(Integer code, String msg, String errorMsg) {
        super(msg);
        this.code = code;
        this.msg = msg;
        this.errorMsg = errorMsg;
    }
    public BusinessException(BusinessCode businessCode){
        super(businessCode.getMsg());
        this.code = businessCode.getCode();
        this.msg = businessCode.getMsg();
    }

    public Integer getCode() {
        return code;
    }
    public void setCode(Integer code) {
        this.code = code;
    }
    public String getMsg() {
        return msg;
    }
    public void setMsg(String msg) {
        this.msg = msg;
    }
    public String getErrorMsg() {
        return errorMsg;
    }
    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }
}
