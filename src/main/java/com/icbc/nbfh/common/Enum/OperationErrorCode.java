package com.icbc.nbfh.common.Enum;

/**
 * 运维操作执行失败时使用的错误码。
 */
public enum OperationErrorCode {

    /**
     * 操作参数不合法。
     */
    INVALID_PARAMETER,

    /**
     * 执行操作所需的前置条件不满足。
     */
    PRECONDITION_FAILED,

    /**
     * 主机资源不足。
     */
    RESOURCE_INSUFFICIENT,

    /**
     * 操作需要使用的端口已被占用。
     */
    PORT_OCCUPIED,

    /**
     * 执行操作所需的命令或脚本不存在。
     */
    COMMAND_NOT_FOUND,

    /**
     * 命令或脚本执行超时。
     */
    COMMAND_TIMEOUT,

    /**
     * 命令或脚本执行失败。
     */
    COMMAND_EXECUTION_FAILED,

    /**
     * 本地配置缺失或配置内容不合法。
     */
    CONFIG_INVALID,

    /**
     * 文件摘要与平台下发的预期摘要不一致。
     */
    CHECKSUM_MISMATCH,

    /**
     * 服务启动后的健康检查未通过。
     */
    HEALTH_CHECK_FAILED,

    /**
     * 服务未在规定时间内启动完成。
     */
    START_TIMEOUT,

    /**
     * Redis主从复制状态异常。
     */
    REPLICATION_FAILED,

    /**
     * Sentinel状态或主节点发现结果异常。
     */
    SENTINEL_FAILED,

    /**
     * 未分类的执行异常。
     */
    UNKNOWN_ERROR
}
