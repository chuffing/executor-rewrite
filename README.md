# Redis 执行器部署与人工准备说明

本文档只描述部署人员需要提前完成的工作。执行器启动后自动注册、接收任务和上报结果的流程不需要人工干预。

## 一、部署原则

三台服务器可以使用同一个执行器 JAR，但每台服务器必须准备各自的外置 `application.yml`。

以下内容允许每台服务器不同：

- 执行器名称。
- Redis、Sentinel 的连接地址、端口和密码。
- Redis、Sentinel 的服务名称。
- Redis 数据目录、正式配置文件路径和备份文件路径。
- Redis 版本安装目录和 `current` 软链接路径。

建议把“同一个 JAR + 本机 application.yml”作为一台服务器的完整执行器部署包，不要为了修改配置重新编译 JAR。

## 二、服务器基础要求

每台执行器服务器需要提前满足以下条件：

- Linux 操作系统。
- Bash 4 或以上版本。
- Java 8。
- 能访问平台服务地址。
- 平台能够访问执行器监听端口，默认端口为 `9101`。
- 执行器运行账户对本文列出的工作目录具有相应读写权限。
- 系统具有 `bash`、`tar`、`gzip`、`awk`、`readlink`、`stat`、`head`、`od`、`chmod`、`cp`、`mv`、`ln`、`mkdir`、`mktemp`、`rm`、`rmdir`、`timeout` 和 `grep` 等命令。
- 需要在本机编译 Redis 源码时，还必须安装 `make`、`cc` 等编译工具。
- `tar` 必须是 GNU tar。
- `redis-cli` 必须存在，并且执行器运行账户可以执行。

## 三、推荐目录结构

以下目录只是推荐值。实际路径可以不同，但必须与本机 `application.yml` 和启动环境变量保持一致。

```text
/opt/redis/
├── agent/
│   ├── redis-monitor-agent.jar
│   ├── application.yml
│   ├── agent-token.dat             # 首次注册成功后自动生成
│   └── task-results/               # 未成功上报的任务结果
├── scripts/
│   ├── precheck-host.sh
│   ├── deploy-version.sh
│   ├── switch-version.sh
│   ├── stop-redis.sh
│   ├── start-redis.sh
│   ├── stop-sentinel.sh
│   └── start-sentinel.sh
├── packages/                       # 平台下发的Redis源码包暂存根目录
│   └── staging/                    # 执行器按任务自动创建
├── configfiles/                    # 平台下发的配置文件暂存根目录
│   └── staging/                    # 执行器按任务自动创建
├── versions/                       # Redis正式版本安装根目录
│   └── 7.2.4/
│       └── bin/
│           ├── redis-server
│           ├── redis-cli
│           └── redis-sentinel -> redis-server
├── current -> /opt/redis/versions/7.2.4
├── conf/
│   ├── redis.conf
│   └── sentinel.conf
├── backup/                        # 不提供给Redis和Sentinel直接使用的可信配置副本
│   ├── redis.conf
│   └── sentinel.conf
└── data/
```

人工需要提前创建的是各个根目录：

```bash
mkdir -p /opt/redis/agent
mkdir -p /opt/redis/scripts
mkdir -p /opt/redis/packages
mkdir -p /opt/redis/configfiles
mkdir -p /opt/redis/versions
mkdir -p /opt/redis/conf
mkdir -p /opt/redis/backup
mkdir -p /opt/redis/data
```

以下目录由执行器按任务自动创建，不需要人工提前创建：

- `/opt/redis/packages/staging/` 下的任务目录。
- `/opt/redis/configfiles/staging/` 下的任务目录。

`/opt/redis/conf` 保存运行中的正式配置，`/opt/redis/backup` 保存故障恢复使用的可信配置副本，两者不能使用同一目录。

## 四、复制执行器和固定脚本

将构建好的执行器 JAR 放到：

```text
/opt/redis/agent/redis-monitor-agent.jar
```

将项目 `scripts` 目录中的七个生产脚本原样复制到：

```text
/opt/redis/scripts/
```

建议脚本仅允许执行器运行账户修改和执行：

```bash
chmod 700 /opt/redis/scripts/*.sh
```

不要在服务器上临时修改单个脚本。三台服务器应当使用同一版本的脚本，防止相同任务在不同服务器上产生不同结果。

## 五、为每台服务器准备外置 application.yml

不要只使用 JAR 内部的默认配置。每台服务器都应单独准备：

```text
/opt/redis/agent/application.yml
```

三台服务器的文件路径可以相同，但文件内容按照本机实际情况分别填写。例如：

```yaml
server:
  port: 9101

agent:
  # 建议每台服务器使用清晰且唯一的名称。
  agent-name: redis-agent-10.0.0.1

  # 本机对平台可见的 IPv4，留空自动识别；多网卡建议显式配置。
  host-ip: 10.0.0.1

  # 未注册/已注册未绑定时的二次注册重试间隔（毫秒）。
  register-retry-interval: 30000

  platform:
    url: http://10.0.0.100:8100
    register-code: 请填写平台提供的注册码
    token-file: /opt/redis/agent/agent-token.dat

  # 采集数据本地留存目录：上报失败时缓存，平台恢复后自动重传。
  data-dir: /opt/redis/agent/data
  data-retain-days: 7

  schedule:
    collect-interval: 300000
    heartbeat-interval: 30000
    register-interval: 30000

  task-result:
    directory: /opt/redis/agent/task-results

  redis:
    host: 127.0.0.1
    port: 6379
    password: 请填写本机Redis密码
    db: 0
    timeout: 2000
    service-name: 请填写本机Redis服务名称
    config-file: /opt/redis/conf/redis.conf
    # 启动脚本允许创建运行目录和日志目录的受控根目录
    data-dir: /opt/redis/data
    log-dir: /opt/redis/logs
    # 在PATH中可直接写redis-cli，否则填写绝对路径
    cli-bin: redis-cli
    backup-file: /opt/redis/backup/redis.conf

  sentinel:
    # 是否随指标采集并上报 Sentinel 节点（默认 true）
    enabled: true
    host: 127.0.0.1
    port: 26379
    password: 请填写本机Sentinel密码
    timeout: 2000
    service-name: mymaster
    # 可选：多哨兵地址，任一台可达即可发现全部哨兵；为空时只用 host:port
    # nodes: 10.0.0.1:26379,10.0.0.2:26379,10.0.0.3:26379
    config-file: /opt/redis/conf/sentinel.conf
    data-dir: /opt/redis/data
    log-dir: /opt/redis/logs
    backup-file: /opt/redis/backup/sentinel.conf

  operation:
    script-dir: /opt/redis/scripts
    package-dir: /opt/redis/packages
    config-file-dir: /opt/redis/configfiles
    install-dir: /opt/redis/versions
    current-dir: /opt/redis/current
    min-free-space-bytes: 2147483648
```

注意：平台注册接口要求执行器上传 `redisReady`，其含义是“本机 Redis 当前是否能正常连接”，执行器按以下规则判定：

- 每轮注册前对本机 Redis 连续 `PING` 3 次，全部返回 `PONG` 才视为就绪；
- 未就绪：上传 `redisReady=false` 且不携带 `redisInfo`，平台只注册执行器，不创建/绑定 Redis 实例；
- 就绪：上传 `redisReady=true` 和 `redisInfo`（host/tcp_port/role/version/run_id/master_host/master_port），平台创建或复用 `redis_instance` 并回写 `instanceId`；
- 已拿到 token 但 `bound=false` 时，注册任务每 30s 自动二次注册，Redis 恢复后无需重启执行器；
- 已绑定后，`isRegistered && isBound` 直接返回，不再请求平台注册接口。

`redisReady` 由执行器根据 Redis 实际连通性实时计算，不需要在配置文件中人工设置。新服务器完成 Redis 部署并启动后，定时注册任务会自动探测并完成实例绑定。

## 六、注册与数据采集行为（与平台接口对齐）

执行器主动调用的平台接口：

| 用途 | 方法/路径 | 说明 |
| --- | --- | --- |
| 注册/二次注册 | `POST /agent/register` | body：registerCode、hostIp、executorPort、redisReady、redisInfo；返回 executorToken/instanceId/bound |
| 心跳 | `POST /agent/heartbeat` | `Authorization: Bearer <token>`；body：hostIp、executorPort；平台同时刷新绑定实例心跳 |
| ping 平台 | `GET /agent/ping` | 采集上报前连续调用 3 次，取平均 RTT 作为 `pingLatency` |
| 指标上报 | `POST /metric/report` | body 为指标对象本身，token 在 body 中；一次请求同时提交 Redis 指标、pingLatency、sentinels |

采集内容：

| 采集项 | 实现 | 平台落库 |
| --- | --- | --- |
| Redis 指标 | `INFO all`（内存/网络/QPS/commandstats/CPU/replication） | `rms_metric_log` |
| 延迟口径 | commandstats calls/usec 相邻周期差值，单位 **μs** | `rms_metric_log.latency` |
| Redis 内存 | `used_memory / 1024^3`，单位 **GB**，保留 3 位小数 | `rms_metric_log.mem_us` |
| 主从复制延迟 | 从节点 `master_last_io_seconds_ago`，主节点 0，单位 **s** | `rms_metric_log.repl_delay` |
| 执行器 ping 平台 | `GET /agent/ping` 3 次平均，单位 **ms** | `rms_node_status_log.ping_latency` |
| Sentinel 节点 | `SENTINEL sentinels <service-name>`，按 ip:port 去重 | `sentinel_node_info`（随指标 upsert） |

采集上报流程：先重传本地积压数据 → 采集 Redis → 采集 ping 延迟 → 采集 Sentinel →
本地留存 → 一次 `/metric/report` 上报 → 成功后删除本地文件。上报失败保留文件，
默认每 5 分钟重试，最多每轮重传 50 条；token 失效时自动清空本地凭证并重新注册。

平台手动检测会调用执行器 `POST /api/collect`；执行器只返回本次采集报文，
由平台回填 token、`collectType=1` 并写库，执行器不重复上报。

## 七、脚本配置由执行器自动注入

`application.yml` 是执行器和Shell脚本的唯一配置源。Java读取并校验配置后，
在启动Redis或Sentinel脚本时通过 `ProcessBuilder` 把对应配置写入本次子进程环境。

服务器不需要执行 `export`，也不需要在 `/etc/profile`、systemd的 `Environment=`
或 `EnvironmentFile=` 中重复维护Redis和Sentinel配置。

Redis和Sentinel密码、端口、正式配置文件、受控数据/日志根目录以及 `redis-cli`
位置全部填写在外置 `application.yml`。该文件包含密码，必须限制为执行器运行账户可读，
建议权限为 `600`。

## 八、首次纳管现有三台生产服务器

首次纳管的前提是三台 Redis 和三台 Sentinel 已经正常运行。

每台生产服务器需要人工完成：

1. 复制同一个执行器 JAR 和七个固定脚本。
2. 创建本机所需目录并设置执行器账户权限。
3. 准备本机独立的外置 `application.yml`。
4. 填写本机真实的 Redis、Sentinel 地址、端口、密码和服务名称。
5. 填写平台地址和平台注册码。
6. 启动执行器，确认执行器注册、心跳和数据采集正常。

平台服务器还需要人工保存三台生产服务器当前使用的配置文件。平台默认目录为：

```text
./data/nodeConfigs/current/
├── 10.0.0.1/
│   ├── redis.conf
│   └── sentinel.conf
├── 10.0.0.2/
│   ├── redis.conf
│   └── sentinel.conf
└── 10.0.0.3/
    ├── redis.conf
    └── sentinel.conf
```

平台根目录可通过环境变量 `PLATFORM_NODE_CONFIG_DIR` 修改。这里保存的是平台侧副本，不是执行器服务器上的正式配置文件。

三台执行器都完成注册和数据采集后，前端查询首次纳管候选节点，选择三个服务器 IP 并提交。首次纳管不要求用户指定主节点和从节点顺序。

## 九、服务器切换前准备新服务器

用于服务器切换的新服务器默认没有 Redis 和 Sentinel。

每台新服务器需要人工完成：

1. 复制同一个执行器 JAR 和七个固定脚本。
2. 创建本机所需目录并设置执行器账户权限。
3. 准备本机独立的外置 `application.yml`。
4. 提前填写未来 Redis 和 Sentinel 将使用的端口、密码、配置路径及服务名称。
5. 配置平台地址、注册码和本地 Token 保存路径。
6. 启动执行器并确认注册、心跳正常。此时没有 Redis，执行器只注册自身，不应采集 Redis 指标。

平台操作人员需要另外完成：

1. 在平台上传并启用目标 Redis 源码安装包。
2. 在平台的 `staging` 目录中，按照目标服务器 IP 准备六份新配置。
3. 明确本次切换的主节点 IP；主节点的 `redis.conf` 不配置生效的 `replicaof`。
4. 两台从节点的 `redis.conf` 必须指向本次选择的主节点。
5. 三份 `sentinel.conf` 必须使用相同名称监控本次选择的主节点。
6. 确认三台新执行器均处于在线、未纳管状态后，再从前端提交服务器切换任务。

平台默认的待切换配置目录如下：

```text
./data/nodeConfigs/staging/
├── 10.0.1.1/
│   ├── redis.conf
│   └── sentinel.conf
├── 10.0.1.2/
│   ├── redis.conf
│   └── sentinel.conf
└── 10.0.1.3/
    ├── redis.conf
    └── sentinel.conf
```

服务器切换任务创建时，平台计算六份文件的 SHA-256 并保存到任务参数中。配置下发时直接读取 `staging/{IP}`，执行器接收后再次校验 SHA-256。

服务器切换成功后，平台会把三台新服务器的配置更新到 `current/{IP}`。平台不再创建 `tasks/{taskNo}` 配置快照目录。

## 十、正式配置文件和 current 软链接

### 已有生产服务器

首次纳管只要求执行器能够连接现有 Redis 和 Sentinel。正式配置文件应当与外置 YAML 中的路径一致。

首次纳管不会执行 `DEPLOY_CONFIG`，因此需要人工将平台为本机 IP 准备的可信 `redis.conf` 和 `sentinel.conf` 分别复制到：

```text
/opt/redis/backup/redis.conf
/opt/redis/backup/sentinel.conf
```

这两份备份必须与平台 `current/{本机IP}` 目录中的配置内容一致。平台首次纳管时会根据该目录中的配置计算并保存 SHA-256，后续 `RESTORE_CONFIG` 会使用这个摘要校验执行器本地备份。

如果后续需要使用平台执行版本切换，则必须保证：

- `/opt/redis/current` 是软链接，不能是普通目录。
- 软链接目标位于 `/opt/redis/versions/{version}`。
- 版本目录中存在可执行的 `bin/redis-server`、`bin/redis-cli`。
- `bin/redis-sentinel` 是指向 `redis-server` 的有效软链接。

### 新服务器

新服务器允许以下内容暂时不存在：

- `/opt/redis/conf/redis.conf`。
- `/opt/redis/conf/sentinel.conf`。
- `/opt/redis/current` 软链接。

但是它们的父目录必须提前存在并且执行器账户可写。服务器切换任务会部署版本、下发配置并创建 `current` 软链接。

## 十一、权限和安全要求

- 执行器 JAR、外置配置文件、脚本和工作目录应由同一个受控账户管理。
- 外置配置文件包含 Redis 和 Sentinel 密码，不应允许其他普通账户读取。
- `agent-token.dat` 是执行器身份凭证，不得在三台服务器之间复制或共用。
- 删除本地 Token 文件会触发执行器重新注册，非必要不要删除。
- Redis 源码包暂存目录和正式版本安装目录不能允许其他普通账户写入。
- Sentinel 会重写 `sentinel.conf`，因此正式 Sentinel 配置文件必须允许执行器运行账户读写。
- 正式配置目录和故障恢复备份目录不能使用同一目录。

## 十二、启动前人工检查清单

启动每台执行器前逐项确认：

- [ ] 本机使用的是正确的外置 `application.yml`。
- [ ] `agent-name` 能够区分当前服务器。
- [ ] 平台地址和注册码正确。
- [ ] Token 文件路径可写，且没有复制其他服务器的 Token。
- [ ] Redis、Sentinel 地址、端口、密码和服务名称正确。
- [ ] Redis、Sentinel 正式配置路径正确。
- [ ] 七个固定脚本已经复制完成并且可读。
- [ ] 包暂存目录、配置暂存目录、版本目录、数据目录已经创建。
- [ ] 正式配置目录和备份目录已经创建且不是同一目录，权限正确。
- [ ] `application.yml` 已填写Redis/Sentinel完整配置且权限为 `600`。
- [ ] 执行器端口和平台之间的网络访问正常。
- [ ] 版本安装目录所在文件系统的可用空间不少于配置要求。

## 十三、启动示例

不需要预设Redis或Sentinel环境变量，直接使用外置配置启动：

```bash
cd /opt/redis/agent
java -jar redis-monitor-agent.jar \
  --spring.config.additional-location=file:/opt/redis/agent/application.yml
```

生产环境建议使用 systemd 等服务管理方式启动，并明确设置运行账户、工作目录和自动重启策略。
