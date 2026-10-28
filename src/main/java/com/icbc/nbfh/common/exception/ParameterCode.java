package com.icbc.nbfh.common.exception;

/**
 * 参数校验异常码，统一使用50000段。
 */
public enum ParameterCode {

    /** 通用参数错误。 */
    PARAM_ERROR(50000, "请求参数错误"),

    /** 参数为空。 */
    PARAM_NULL(50001, "参数不能为空"),

    /** 参数为空白字符串。 */
    PARAM_BLANK(50002, "参数不能为空白字符串"),

    /** 参数格式不正确。 */
    PARAM_FORMAT_ERROR(50003, "参数格式不正确"),

    /** 参数数值超出允许范围。 */
    PARAM_RANGE_ERROR(50005, "参数数值超出允许范围"),

    /** 参数不在允许的枚举范围内。 */
    PARAM_ENUM_ERROR(50007, "参数不在允许的枚举范围内"),

    /** 上传文件为空。 */
    PARAM_FILE_EMPTY(50011, "上传文件不能为空"),

    /** 文件大小超出限制。 */
    PARAM_FILE_SIZE_ERROR(50013, "文件大小超出限制"),

    /** 文件名为空或格式不正确。 */
    PARAM_FILE_NAME_ERROR(50014, "文件名为空或格式不正确"),

    /** ID格式不正确。 */
    PARAM_ID_ERROR(50017, "ID格式不正确"),

    /** 参数非法。 */
    PARAM_INVALID(50020, "参数非法"),

    /** Redis安装包不合法。 */
    PACKAGE_FILE_INVALID(50021, "安装包不合法"),

    /** 任务编号不合法。 */
    TASK_NO_INVALID(50022, "任务编号不合法"),

    /** 步骤编号不合法。 */
    STEP_NO_INVALID(50023, "步骤编号不合法"),

    /** 任务类型不能为空。 */
    TASK_TYPE_EMPTY(50024, "任务类型不能为空"),

    /** 任务类型不支持。 */
    TASK_TYPE_UNSUPPORTED(50025, "任务类型不支持"),

    /** 任务参数不合法。 */
    TASK_PARAM_INVALID(50026, "任务参数不合法"),

    /** 安装包摘要不一致。 */
    PACKAGE_CHECKSUM_MISMATCH(50027, "安装包摘要不一致"),

    /** 配置文件不合法。 */
    CONFIG_FILE_INVALID(50028, "配置文件不合法"),

    /** 配置文件摘要不一致。 */
    CONFIG_CHECKSUM_MISMATCH(50029, "配置文件摘要不一致");

    private final Integer code;

    private final String msg;

    ParameterCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
