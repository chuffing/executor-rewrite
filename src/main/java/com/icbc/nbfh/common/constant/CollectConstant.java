package com.icbc.nbfh.common.constant;

/**
 * 采集域常量：Redis/Sentinel 采集口径、默认值、字段上限
 *
 * 采集器中不再出现裸字符串/魔法数字，统一引用本类，避免多处默认值漂移
 */
public final class CollectConstant {

    private CollectConstant() {
    }

    /* ===== 采集状态与角色 ===== */

    /** 采集成功状态 */
    public static final String STATUS_UP = "up";
    /** 采集失败状态 */
    public static final String STATUS_DOWN = "down";

    /** 主节点角色 */
    public static final String ROLE_MASTER = "MASTER";
    /** 从节点角色 */
    public static final String ROLE_REPLICA = "REPLICA";
    /** Redis 旧版本从节点角色名 */
    public static final String REDIS_ROLE_SLAVE = "SLAVE";

    /** 合法但无意义的 run_id 兜底值（平台要求 40 位小写十六进制） */
    public static final String EMPTY_RUN_ID = "0000000000000000000000000000000000000000";
    /** run_id 格式校验 */
    public static final String RUN_ID_PATTERN = "^[0-9a-f]{40}$";

    /** Redis 版本未知时的兜底值 */
    public static final String DEFAULT_REDIS_VERSION = "0.0.0";
    /** Redis 默认端口 */
    public static final String DEFAULT_REDIS_PORT = "6379";

    /* ===== 上报口径 ===== */

    /** 上报类型：自动采集 */
    public static final String COLLECT_TYPE_AUTO = "0";
    /** 上报类型：平台手动采集 */
    public static final String COLLECT_TYPE_MANUAL = "1";

    /** 采集失败原因最大长度（平台字段限制） */
    public static final int MAX_ERROR_MSG_LENGTH = 500;
    /** 每轮最多重传的历史数据条数，避免平台恢复后单轮阻塞过久 */
    public static final int MAX_RETRY_BATCH = 50;
    /** 主从复制延迟上限（秒），平台校验上限 86400 */
    public static final long MAX_REPL_DELAY_SECONDS = 86400L;

    /* ===== Redis 采集参数 ===== */

    /** 注册前 Redis 就绪探测次数 */
    public static final int REDIS_PING_TIMES = 3;
    /** Redis PING 正常应答 */
    public static final String PONG = "PONG";

    /* ===== 注册信息 key（平台端约定） ===== */

    public static final String INFO_HOST = "host";
    public static final String INFO_TCP_PORT = "tcp_port";
    public static final String INFO_ROLE = "role";
    public static final String INFO_VERSION = "version";
    public static final String INFO_RUN_ID = "run_id";
    public static final String INFO_MASTER_HOST = "master_host";
    public static final String INFO_MASTER_PORT = "master_port";

    /* ===== Sentinel 采集 ===== */

    /** 未配置 service-name 时的默认主从组名 */
    public static final String DEFAULT_SENTINEL_MASTER_NAME = "mymaster1";
    /** 报表节点名前缀 */
    public static final String SENTINEL_NODE_NAME_PREFIX = "sentinel-";
    /** 平台端单次最多接收的哨兵节点数 */
    public static final int MAX_SENTINEL_NODES = 50;
    /** 平台端 sentinel_node_info.node_ip 字段长度 */
    public static final int MAX_NODE_IP_LENGTH = 20;
    /** 平台端 nodeName/masterName 字段长度 */
    public static final int MAX_NODE_NAME_LENGTH = 64;

    /** 哨兵在线 */
    public static final String SENTINEL_STATUS_ONLINE = "1";
    /** 哨兵离线 */
    public static final String SENTINEL_STATUS_OFFLINE = "0";
    /** 哨兵离线标记 */
    public static final String SENTINEL_FLAG_S_DOWN = "s_down";
    public static final String SENTINEL_FLAG_O_DOWN = "o_down";
    public static final String SENTINEL_FLAG_DISCONNECTED = "disconnected";

    /** 合法端口区间 */
    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;

    /** 平台 host 字段字符集 */
    public static final String HOST_PATTERN = "^[A-Za-z0-9.:\\-_\\[\\]]+$";
    /** 平台 host 字段长度 */
    public static final int MAX_HOST_LENGTH = 255;
}
