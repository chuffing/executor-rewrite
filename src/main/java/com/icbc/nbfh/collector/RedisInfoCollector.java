package com.icbc.nbfh.collector;

import com.icbc.nbfh.client.RedisConnectionManager;
import com.icbc.nbfh.common.constant.CollectConstant;
import com.icbc.nbfh.common.constant.CommonConstant;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.MetricDataDTO;
import com.icbc.nbfh.util.HostMemoryUtils;
import com.icbc.nbfh.util.NetworkUtils;
import com.icbc.nbfh.util.RedisInfoParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Redis 指标采集器：只读执行 PING / INFO，不修改生产数据
 *   Redis 命令加权平均延迟（微秒）：INFO commandstats 的 calls/usec 相邻两次差值；
 *   主从复制延迟（秒）：从节点取 master_last_io_seconds_ago，主节点为 0；
 *   memUs：Redis 内存使用量（GB），由 INFO used_memory（字节）换算；
 *   pingLatency：执行器 ping 平台的 HTTP 往返延迟（毫秒），由采集服务回填

 */
@Slf4j
@Component
public class RedisInfoCollector {

    /** 逻辑核数，启动时取一次即可 */
    private static final int CPU_CORES = Math.max(1, Runtime.getRuntime().availableProcessors());

    /** 百分比/两位小数格式 */
    private static final String FORMAT_SCALE_2 = "%.2f";

    /** 延迟/内存等三位小数格式 */
    private static final String FORMAT_SCALE_3 = "%.3f";

    private final AgentProperties properties;

    private final RedisConnectionManager connectionManager;

    /** 上一次采集的 commandstats 累计值，用于计算平均延迟 */
    private long lastCmdCalls = 0;
    private long lastCmdUsec = 0;
    private boolean hasLastSample = false;

    /** 上一次采集的 CPU 累计秒数与采样时刻，用于计算相邻采集周期的平均 CPU 使用率 */
    private double lastCpuSys = 0D;
    private double lastCpuUser = 0D;
    private long lastCpuSampleNanos = 0L;
    private boolean hasLastCpuSample = false;

    public RedisInfoCollector(AgentProperties properties, RedisConnectionManager connectionManager) {
        this.properties = properties;
        this.connectionManager = connectionManager;
    }

    /**
     * 执行一次采集，采集失败不抛异常，返回 status=down 并携带 errorMsg
     */
    public synchronized MetricDataDTO collect() {
        MetricDataDTO data = new MetricDataDTO();
        data.setCollectTime(System.currentTimeMillis());
        try (Jedis jedis = connectionManager.borrow()) {
            // INFO all 已包含 server/memory/stats/replication/cpu/commandstats 等全部 section
            Map<String, String> info = RedisInfoParser.parse(jedis.info("all"));

            collectCpu(data, info);
            collectMemory(data, info);
            collectLatency(data, info);
            collectNetworkAndQps(data, info);
            collectIdentityAndReplication(data, info);

            data.setStatus(CollectConstant.STATUS_UP);
            data.setErrorMsg("");
            hasLastSample = true;
        } catch (Exception e) {
            log.warn("Redis 指标采集失败: {}", e.getMessage());
            data.setStatus(CollectConstant.STATUS_DOWN);
            data.setErrorMsg(truncate(e.getMessage(), CollectConstant.MAX_ERROR_MSG_LENGTH));
        }
        return data;
    }

    /**
     * 注册前采集 Redis 身份信息，用于平台端创建 RedisInstance
     *
     * 平台端约定 key：host / tcp_port / role / version / run_id / master_host / master_port/
     */
    public synchronized Map<String, String> collectRegistrationInfo() {
        Map<String, String> result = new LinkedHashMap<>();
        try (Jedis jedis = connectionManager.borrow()) {
            Map<String, String> server = RedisInfoParser.parse(jedis.info("server"));
            Map<String, String> replication = RedisInfoParser.parse(jedis.info("replication"));

            String role = normalizeRole(replication.get("role"));
            result.put(CollectConstant.INFO_HOST,
                    defaultIfBlank(properties.getRedis().getHost(), CommonConstant.DEFAULT_LOCALHOST_IP));
            result.put(CollectConstant.INFO_TCP_PORT, defaultIfBlank(server.get("tcp_port"),
                    String.valueOf(properties.getRedis().getPort())));
            result.put(CollectConstant.INFO_ROLE, role);
            result.put(CollectConstant.INFO_VERSION,
                    defaultIfBlank(server.get("redis_version"), CollectConstant.DEFAULT_REDIS_VERSION));
            result.put(CollectConstant.INFO_RUN_ID, normalizeRunId(server.get("run_id")));
            if (CollectConstant.ROLE_REPLICA.equals(role)) {
                putIfHasText(result, CollectConstant.INFO_MASTER_HOST, replication.get("master_host"));
                putIfHasText(result, CollectConstant.INFO_MASTER_PORT, replication.get("master_port"));
            }
        }
        return result;
    }

    /**
     * CPU 占用率：Redis 进程 used_cpu_sys/user 在相邻两次采集之间的增量 / 间隔 / 整机逻辑核数
     * 首次采集没有基线，上报 0.00；Redis 重启导致计数回退时本周期同样回退为 0.00，不产生负值
     */
    private void collectCpu(MetricDataDTO data, Map<String, String> info) {
        double sys = RedisInfoParser.toDouble(info.get("used_cpu_sys"));
        double user = RedisInfoParser.toDouble(info.get("used_cpu_user"));
        long nowNanos = System.nanoTime();

        if (!hasLastCpuSample) {
            saveCpuSample(sys, user, nowNanos);
            data.setCpuPer("0.00");
            return;
        }

        double elapsedSeconds = (nowNanos - lastCpuSampleNanos) / 1_000_000_000.0;
        double consumedSeconds = (sys - lastCpuSys) + (user - lastCpuUser);
        saveCpuSample(sys, user, nowNanos);

        if (elapsedSeconds <= 0 || consumedSeconds < 0) {
            // 时钟异常或 Redis 重启导致计数器重置，本周期按 0 处理
            data.setCpuPer("0.00");
            return;
        }
        double cpuPercent = consumedSeconds / elapsedSeconds / CPU_CORES * 100.0;
        data.setCpuPer(format(clamp(cpuPercent, 0.0, 100.0), 2));
    }

    /** 记录本次 CPU 累计值基线 */
    private void saveCpuSample(double sys, double user, long sampleNanos) {
        this.lastCpuSys = sys;
        this.lastCpuUser = user;
        this.lastCpuSampleNanos = sampleNanos;
        this.hasLastCpuSample = true;
    }

    /**
     * 内存：服务器内存占用率优先读 /proc/meminfo（Linux 生产环境），
     * 读取不到时回退为 Redis RSS 占整机内存比例；
     * Redis 内存使用量按平台口径换算为 GB（used_memory 字节 / 1024^3）
     */
    private void collectMemory(MetricDataDTO data, Map<String, String> info) {
        Double serverMemPercent = isRedisLocal() ? HostMemoryUtils.readUsagePercent() : null;
        if (serverMemPercent == null) {
            serverMemPercent = redisMemorySharePercent(info);
        }
        if (serverMemPercent != null) {
            data.setMemServerPer(format(clamp(serverMemPercent, 0.0, 100.0), 2));
        } else {
            // 平台必填校验，无法读取时上报 0
            data.setMemServerPer("0.00");
        }

        long usedMemoryBytes = RedisInfoParser.toLong(info.get("used_memory"));
        double usedMemoryGb = usedMemoryBytes / 1024.0 / 1024.0 / 1024.0;
        data.setMemUs(format(clamp(usedMemoryGb, 0.0, 1024.0), 3));
    }

    /**
     * 判断 Redis 是否部署在执行器本机（同机时 /proc/meminfo 才是 Redis 所在服务器的内存）
     */
    private boolean isRedisLocal() {
        String host = properties.getRedis().getHost();
        if (NetworkUtils.isLoopbackHost(host)) {
            return true;
        }
        try {
            InetAddress target = InetAddress.getByName(host.trim());
            if (target.isLoopbackAddress()) {
                return true;
            }
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    if (addresses.nextElement().equals(target)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("判断 Redis 是否本机失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 命令加权平均延迟（微秒）
     */
    private void collectLatency(MetricDataDTO data, Map<String, String> info) {
        long[] commandStats = RedisInfoParser.sumCommandStats(info);
        long totalCalls = commandStats[0];
        long totalUsec = commandStats[1];

        String latencyUsec;
        if (hasLastSample && totalCalls > lastCmdCalls && totalUsec >= lastCmdUsec) {
            // 相邻两次采集的增量，反映最近一个采集周期的真实平均延迟
            long deltaCalls = totalCalls - lastCmdCalls;
            long deltaUsec = totalUsec - lastCmdUsec;
            latencyUsec = format((double) deltaUsec / deltaCalls, 3);
        } else if (totalCalls > 0 && totalUsec > 0) {
            // 首次采集或 Redis 重启导致计数器重置：用累计值估算，避免首条上报恒为 0
            latencyUsec = format((double) totalUsec / totalCalls, 3);
        } else {
            latencyUsec = "0.000";
        }
        data.setLatency(latencyUsec);

        lastCmdCalls = totalCalls;
        lastCmdUsec = totalUsec;
    }

    private void collectNetworkAndQps(MetricDataDTO data, Map<String, String> info) {
        data.setIoIik(normalizeNonNegative(info.get("instantaneous_input_kbps")));
        data.setIoIok(normalizeNonNegative(info.get("instantaneous_output_kbps")));
        data.setOps(normalizeNonNegative(info.get("instantaneous_ops_per_sec")));
    }

    /**
     * 主从复制延迟（秒）
     * 平台校验要求 masterHost/masterPort 必填；主节点没有上游 master，回填自身地址
     * 从节点优先使用 INFO replication 的 master_host/master_port
     */
    private void collectIdentityAndReplication(MetricDataDTO data, Map<String, String> info) {
        String role = normalizeRole(info.get("role"));
        data.setRole(role);
        data.setRedisVersion(defaultIfBlank(emptyToNull(info.get("redis_version")),
                CollectConstant.DEFAULT_REDIS_VERSION));
        data.setRunId(normalizeRunId(info.get("run_id")));
        data.setConnectedSlaves(emptyToNull(info.get("connected_slaves")));

        String selfHost = platformVisibleHost();
        String selfPort = String.valueOf(properties.getRedis().getPort());
        if (CollectConstant.ROLE_REPLICA.equals(role)) {
            long seconds = 0L;
            String replDelay = info.get("master_last_io_seconds_ago");
            if (StringUtils.hasText(replDelay)) {
                try {
                    seconds = (long) Double.parseDouble(replDelay.trim());
                } catch (Exception ignored) {
                    seconds = 0L;
                }
            }
            // 平台校验上限 86400 秒
            data.setReplDelay(String.valueOf(Math.max(0L,
                    Math.min(seconds, CollectConstant.MAX_REPL_DELAY_SECONDS))));
            // 故障切换过程中 INFO 可能返回空/问号，平台要求 host/port 必填且合法
            data.setMasterHost(normalizeMasterHost(info.get("master_host"), selfHost));
            data.setMasterPort(normalizeMasterPort(info.get("master_port"), selfPort));
        } else {
            data.setReplDelay("0");
            data.setMasterHost(selfHost);
            data.setMasterPort(selfPort);
        }
    }

    /**
     * 主节点自报地址：本机 Redis 使用执行器对外 IP，远程 Redis 使用配置的地址
     * 用于满足平台对 masterHost/masterPort 的必填校验，并保证拓扑展示可读
     */
    private String platformVisibleHost() {
        String redisHost = properties.getRedis().getHost();
        if (!StringUtils.hasText(redisHost)) {
            return CommonConstant.DEFAULT_LOCALHOST_IP;
        }
        redisHost = redisHost.trim();
        if (NetworkUtils.isLoopbackHost(redisHost)) {
            try {
                return NetworkUtils.resolveHostIp(properties.getHostIp());
            } catch (Exception e) {
                return CommonConstant.DEFAULT_LOCALHOST_IP;
            }
        }
        return redisHost;
    }

    /** 回退口径：Redis RSS / 系统总内存 */
    private Double redisMemorySharePercent(Map<String, String> info) {
        long usedRss = RedisInfoParser.toLong(info.get("used_memory_rss"));
        long totalSystemMemory = RedisInfoParser.toLong(info.get("total_system_memory"));
        if (totalSystemMemory <= 0) {
            totalSystemMemory = RedisInfoParser.parseHumanBytes(info.get("total_system_memory_human"));
        }
        if (usedRss <= 0 || totalSystemMemory <= 0) {
            return null;
        }
        return usedRss * 100.0 / totalSystemMemory;
    }

    /** Redis 角色统一为平台约定值：MASTER / REPLICA */
    private String normalizeRole(String role) {
        if (!StringUtils.hasText(role)) {
            return CollectConstant.ROLE_MASTER;
        }
        String upper = role.trim().toUpperCase(Locale.ROOT);
        if (CollectConstant.REDIS_ROLE_SLAVE.equals(upper)
                || CollectConstant.ROLE_REPLICA.equals(upper)) {
            return CollectConstant.ROLE_REPLICA;
        }
        return CollectConstant.ROLE_MASTER;
    }

    /** 平台要求 runId 为 40 位小写十六进制，异常 INFO 时给出合法兜底 */
    private String normalizeRunId(String runId) {
        if (!StringUtils.hasText(runId)) {
            return CollectConstant.EMPTY_RUN_ID;
        }
        String normalized = runId.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(CollectConstant.RUN_ID_PATTERN)) {
            return CollectConstant.EMPTY_RUN_ID;
        }
        return normalized;
    }

    /** 平台要求 masterHost 非空且符合 host 字符集；异常 INFO 时回填本机地址 */
    private String normalizeMasterHost(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String host = value.trim();
        if ("?".equals(host)
                || host.length() > CollectConstant.MAX_HOST_LENGTH
                || !host.matches(CollectConstant.HOST_PATTERN)) {
            return fallback;
        }
        return host;
    }

    /** 平台要求 masterPort 为 1~65535 的整数；异常时回填本机端口 */
    private String normalizeMasterPort(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            int port = Integer.parseInt(value.trim());
            return port >= CollectConstant.MIN_PORT && port <= CollectConstant.MAX_PORT
                    ? String.valueOf(port) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 平台要求非负数；INFO 缺失/异常值统一回退 0 */
    private String normalizeNonNegative(String value) {
        if (!StringUtils.hasText(value)) {
            return "0";
        }
        try {
            double number = Double.parseDouble(value.trim());
            if (Double.isNaN(number) || Double.isInfinite(number) || number < 0) {
                return "0";
            }
            return value.trim();
        } catch (NumberFormatException e) {
            return "0";
        }
    }

    private void putIfHasText(Map<String, String> map, String key, String value) {
        if (StringUtils.hasText(value)) {
            map.put(key, value.trim());
        }
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 固定精度的格式化
     */
    private String format(double value, int scale) {
        switch (scale) {
            case 2:
                return String.format(Locale.ROOT, FORMAT_SCALE_2, value);
            case 3:
                return String.format(Locale.ROOT, FORMAT_SCALE_3, value);
            default:
                return BigDecimal.valueOf(value)
                        .setScale(scale, RoundingMode.HALF_UP)
                        .toPlainString();
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
