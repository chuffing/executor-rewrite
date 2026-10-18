package com.icbc.nbfh.common.constant;

/** 配置上传的固定暂存名称。 */
public final class ConfigFileConstants {

    /** 配置文件暂存子目录。 */
    public static final String STAGING_DIRECTORY_NAME = "staging";

    public static final String DIRECTORY_MARKER = "-config-";

    public static final String REDIS_FILE_NAME = "redis.conf";

    public static final String SENTINEL_FILE_NAME = "sentinel.conf";

    /** 工具类不允许实例化。 */
    private ConfigFileConstants() {
    }
}
