package com.icbc.nbfh.common.constant;

import java.util.regex.Pattern;

/**
 * 通用参数校验常量。
 */
public final class ValidationConstants {

    /**
     * 工具类不允许实例化。
     */
    private ValidationConstants() {
    }

    /** 任务编号格式。 */
    public static final Pattern TASK_NO_PATTERN =
            Pattern.compile("TASK\\d{12}");

    /** SHA256格式。 */
    public static final Pattern SHA256_PATTERN =
            Pattern.compile("[0-9a-fA-F]{64}");

    /** Redis版本号格式。 */
    public static final Pattern VERSION_PATTERN = Pattern.compile(
            "[0-9]+(?:\\.[0-9]+){1,3}(?:-[0-9A-Za-z.-]+)?"
    );

    /** 上传安装包最大为50 MiB。 */
    public static final long MAX_UPGRADE_PACKAGE_SIZE_BYTES =
            50L * 1024L * 1024L;

    /** 安装包原始文件名允许的最大长度。 */
    public static final int MAX_PACKAGE_FILE_NAME_LENGTH = 128;

}
