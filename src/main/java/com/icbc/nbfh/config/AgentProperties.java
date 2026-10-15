package com.icbc.nbfh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** 平台地址。 */
    private String platformUrl;

    /** 预设注册码。 */
    private String registerCode;

    /** 执行器名称。 */
    private String agentName;

    /** 指标采集间隔，单位为毫秒。 */
    private long collectInterval = 300000;

    /** 心跳间隔，单位为毫秒。 */
    private long heartbeatInterval = 30000;

    /** 本地 Redis 连接配置。 */
    private RedisConfig redis = new RedisConfig();

    /** 固定运维脚本目录。 */
    private String scriptDir;

    /** Redis systemd 服务名。 */
    private String redisServiceName;

    /** Redis 配置文件。 */
    private String redisConfigFile;

    /** Redis 数据目录。 */
    private String redisDataDir;

    /** Sentinel 端口。 */
    private int sentinelPort = 26379;

    /** Sentinel systemd 服务名。 */
    private String sentinelServiceName;

    /** Sentinel 配置文件。 */
    private String sentinelConfigFile;

    /** 备份磁盘中的 Redis 配置文件。 */
    private String redisBackupFile;

    /** 备份磁盘中的 Sentinel 配置文件。 */
    private String sentinelBackupFile;

    /** Token 持久化文件。 */
    private String tokenFile = "./agent-token.dat";

    /** 升级包临时目录。 */
    private String upgradeDir = "/tmp/redis-upgrade/";

    @Data
    public static class RedisConfig {
        private String host = "127.0.0.1";
        private int port = 6379;
        private String password;
        private int db = 0;
        private int timeout = 5000;
        private Pool pool = new Pool();

        @Data
        public static class Pool {
            private int maxTotal = 5;
            private int maxIdle = 2;
            private int minIdle = 1;
            private long maxWaitMillis = 2000;
        }
    }
}
