package com.icbc.nbfh.common.constant;

/**
 * 平台任务参数名称。
 */
public final class TaskParamConstants {

    public static final String REDIS_CONFIG_SHA256 = "redisConfigSha256";

    public static final String SENTINEL_CONFIG_SHA256 =
            "sentinelConfigSha256";

    /** 目标版本号。 */
    public static final String TARGET_VERSION = "targetVersion";

    /** 升级包SHA256摘要。 */
    public static final String PACKAGE_SHA256 = "packageSha256";

    /** 平台安装包记录编号。 */
    public static final String PACKAGE_ID = "packageId";

    /** 执行器暂存的安装包绝对路径。 */
    public static final String PACKAGE_LOCAL_PATH = "packageLocalPath";

    /**
     * 工具类不允许实例化。
     */
    private TaskParamConstants() {
    }
}
