package com.icbc.nbfh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** 执行器名称。 */
    private String agentName;

    /** 平台通信配置。 */
    private PlatformConfig platform = new PlatformConfig();

    /** 定时任务配置。 */
    private ScheduleConfig schedule = new ScheduleConfig();

    /** Redis 配置。 */
    private RedisConfig redis = new RedisConfig();

    /** Sentinel 配置。 */
    private SentinelConfig sentinel = new SentinelConfig();

    /** 运维操作配置。 */
    private OperationConfig operation = new OperationConfig();


    @Data
    public static class PlatformConfig {

        /** 主平台地址。 */
        private String url;

        /** 执行器注册码。 */
        private String registerCode;

        /** Token 本地保存路径。 */
        private String tokenFile = "./agent-token.dat";
    }


    @Data
    public static class ScheduleConfig {

        /** 指标采集间隔，毫秒。 */
        private long collectInterval = 300000;

        /** 心跳间隔，毫秒。 */
        private long heartbeatInterval = 30000;
    }


    @Data
    public static class RedisConfig {

        /** Redis 连接配置。 */
        private String host = "127.0.0.1";
        private int port = 6379;
        private String password;
        private int db = 0;
        private int timeout = 2000;

        /** Redis 文件及服务配置。 */
        private String serviceName;
        private String configFile;
        private String dataDir;
        private String backupFile;

        /** Redis 连接池。 */
        private PoolConfig pool = new PoolConfig();
    }


    @Data
    public static class PoolConfig {

        private int maxTotal = 5;
        private int maxIdle = 2;
        private int minIdle = 1;
        private long maxWaitMillis = 2000;
    }


    @Data
    public static class SentinelConfig {

        private int port = 26379;

        private String serviceName;

        private String configFile;

        private String backupFile;
    }


    @Data
    public static class OperationConfig {

        /** 固定 Shell 脚本目录。 */
        private String scriptDir;

        /** 升级包临时目录。 */
        private String upgradeDir = "/tmp/redis-upgrade/";

        /** Shell 执行超时，秒。 */
        private long scriptTimeoutSeconds = 10;

        /** Redis 停止检查超时，秒。 */
        private long stopCheckTimeoutSeconds = 10;

        /** Redis 停止检查间隔，毫秒。 */
        private long stopCheckIntervalMillis = 1000;
    }
}