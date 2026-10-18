package com.icbc.nbfh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** 执行器名称。 */
    private String agentName;

    /** 平台通信配置。 */
    private PlatformConfig platform = new PlatformConfig();

    /** 定时任务配置。 */
    private ScheduleConfig schedule = new ScheduleConfig();

    /** Redis 配置。 */
    @Valid
    private RedisConfig redis = new RedisConfig();

    /** Sentinel 配置。 */
    @Valid
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
        @NotBlank(message = "Redis密码不能为空")
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

        private String host = "127.0.0.1";

        private int port = 26379;

        @NotBlank(message = "Sentinel密码不能为空")
        private String password;

        private int timeout = 2000;

        private String serviceName;

        private String configFile;

        private String backupFile;
    }


    @Data
    public static class OperationConfig {

        /** 固定 Shell 脚本目录。 */
        private String scriptDir;

        /** Redis安装包暂存目录。 */
        private String packageDir = "/opt/redis/packages/";

        /** Redis与Sentinel配置文件的暂存根目录。 */
        private String configFileDir;

        /** Redis各版本正式安装目录。 */
        private String installDir = "/opt/redis/versions/";

        /** 当前Redis版本软链接路径，预留给版本切换。 */
        private String currentDir = "/opt/redis/current";

        /** 预检查要求的最低磁盘可用空间，默认2 GiB。 */
        private long minFreeSpaceBytes = 2L * 1024L * 1024L * 1024L;

        /** Shell 执行超时，秒。 */
        private long scriptTimeoutSeconds = 10;

        /** Redis 停止检查超时，秒。 */
        private long stopCheckTimeoutSeconds = 10;

        /** Redis 停止检查间隔，毫秒。 */
        private long stopCheckIntervalMillis = 1000;

        /** Redis 启动检查超时，秒。 */
        private long startCheckTimeoutSeconds = 30;

        /** Redis 启动检查间隔，毫秒。 */
        private long startCheckIntervalMillis = 1000;
    }
}
