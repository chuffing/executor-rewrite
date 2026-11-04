# Redis 执行器：人工部署手册

本文档只说明 **Redis 执行器** 在 Redis 服务器上的人工部署。平台后端如何部署、建库和准备平台配置，请查看平台项目自己的 `README.md`。

每台 Redis 服务器部署一个执行器。下面以 Linux、systemd、Java 8 为例，统一使用：

```text
/opt/redis
```

执行器分为两种部署场景：

1. **首次纳管已有生产 Redis**：服务器上的 Redis 和 Sentinel 已经运行；
2. **准备一台新服务器**：服务器暂时没有 Redis，等待以后执行服务器切换。

请先完成通用步骤，再按照对应场景继续操作。

## 1. 部署前准备

每台执行器服务器需要：

- JDK 8；
- Maven 3.6 或更高版本（仅构建机器需要）；
- `bash`、`tar`、`gzip`、`systemctl`；
- `gcc`、`make` 等 Redis 源码编译工具；
- 至少 2 GiB 可用磁盘空间，或满足配置中的更高要求；
- 执行器能够访问平台端口，默认 `8100`；
- 平台能够访问执行器端口，默认 `9101`。

检查基础命令：

```bash
java -version
bash --version
gcc --version
make --version
tar --version
systemctl --version
```

Java 输出中应包含 Java 8。

## 2. 构建执行器 JAR

在构建机器进入执行器项目：

```bash
cd redis-monitor-agent-rewrite
mvn clean package
```

构建成功后得到：

```text
target/redis-monitor-agent.jar
```

正式投产前建议执行：

```bash
mvn test
```

## 3. 确定运行账户

执行器调用脚本，通过独立的 systemd 单元启停 Redis 和 Sentinel，因此运行账户必须有权：

- 读取六个固定脚本；
- 写入 `/opt/redis` 下的运维目录；
- 读写正式配置和备份配置；
- 执行 Redis 版本目录中的二进制文件；
- 写入 Redis/Sentinel 的数据、日志和 PID 文件目录；
- 执行 `systemctl enable/start/stop` 管理两个固定服务单元。

当前脚本直接调用 `systemctl`，本文统一让执行器以 `root` 运行。不要另外给普通账户配置宽泛的免密 `sudo`。Redis 和 Sentinel 由各自独立的 systemd 单元运行，与执行器不在同一个进程组；停止或更新执行器不会连带停止 Redis/Sentinel。

不要在没有核对现有 Redis 权限的情况下，递归修改现有生产数据目录的属主。

## 4. 创建执行器基础目录

人工只需要准备 JAR 所在的基础目录：

```bash
sudo install -d -o root -g root -m 0750 /opt/redis
sudo install -d -o root -g root -m 0750 /opt/redis/agent
```

`agent.operation.base-dir` 校验通过后，执行器会从该绝对根目录派生全部固定运维路径并自动：

- 创建 `scripts`、`packages`、`configfiles` 和 `versions`；
- 创建 `current`、正式配置及备份配置所需的父目录；
- 从 JAR 内释放六个固定脚本，并在升级 JAR 后同步更新；
- 自动安装两个固定的 Redis/Sentinel systemd 服务单元；
- 检查上述目录可读、可写、可访问；
- 使用 `conf` 保存正式配置，使用 `backup` 保存可信备份。

执行器不会创建假的 `current` 目录，也不会创建空的 Redis/Sentinel
正式配置文件。已有 Redis 的服务器仍需按照第 7 节准备配置和软链接。

## 5. 上传并安装 JAR

从构建机器上传，下面的 `redis-server` 替换为目标服务器地址：

```bash
scp target/redis-monitor-agent.jar redis-server:/tmp/
```

在目标服务器安装：

```bash
sudo install -o root -g root -m 0640 /tmp/redis-monitor-agent.jar /opt/redis/agent/redis-monitor-agent.jar
```

六个脚本和两个 systemd 服务单元已经打包在 JAR 中，不需要单独上传。
执行器首次启动时会释放以下脚本，不存在 `precheck-host.sh`：

```text
deploy-version.sh
switch-version.sh
start-redis.sh
stop-redis.sh
start-sentinel.sh
stop-sentinel.sh
```

## 6. 使用 JAR 内置配置

执行器统一读取 JAR 中的 `application.yml`，服务器上不再创建或维护外置
YAML。发布 JAR 前应确认项目内配置已经与目标环境一致，包括平台地址、
注册码、Redis/Sentinel 连接信息、服务名称及运维根目录规划。

`agent.host-ip` 保持为空时，执行器会在每台服务器上自动识别本机 IPv4；
平台以 `hostIp + executorPort` 区分执行器。Token 文件保存在各自服务器的
工作目录中，禁止在服务器之间复制 `agent-token.dat`。

固定运维路径只配置一次：

```yaml
agent:
  operation:
    base-dir: /opt/redis
```

根目录必须是无空白、无冗余分隔符、无 `.` 或 `..` 路径段的绝对路径，且不能是文件系统根目录。`scripts`、`packages`、`configfiles`、`versions`、`current`、`conf`、`backup` 以及稳定的 `redis-cli` 路径都由代码派生，不再分别配置。

`agent.redis.data-dir`、`agent.redis.log-dir` 及 Sentinel 对应配置仍保持独立，因为生产数据和日志可能位于其他磁盘。

如果以后连执行器 JAR 也从 `/opt/redis/agent` 迁移到新的根目录，还必须人工同步修改 `redis-monitor-agent.service` 的 `WorkingDirectory` 和 `ExecStart`。这是执行器自身的操作系统启动入口，无法由尚未启动的 JAR 自动迁移；Redis/Sentinel的两个服务单元则会在执行器启动后自动按新根目录更新。

## 7. 场景一：首次纳管已有生产 Redis

本场景要求 Redis 和 Sentinel 当前已经正常运行。

### 7.1 准备正式配置

将本机当前真实生效的 Redis 和 Sentinel 配置复制到标准目录：

```bash
sudo cp <当前Redis配置文件> /opt/redis/conf/redis.conf
sudo cp <当前Sentinel配置文件> /opt/redis/conf/sentinel.conf
sudo chown root:root /opt/redis/conf/redis.conf /opt/redis/conf/sentinel.conf
sudo chmod 640 /opt/redis/conf/redis.conf /opt/redis/conf/sentinel.conf
```

复制前必须确认文件内容与当前生产实例一致。以后平台执行启动操作时会使用这里配置的文件。

### 7.2 准备可信备份

```bash
sudo cp /opt/redis/conf/redis.conf /opt/redis/backup/redis.conf
sudo cp /opt/redis/conf/sentinel.conf /opt/redis/backup/sentinel.conf
sudo chown root:root /opt/redis/backup/redis.conf /opt/redis/backup/sentinel.conf
sudo chmod 640 /opt/redis/backup/redis.conf /opt/redis/backup/sentinel.conf
```

备份文件必须与平台为本机 IP 保存的可信配置完全一致。Sentinel 会改写正式 `sentinel.conf`，因此正式文件和备份文件不能是同一个文件。

### 7.3 建立 current 软链接

先找到当前 Redis 的实际安装根目录。该目录下面必须存在：

```text
bin/redis-server
bin/redis-cli
```

假设实际目录为 `/usr/local/redis-7.2.4`：

```bash
sudo ln -sfn /usr/local/redis-7.2.4 /opt/redis/current
sudo chown -h root:root /opt/redis/current
```

验证：

```bash
readlink -f /opt/redis/current
sudo test -x /opt/redis/current/bin/redis-server
sudo test -x /opt/redis/current/bin/redis-cli
/opt/redis/current/bin/redis-cli --version
```

JAR 内置配置中始终保持：

```yaml
agent:
  operation:
    base-dir: /opt/redis
```

Redis CLI 会自动派生为 `/opt/redis/current/bin/redis-cli`。不要把具体版本号写入其他配置；后续升级只切换 `current` 软链接。

## 8. 场景二：准备没有 Redis 的新服务器

新服务器等待以后执行服务器切换时：

- 不需要提前安装 Redis；
- 不需要提前创建 `/opt/redis/current`；
- 不需要提前创建正式 Redis/Sentinel 配置文件；
- 执行器会从 `agent.operation.base-dir` 自动创建 `conf`、`backup` 等受管目录；
- Redis CLI 固定派生为 `/opt/redis/current/bin/redis-cli`；
- 启动阶段允许 `redis-cli` 文件不存在；
- 真正执行需要 CLI 的运维任务时，会动态检查文件存在、是普通文件且可执行。

执行器启动后应只完成注册和心跳，不应产生 Redis 实例绑定。版本、配置和 `current` 软链接会在服务器切换任务中创建。

## 9. 配置三个独立的 systemd 服务

Redis、Sentinel 和执行器必须分别由三个 systemd 单元管理。这样停止、更新或卸载执行器时，只会结束执行器自己的 Java 进程，不会向 Redis/Sentinel 发送终止信号。

### 9.1 自动安装 JAR 内置的 Redis/Sentinel 服务单元

执行器以 `root` 启动时，会自动从 JAR 原子安装：

```text
/etc/systemd/system/redis-managed.service
/etc/systemd/system/sentinel-managed.service
```

文件固定为 `root:root`、权限 `0644`。只有内容发生变化时才会替换文件并执行 `systemctl daemon-reload`；内容相同时不会重复加载。安装阶段只准备服务定义，不会自动启动、停止或重启 Redis/Sentinel。安装目录不可写、JAR 缺少资源或 `daemon-reload` 失败时，执行器会给出明确错误并阻止启动。

当 `base-dir` 为 `/opt/redis` 时，服务单元会使用：

```text
/opt/redis/current/bin/redis-server
/opt/redis/current/bin/redis-sentinel
/opt/redis/conf/redis.conf
/opt/redis/conf/sentinel.conf
```

服务单元由模板根据已经校验的根目录生成。修改 `base-dir` 后，执行器会同步更新服务文件并执行 `daemon-reload`，但不会自动重启生产实例。Redis/Sentinel以前台模式运行，由 systemd 直接跟踪主进程；同一根目录内升级 Redis 只切换 `current` 软链接。

### 9.2 创建执行器服务单元

```bash
sudo vi /etc/systemd/system/redis-monitor-agent.service
```

写入：

```ini
[Unit]
Description=Redis Monitor Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
Group=root
WorkingDirectory=/opt/redis/agent
ExecStart=/usr/bin/java -jar /opt/redis/agent/redis-monitor-agent.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

若 `command -v java` 不是 `/usr/bin/java`，修改 `ExecStart`。

加载配置并只启动执行器：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now redis-monitor-agent
sudo systemctl status redis-monitor-agent --no-pager
```

不要为了隔离进程给执行器配置 `KillMode=process`。三个独立服务单元本身已经完成进程隔离，执行器保持 systemd 默认的进程组清理策略即可。

### 9.3 已有生产 Redis 的首次接管

如果 Redis/Sentinel 当前由人工命令或旧执行器启动，安装服务单元后不要直接执行 `systemctl start redis-managed sentinel-managed`，否则会重复启动并争抢端口。

第一次通过平台执行停止操作时，脚本会先正常关闭当前实例，再将对应 systemd 单元收敛为停止状态；随后第一次通过平台执行启动操作时，脚本会启用并启动独立服务单元。完成这次停启后，实例就由 systemd 正式接管。

如果脚本发现端口上的 Redis/Sentinel 正在运行，但相应服务单元不是 `active`，启动操作会明确报错并拒绝重复启动。应先安排维护窗口完成一次停止、启动，不要强制杀进程。

### 9.4 新服务器首次部署

新服务器启动执行器后会自动安装两个服务单元，但不会提前启用或启动它们。服务器切换任务准备好版本、配置和 `current` 后，启动脚本才会执行 `systemctl enable` 和 `systemctl start`。

查看日志：

```bash
sudo journalctl -u redis-monitor-agent -f
sudo journalctl -u redis-managed -f
sudo journalctl -u sentinel-managed -f
```

## 10. 启动验收

### 10.1 检查端口

```bash
ss -lntp | grep 9101
```

已由独立服务接管的生产节点还应检查：

```bash
sudo systemctl status redis-managed --no-pager
sudo systemctl status sentinel-managed --no-pager
sudo systemctl status redis-monitor-agent --no-pager
```

停止执行器不会影响另外两个服务。可在维护验证时执行：

```bash
sudo systemctl stop redis-monitor-agent
sudo systemctl is-active redis-managed
sudo systemctl is-active sentinel-managed
sudo systemctl start redis-monitor-agent
```

中间两条命令都应输出 `active`。

### 10.2 检查平台注册和心跳

查看执行器日志，确认没有以下错误：

- 注册码错误；
- 平台连接失败；
- Token 文件写入失败；
- 运维目录启动校验失败；
- Redis/Sentinel 密码或连接错误。

然后在平台前端确认执行器在线。

已有生产 Redis 的服务器还应确认：

- 执行器已经绑定本机 Redis 实例；
- Redis 指标能够正常上报；
- Sentinel 节点能够正常上报。

没有 Redis 的新服务器应确认：

- 执行器在线；
- 未绑定 Redis 实例；
- 日志中没有反复注册失败。

### 10.3 检查运维目录权限

```bash
sudo test -r /opt/redis/scripts
sudo test -x /opt/redis/scripts
sudo test -w /opt/redis/packages
sudo test -w /opt/redis/configfiles
sudo test -w /opt/redis/versions
sudo test -w /opt/redis/conf
sudo test -w /opt/redis/backup
```

没有输出且退出码为 `0` 即为通过。

## 11. 网络和防火墙

每台执行器至少需要：

- 出站访问平台 `8100`；
- 入站允许平台访问执行器 `9101`；
- 访问本机 Redis `6379`；
- 访问配置中的 Sentinel 端口，默认 `26379`。

只向平台服务器和必要网段开放 `9101`，不要直接对公网开放。

## 12. 更新执行器版本

先备份旧 JAR：

```bash
sudo cp /opt/redis/agent/redis-monitor-agent.jar \
  /opt/redis/agent/redis-monitor-agent.jar.bak
```

停止、替换并启动：

```bash
sudo systemctl stop redis-monitor-agent
sudo install -o root -g root -m 0640 \
  /tmp/redis-monitor-agent.jar \
  /opt/redis/agent/redis-monitor-agent.jar

sudo systemctl start redis-monitor-agent
sudo systemctl status redis-monitor-agent --no-pager
```

执行器启动时会自动同步两个服务单元。更新执行器 JAR 和重新加载服务单元不会停止 Redis/Sentinel。不要删除 `agent-token.dat`，删除后执行器会重新注册。

## 13. 常见启动失败

### 报错固定脚本不存在或不可读

确认执行器账户能够写入 `agent.operation.base-dir` 派生的 `scripts` 目录。脚本由 JAR 自动释放，
如果仍然缺失，还需要检查 JAR 中是否包含 `scripts/*.sh`。

### 报错 systemd 服务单元无法安装或 daemon-reload 失败

确认服务器使用 systemd、执行器服务配置为 `User=root`，并检查：

```bash
ls -ld /etc/systemd/system
systemctl --version
sudo systemctl daemon-reload
```

执行器只会管理 `redis-managed.service` 和 `sentinel-managed.service` 两个固定文件，不会根据外部配置写入其他系统路径。

### 报错路径必须是绝对路径

检查 `agent.operation.base-dir`，不能使用相对路径。

### 报错路径包含首尾空格、`.` 或 `..`

修正项目内置的 `application.yml` 并重新构建 JAR。执行器不会自动修正错误配置。

### 报错目录不存在、不可读、不可写或不可访问

重新执行第 4 节的目录创建命令，并检查完整路径：

```bash
namei -l /opt/redis/versions
```

### 运维任务报 redis-cli 不存在或不可执行

已有生产 Redis 的服务器检查：

```bash
readlink -f /opt/redis/current
ls -l /opt/redis/current/bin/redis-cli
sudo test -x /opt/redis/current/bin/redis-cli
```

新服务器首次部署 Redis 前出现文件不存在是允许的，但在需要执行 CLI 的运维步骤前必须已经部署版本并建立 `current`。

### 报错 Redis 或 Sentinel 已运行但不属于独立服务单元

说明端口上的实例仍是人工命令或旧执行器启动的进程。不要再次启动。安排维护窗口，先通过平台执行一次停止，再执行一次启动，让 `redis-managed.service` 和 `sentinel-managed.service` 正式接管。

排查命令：

```bash
sudo systemctl is-active redis-managed
sudo systemctl is-active sentinel-managed
ps -eo pid,ppid,user,args | grep -E '[r]edis-server|[r]edis-sentinel'
```

### 执行器注册失败

检查平台地址、注册码、本机 `host-ip`、双向网络和 Token 文件父目录权限。不要复制其他服务器的 Token 文件。

## 14. 执行器投产检查清单

- [ ] Java 8、bash、gcc、make、tar、gzip、systemd 已安装；
- [ ] JAR 已安装，六个脚本已由 JAR 自动释放；
- [ ] `agent.operation.base-dir` 是经过核对的干净绝对路径；
- [ ] `/opt/redis` 和 `/opt/redis/agent` 基础目录权限正确；
- [ ] 受管运维目录已由执行器自动创建；
- [ ] JAR 内置配置已经与目标环境核对一致；
- [ ] 平台地址、注册码、本机 IP 和执行器端口正确；
- [ ] Redis/Sentinel 地址、密码和服务名称正确；
- [ ] 每台服务器使用独立 Token 文件；
- [ ] `redis-managed.service` 和 `sentinel-managed.service` 已由执行器自动安装；
- [ ] `redis-monitor-agent.service` 已启用并正常运行；
- [ ] 已有生产 Redis：维护窗口内完成一次停止、启动，由两个独立服务单元接管；
- [ ] 停止执行器后 Redis/Sentinel 仍保持 `active`；
- [ ] `9101` 端口已监听；
- [ ] 平台与执行器网络双向可达；
- [ ] 已有生产 Redis：正式配置、可信备份和 `current` 软链接已准备；
- [ ] 新服务器：没有提前创建假的 `current` 目录或 `redis-cli` 文件；
- [ ] 平台前端显示执行器状态符合当前部署场景。
