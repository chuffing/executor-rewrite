package com.icbc.nbfh.common.Enum;

/**
 * 平台与执行器之间的任务接收业务响应码。
 *
 * <p>该枚举只表示统一响应体中的业务结果，不表示真实HTTP状态码。</p>
 */
public enum AgentTaskResponseCodeEnum {

    ACCEPTED(202, "任务已接收"),

    PARAMETER_REJECTED(400, "任务参数错误"),

    AUTHENTICATION_REJECTED(401, "身份认证失败"),

    PAYLOAD_TOO_LARGE(413, "上传内容过大"),

    AGENT_BUSY(503, "执行器繁忙");

    private final int code;

    private final String defaultMsg;

    AgentTaskResponseCodeEnum(int code, String defaultMsg) {
        this.code = code;
        this.defaultMsg = defaultMsg;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMsg() {
        return defaultMsg;
    }

    /**
     * 根据业务响应码获取对应枚举。
     *
     * @param code 业务响应码
     * @return 对应枚举；未定义的业务响应码返回null
     */
    public static AgentTaskResponseCodeEnum fromCode(int code) {
        for (AgentTaskResponseCodeEnum responseCode : values()) {
            if (responseCode.code == code) {
                return responseCode;
            }
        }
        return null;
    }
}
