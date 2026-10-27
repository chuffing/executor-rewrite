package com.icbc.nbfh.collector;

import com.icbc.nbfh.common.constant.CommonConstant;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.MetricDataDTO;
import com.icbc.nbfh.util.NetworkUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redis 指标采集器：只读执行 PING / INFO，不修改生产数据
 *
 *   Redis 命令加权平均延迟（微秒），由 INFO commandstats 的calls/usec 相邻两次采集差值计算；
 *   主从复制延迟（秒），从节点取
 *       {@code master_last_io_seconds_ago}，主节点为 0；
 *   {@code memUs}：Redis 内存使用量（GB），由 INFO used_memory（字节）换算；
 *   {@code pingLatency}：执行器 ping 平台的 HTTP 往返延迟（毫秒），
 *       由采集服务调用平台 {@code /api/agent/ping} 后回填
 */
@Slf4j
@Component
public class RedisInfoCollector {

    /** CPU 采样窗口，200ms 足够接近瞬时，又不至于因时钟粒度抖动 */
    private static final long SAMPLE_WINDOW_MS = 200;

    /** 注册前 Redis 就绪探测次数 */
    private static final int PING_TIMES = 3;

    /** 合法但无意义的 run_id 兜底值（平台要求 40 位小写十六进制） */
    private static final String EMPTY_RUN_ID = "0000000000000000000000000000000000000000";

    /** Redis 内存 human 字符串解析，如 15.62G / 800M / 123K */
    private static final Pattern MEM_PATTERN =
            Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?)", Pattern.CASE_INSENSITIVE);

    /** 逻辑核数，启动时取一次即可 */
    private static final int CPU_CORES = Math.max(1, Runtime.getRuntime().availableProcessors());

    private final AgentProperties properties;

    private volatile JedisPool jedisPool;

    /** 上一次采集的 commandstats 累计值，用于计算平均延迟 */
    private long lastCmdCalls = 0;
    private long lastCmdUsec = 0;
    private boolean hasLastSample = false;

    public RedisInfoCollector(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 初始化 Redis 连接池
     */
    public synchronized void initPool() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            return;
        }
        AgentProperties.RedisConfig redisConfig = properties.getRedis();
        if (redisConfig == null || !StringUtils.hasText(redisConfig.getHost())) {
            throw new IllegalStateException("未配置 agent.redis.host");
        }

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(redisConfig.getPool().getMaxTotal());
        poolConfig.setMaxIdle(redisConfig.getPool().getMaxIdle());
        poolConfig.setMinIdle(redisConfig.getPool().getMinIdle());
        poolConfig.setMaxWaitMillis(redisConfig.getPool().getMaxWaitMillis());

        String password = StringUtils.hasText(redisConfig.getPassword())
                ? redisConfig.getPassword() : null;

        jedisPool = new JedisPool(poolConfig,
                redisConfig.getHost().trim(),
                redisConfig.getPort(),
                redisConfig.getTimeout(),
                password,
                redisConfig.getDb());
        log.info("Redis 连接池初始化完成: {}:{} (db={})",
                redisConfig.getHost(), redisConfig.getPort(), redisConfig.getDb());
    }

    /**
     * 注册前自检：Redis 是否已安装且连接正常
     * 连续 PING 3 次，全部返回 PONG 视为就绪；连接失败/认证失败/超时均返回 false
     * 未就绪时注册流程以 {@code redisReady=false} 先注册执行器，Redis 恢复后再二次注册绑定
     */
    public synchronized boolean isRedisReady() {
        try {
            initPool();
            try (Jedis jedis = jedisPool.getResource()) {
                for (int i = 0; i < PING_TIMES; i++) {
                    String pong = jedis.ping();
                    if (!"PONG".equalsIgnoreCase(pong)) {
                        return false;
                    }
                }
                return true;
            }
        } catch (Exception e) {
            log.debug("Redis 就绪探测失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 执行一次采集采集失败不抛异常，返回 status=down 并携带 errorMsg
     */
    public synchronized MetricDataDTO collect() {
        MetricDataDTO data = new MetricDataDTO();
        data.setCollectTime(System.currentTimeMillis());
        try {
            initPool();
            try (Jedis jedis = jedisPool.getResource()) {
                // INFO all 已包含 server/memory/stats/replication/cpu/commandstats 等全部 section
                String infoAllText = jedis.info("all");
                Map<String, String> info = parseInfo(infoAllText);

                collectCpu(data, info, jedis);
                collectMemory(data, info);
                collectLatency(data, info);
                collectNetworkAndQps(data, info);
                collectIdentityAndReplication(data, info);

                data.setStatus("up");
                data.setErrorMsg("");
                hasLastSample = true;
            }
        } catch (Exception e) {
            log.warn("Redis 指标采集失败: {}", e.getMessage());
            data.setStatus("down");
            data.setErrorMsg(truncate(e.getMessage(), 500));
        }
        return data;
    }

    /**
     * 注册前采集 Redis 身份信息，用于平台端创建 RedisInstance
     *
     * 平台端约定 key：host / tcp_port / role / version / run_id / master_host / master_port
     */
    public synchronized Map<String, String> collectRegistrationInfo() {
        initPool();
        Map<String, String> result = new LinkedHashMap<>();
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> server = parseInfo(jedis.info("server"));
            Map<String, String> replication = parseInfo(jedis.info("replication"));

            String role = normalizeRole(replication.get("role"));
            result.put("host", defaultIfBlank(properties.getRedis().getHost(), CommonConstant.DEFAULT_LOCALHOST_IP));
            result.put("tcp_port", defaultIfBlank(server.get("tcp_port"),
                    String.valueOf(properties.getRedis().getPort())));
            result.put("role", role);
            result.put("version", defaultIfBlank(server.get("redis_version"), "0.0.0"));
            result.put("run_id", normalizeRunId(server.get("run_id")));
            if ("REPLICA".equals(role)) {
                putIfHasText(result, "master_host", replication.get("master_host"));
                putIfHasText(result, "master_port", replication.get("master_port"));
            }
        }
        return result;
    }

    /**
     * CPU 占用率：Redis 进程 used_cpu_sys/user 在采样窗口内的增量 / 整机逻辑核数
     */
    private void collectCpu(MetricDataDTO data,
                            Map<String, String> info,
                            Jedis jedis) {
        double sys1 = getDouble(info.get("used_cpu_sys"));
        double user1 = getDouble(info.get("used_cpu_user"));
        long startNanos = System.nanoTime();
        sleepQuietly(SAMPLE_WINDOW_MS);

        Map<String, String> cpuInfo = parseInfo(jedis.info("cpu"));
        double sys2 = getDouble(cpuInfo.get("used_cpu_sys"));
        double user2 = getDouble(cpuInfo.get("used_cpu_user"));
        double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        if (elapsedSeconds <= 0) {
            data.setCpuPer("0.00");
            return;
        }
        double cpuPercent = ((sys2 - sys1) + (user2 - user1)) / elapsedSeconds / CPU_CORES * 100.0;
        data.setCpuPer(format(clamp(cpuPercent, 0.0, 100.0), 2));
    }

    /**
     * 内存：服务器内存占用率优先读 /proc/meminfo（Linux 生产环境），
     * 读取不到时回退为 Redis RSS 占整机内存比例
     * Redis 内存使用量按平台口径换算为 GB（used_memory 字节 / 1024^3）
     */
    private void collectMemory(MetricDataDTO data, Map<String, String> info) {
        Double serverMemPercent = isRedisLocal() ? readServerMemUsagePercent() : null;
        if (serverMemPercent == null) {
            serverMemPercent = redisMemorySharePercent(info);
        }
        if (serverMemPercent != null) {
            data.setMemServerPer(format(clamp(serverMemPercent, 0.0, 100.0), 2));
        } else {
            // 平台必填校验，无法读取时上报 0
            data.setMemServerPer("0.00");
        }

        long usedMemoryBytes = getLong(info.get("used_memory"));
        double usedMemoryGb = usedMemoryBytes / 1024.0 / 1024.0 / 1024.0;
        data.setMemUs(format(clamp(usedMemoryGb, 0.0, 1024.0), 3));
    }

    /**
     * 判断 Redis 是否部署在执行器本机（同机时 /proc/meminfo 才是 Redis 所在服务器的内存）
     */
    private boolean isRedisLocal() {
        String host = properties.getRedis().getHost();
        if (!StringUtils.hasText(host)) {
            return true;
        }
        host = host.trim();
        if ("127.0.0.1".equals(host) || "0.0.0.0".equals(host)
                || "localhost".equalsIgnoreCase(host) || "::1".equals(host)) {
            return true;
        }
        try {
            InetAddress target = InetAddress.getByName(host);
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
     * 延迟 1/3：命令加权平均延迟（微秒）
     */
    private void collectLatency(MetricDataDTO data, Map<String, String> info) {
        long[] commandStats = sumCommandStats(info);
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
     * 节点身份 + 延迟 2/3：主从复制延迟（秒）
     *
     * 平台校验要求 masterHost/masterPort 必填；主节点没有上游 master，
     * 这里回填自身地址，从节点优先使用 INFO replication 的 master_host/master_port
     */
    private void collectIdentityAndReplication(MetricDataDTO data, Map<String, String> info) {
        String role = normalizeRole(info.get("role"));
        data.setRole(role);
        data.setRedisVersion(defaultIfBlank(emptyToNull(info.get("redis_version")), "0.0.0"));
        data.setRunId(normalizeRunId(info.get("run_id")));
        data.setConnectedSlaves(emptyToNull(info.get("connected_slaves")));

        String selfHost = platformVisibleHost();
        String selfPort = String.valueOf(properties.getRedis().getPort());
        if ("REPLICA".equals(role)) {
            String replDelay = info.get("master_last_io_seconds_ago");
            long seconds = 0L;
            if (StringUtils.hasText(replDelay)) {
                try {
                    seconds = (long) Double.parseDouble(replDelay.trim());
                } catch (Exception ignored) {
                    seconds = 0L;
                }
            }
            // 平台校验上限 86400 秒
            data.setReplDelay(String.valueOf(Math.max(0L, Math.min(seconds, 86400L))));
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
            return "127.0.0.1";
        }
        redisHost = redisHost.trim();
        if ("127.0.0.1".equals(redisHost) || "0.0.0.0".equals(redisHost)
                || "localhost".equalsIgnoreCase(redisHost) || "::1".equals(redisHost)) {
            try {
                return NetworkUtils.resolveHostIp(properties.getHostIp());
            } catch (Exception e) {
                return "127.0.0.1";
            }
        }
        return redisHost;
    }

    /** 解析 INFO 返回为 key-value */
    private Map<String, String> parseInfo(String info) {
        Map<String, String> map = new HashMap<>();
        if (info == null) {
            return map;
        }
        for (String line : info.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int idx = line.indexOf(':');
            if (idx > 0) {
                map.put(line.substring(0, idx), line.substring(idx + 1).trim());
            }
        }
        return map;
    }

    /**
     * 汇总 commandstats：返回 [总 calls, 总 usec]
     * INFO 中格式为 cmdstat_get:calls=1000,usec=5000,usec_per_call=5.00
     */
    private long[] sumCommandStats(Map<String, String> info) {
        long totalCalls = 0;
        long totalUsec = 0;
        for (Map.Entry<String, String> entry : info.entrySet()) {
            if (!entry.getKey().startsWith("cmdstat_")) {
                continue;
            }
            String[] pairs = entry.getValue().split(",");
            for (String pair : pairs) {
                if (pair.startsWith("calls=")) {
                    totalCalls += parseLong(pair.substring(6));
                } else if (pair.startsWith("usec=")) {
                    totalUsec += parseLong(pair.substring(5));
                }
            }
        }
        return new long[]{totalCalls, totalUsec};
    }

    /**
     * 读取 /proc/meminfo 计算服务器内存占用率（%）
     * 非 Linux 或读取失败返回 null，由调用方回退其它口径
     */
    private Double readServerMemUsagePercent() {
        File meminfo = new File("/proc/meminfo");
        if (!meminfo.canRead()) {
            return null;
        }
        long totalKb = 0;
        long availableKb = -1;
        long freeKb = 0;
        long buffersKb = 0;
        long cachedKb = 0;
        long sReclaimableKb = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(meminfo.toPath()),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int idx = line.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim().split("\\s+")[0];
                long kb = parseLong(value);
                switch (key) {
                    case "MemTotal":
                        totalKb = kb;
                        break;
                    case "MemAvailable":
                        availableKb = kb;
                        break;
                    case "MemFree":
                        freeKb = kb;
                        break;
                    case "Buffers":
                        buffersKb = kb;
                        break;
                    case "Cached":
                        cachedKb = kb;
                        break;
                    case "SReclaimable":
                        sReclaimableKb = kb;
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            log.debug("读取 /proc/meminfo 失败: {}", e.getMessage());
            return null;
        }
        if (totalKb <= 0) {
            return null;
        }
        long available = availableKb >= 0
                ? availableKb
                : freeKb + buffersKb + cachedKb + sReclaimableKb;
        long used = Math.max(0, totalKb - available);
        return used * 100.0 / totalKb;
    }

    /** 回退口径：Redis RSS / 系统总内存 */
    private Double redisMemorySharePercent(Map<String, String> info) {
        long usedRss = getLong(info.get("used_memory_rss"));
        long totalSystemMemory = getLong(info.get("total_system_memory"));
        if (totalSystemMemory <= 0) {
            totalSystemMemory = parseMemHuman(info.get("total_system_memory_human"));
        }
        if (usedRss <= 0 || totalSystemMemory <= 0) {
            return null;
        }
        return usedRss * 100.0 / totalSystemMemory;
    }

    /** 解析 Redis human 内存字符串（15.62G / 800M / 123K / 1024B） */
    private long parseMemHuman(String raw) {
        if (!StringUtils.hasText(raw)) {
            return 0;
        }
        Matcher matcher = MEM_PATTERN.matcher(raw.trim());
        if (!matcher.find()) {
            return parseLong(raw);
        }
        double value = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2).toUpperCase(Locale.ROOT);
        long multiplier;
        switch (unit) {
            case "T":
                multiplier = 1L << 40;
                break;
            case "G":
                multiplier = 1L << 30;
                break;
            case "M":
                multiplier = 1L << 20;
                break;
            case "K":
                multiplier = 1L << 10;
                break;
            default:
                multiplier = 1L;
                break;
        }
        return (long) (value * multiplier);
    }

    /** Redis 角色统一为平台约定值：MASTER / REPLICA */
    private String normalizeRole(String role) {
        if (!StringUtils.hasText(role)) {
            return "MASTER";
        }
        String upper = role.trim().toUpperCase(Locale.ROOT);
        if ("SLAVE".equals(upper) || "REPLICA".equals(upper)) {
            return "REPLICA";
        }
        return "MASTER";
    }

    /** 平台要求 runId 为 40 位小写十六进制，异常 INFO 时给出合法兜底 */
    private String normalizeRunId(String runId) {
        if (!StringUtils.hasText(runId)) {
            return EMPTY_RUN_ID;
        }
        String normalized = runId.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[0-9a-f]{40}$")) {
            return EMPTY_RUN_ID;
        }
        return normalized;
    }

    /** 平台要求 masterHost 非空且符合 host 字符集；异常 INFO 时回填本机地址 */
    private String normalizeMasterHost(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String host = value.trim();
        if ("?".equals(host) || host.length() > 255
                || !host.matches("^[A-Za-z0-9.:\\-_\\[\\]]+$")) {
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
            return port >= 1 && port <= 65535 ? String.valueOf(port) : fallback;
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

    private String format(double value, int scale) {
        return String.format(Locale.ROOT, "%." + scale + "f", value);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private long getLong(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private long parseLong(String value) {
        return getLong(value);
    }

    private double getDouble(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public synchronized void destroy() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
                log.info("Redis 连接池已关闭");
            } catch (Exception e) {
                log.warn("关闭 Redis 连接池失败: {}", e.getMessage());
            }
        }
        jedisPool = null;
    }
}
