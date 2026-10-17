package com.icbc.nbfh.common.constant;

/**
 * Redis协议返回值及INFO字段常量。
 */
public final class RedisProtocolConstants {

    public static final String PONG = "PONG";

    public static final String INFO_REPLICATION = "replication";

    public static final String INFO_ROLE = "role";

    public static final String INFO_MASTER_HOST = "master_host";

    public static final String INFO_MASTER_PORT = "master_port";

    public static final String STATUS_UP = "up";

    public static final String STATUS_DOWN = "down";

    /**
     * 工具类不允许实例化。
     */
    private RedisProtocolConstants() {
    }
}
