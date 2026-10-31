package com.icbc.nbfh.config;

import com.icbc.nbfh.common.constant.CommonConstant;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** 执行器名称。 */
    private String agentName;

    /** 本机对平台可见的 IPv4 地址，留空自动识别。 */
    private String hostIp;

    /** 未注册或未绑定时的注册重试间隔，毫秒。 */
    private long registerRetryInterval = 30000;

    /** 采集数据本地留存目录。 */
    private String dataDir = "./data";

    /** 本地留存采集数据的保留天数。 */
    private int dataRetainDays = 7;

    /** 平台通信配置。 */
    private PlatformConfig platform = new PlatformConfig();

    /** 定时任务配置。 */
    private ScheduleConfig schedule = new ScheduleConfig();

    /** 任务结果本地保存配置。 */
    @Valid
    private TaskResultConfig taskResult = new TaskResultConfig();

    /** Redis 配置。 */
    @Valid
    private RedisConfig redis = new RedisConfig();

    /** Sentinel 配置。 */
    @Valid
    private SentinelConfig sentinel = new SentinelConfig();

    /** 运维操作配置。 */
    @Valid
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

        /** 注册重试间隔，毫秒。 */
        private long registerInterval = 30000;
    }


    @Data
    public static class TaskResultConfig {

        /** 待上报任务结果的本地保存目录。 */
        @NotBlank(message = "任务结果保存目录不能为空")
        private String directory = "./task-results";

    }


    @Data
    public static class RedisConfig {

        /** Redis 连接配置。 */
        @NotBlank(message = "Redis主机不能为空")
        private String host = CommonConstant.DEFAULT_LOCALHOST_IP;
        private int port = 6379;

        @NotBlank(message = "Redis密码不能为空")
        private String password;
        private int db = 0;
        private int timeout = 2000;

        /** Redis 文件及服务配置。 */
        private String serviceName;

        @NotBlank(message = "Redis配置文件路径不能为空")
        private String configFile;

        /** Redis运行数据的受控根目录，启动脚本只允许在该目录下创建子目录。 */
        @NotBlank(message = "Redis数据根目录不能为空")
        private String dataDir;

        /** Redis运行日志的受控根目录。 */
        @NotBlank(message = "Redis日志根目录不能为空")
        private String logDir = "/opt/redis/logs";

        /** 停止Redis和Sentinel时使用的redis-cli命令或绝对路径。 */
        @NotBlank(message = "redis-cli路径不能为空")
        private String cliBin = "redis-cli";

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

        /** 是否采集 Sentinel 节点。 */
        private boolean enabled = true;

        @NotBlank(message = "Sentinel主机不能为空")
        private String host = CommonConstant.DEFAULT_LOCALHOST_IP;

        private int port = 26379;

        @NotBlank(message = "Sentinel密码不能为空")
        private String password;

        private int timeout = 2000;

        /** Sentinel 监控的主从组名称，同时作为节点采集的 masterName。 */
        private String serviceName;

        /**
         * 哨兵节点地址列表，格式 host:port。
         * 为空时只使用 host:port 这一台；配置多台后任一可达即可拿到完整哨兵列表。
         */
        private List<String> nodes = new ArrayList<>();

        @NotBlank(message = "Sentinel配置文件路径不能为空")
        private String configFile;

        /** Sentinel运行数据的受控根目录。 */
        @NotBlank(message = "Sentinel数据根目录不能为空")
        private String dataDir = "/opt/redis/data";

        /** Sentinel运行日志的受控根目录。 */
        @NotBlank(message = "Sentinel日志根目录不能为空")
        private String logDir = "/opt/redis/logs";

        private String backupFile;
    }


    @Data
    public static class OperationConfig {

        /** 固定 Shell 脚本目录。 */
        private String scriptDir;

        /** Redis源码包暂存目录。 */
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

        /** Redis源码编译及版本部署超时，秒。 */
        @Min(value = 1, message = "Redis源码编译及版本部署超时不能小于1秒")
        private long versionDeployTimeoutSeconds = 1800;

        /** Redis 停止检查超时，秒。 */
        private long stopCheckTimeoutSeconds = 10;

        /** Redis 停止检查间隔，毫秒。 */
        private long stopCheckIntervalMillis = 1000;

        /** Redis 启动检查超时，秒。 */
        private long startCheckTimeoutSeconds = 30;

        /** Redis 启动检查间隔，毫秒。 */
        private long startCheckIntervalMillis = 1000;

        /** Redis和Sentinel拓扑验收超时，秒。 */
        @Min(value = 1, message = "拓扑验收超时不能小于1秒")
        private long topologyCheckTimeoutSeconds = 60;

        /** Redis和Sentinel拓扑验收间隔，毫秒。 */
        @Min(value = 100, message = "拓扑验收间隔不能小于100毫秒")
        private long topologyCheckIntervalMillis = 5000;
    }
}
