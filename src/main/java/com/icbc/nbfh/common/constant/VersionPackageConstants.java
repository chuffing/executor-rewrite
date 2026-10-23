package com.icbc.nbfh.common.constant;

/**
 * Redis源码包处理常量。
 */
public final class VersionPackageConstants {

    /** 源码包暂存目录名称。 */
    public static final String STAGING_DIRECTORY_NAME = "staging";

    /** 上传过程中使用的临时文件名。 */
    public static final String TEMP_PACKAGE_FILE_NAME = "package.part";

    /** 校验完成后的固定源码包文件名。 */
    public static final String STAGED_PACKAGE_FILE_NAME = "package.tar.gz";

    /** Redis官方源码包文件名前缀。 */
    public static final String SOURCE_PACKAGE_PREFIX = "redis-";

    /** Redis官方源码包文件名后缀。 */
    public static final String SOURCE_PACKAGE_SUFFIX = ".tar.gz";

    /**
     * 工具类不允许实例化。
     */
    private VersionPackageConstants() {
    }
}
