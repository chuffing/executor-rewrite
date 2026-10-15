package com.icbc.nbfh.common.Enum;


/**
 * 执行器支持的运维操作类型。
 */
public enum OperationType {

    PRECHECK_HOST,

    /**
     * 启动Redis。
     */
    START_REDIS,

    /**
     * 停止Redis。
     */
    STOP_REDIS,

    /**
     * 启动Sentinel。
     */
    START_SENTINEL,

    /**
     * 停止Sentinel。
     */
    STOP_SENTINEL,

    /**
     * 恢复配置文件。
     */
    RESTORE_CONFIG,

    /**
     * 部署配置文件。
     */
    DEPLOY_CONFIG,

    /**
     * 部署版本。
     */
    DEPLOY_VERSION,

    /**
     * 版本切换。
     */
    SWITCH_VERSION,

    /**
     * 回滚版本。
     */
    ROLLBACK_VERSION
}
