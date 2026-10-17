package com.icbc.nbfh.common.constant;

/**
 * Redis版本安装包处理常量。
 */
public final class VersionPackageConstants {

    /** 安装包暂存目录名称。 */
    public static final String STAGING_DIRECTORY_NAME = "staging";

    /** 上传过程中使用的临时文件名。 */
    public static final String TEMP_PACKAGE_FILE_NAME = "package.part";

    /** 校验完成后的固定安装包文件名。 */
    public static final String STAGED_PACKAGE_FILE_NAME = "package.tar.gz";

    /** 平台运行包文件名前缀。 */
    public static final String RUNTIME_PACKAGE_PREFIX = "redis-";

    /** 平台运行包文件名后缀。 */
    public static final String RUNTIME_PACKAGE_SUFFIX = "-runtime.tar.gz";

    /**
     * 工具类不允许实例化。
     */
    private VersionPackageConstants() {
    }
}
