package com.icbc.nbfh.common.constant;

import cn.hutool.http.HttpStatus;

/**
 * 任务结果持久化及上报常量。
 */
public final class TaskResultConstants {

    /** 平台任务结果上报接口。 */
    public static final String REPORT_PATH = "/api/task/report";

    /** HTTP认证请求头。 */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /** Bearer认证前缀。 */
    public static final String BEARER_PREFIX = "Bearer ";

    /** 平台业务处理成功状态码。 */
    public static final int PLATFORM_SUCCESS_CODE = HttpStatus.HTTP_OK;

    /** 本地待上报结果文件后缀。 */
    public static final String RESULT_FILE_SUFFIX = ".json";

    /** 本地结果临时文件后缀。 */
    public static final String TEMP_FILE_SUFFIX = ".tmp";

    /** 本地保存的错误信息最大长度。 */
    public static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    /** 工具类不允许实例化。 */
    private TaskResultConstants() {
    }
}
