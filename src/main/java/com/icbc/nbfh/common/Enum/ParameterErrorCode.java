package com.icbc.nbfh.common.Enum;

/**
 * 参数校验错误码。
 */
public enum ParameterErrorCode {

    TASK_NO_INVALID("PARAM_001", "任务编号不合法"),

    STEP_NO_INVALID("PARAM_002", "步骤编号不合法"),

    TASK_TYPE_EMPTY("PARAM_003", "任务类型不能为空"),

    TASK_TYPE_UNSUPPORTED("PARAM_004", "任务类型不支持"),

    TASK_PARAM_INVALID("PARAM_005", "任务参数不合法"),

    PACKAGE_FILE_INVALID("PARAM_006", "安装包不合法"),

    PACKAGE_CHECKSUM_MISMATCH("PARAM_007", "安装包摘要不一致"),

    CONFIG_FILE_INVALID("PARAM_008", "配置文件不合法"),

    CONFIG_CHECKSUM_MISMATCH("PARAM_009", "配置文件摘要不一致");

    private final String code;

    private final String msg;

    /**
     * 创建参数校验错误码。
     */
    ParameterErrorCode(String code, String msg) {
        this.code = code;
        this.msg = msg;
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
}
