package com.icbc.nbfh.common.exception;

public enum BusinessCode {

    SYSTEM_ERROR(30000, "系统内部异常"),
    OPERATION_ERROR(30001, "操作失败"),
    PARAMS_ERROR(30002, "业务参数错误"),
    NOT_LOGIN_ERROR(30003, "未登录"),
    NO_AUTH_ERROR(30004, "无权限"),
    NOT_FOUND_ERROR(30005, "业务对象不存在"),
    FORBIDDEN_ERROR(30006, "禁止访问");

    private Integer code;
    private String msg;
    BusinessCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
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
}
