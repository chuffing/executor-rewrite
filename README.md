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
- `bash`、`tar`、`gzip`；
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

执行器调用脚本启停 Redis 和 Sentinel，因此运行账户必须有权：

- 读取六个固定脚本；
- 写入 `/opt/redis` 下的运维目录；
- 读写正式配置和备份配置；
- 执行 Redis 版本目录中的二进制文件；
- 写入 Redis/Sentinel 的数据、日志和 PID 文件目录。

如果现有生产 Redis 已经由专用账户运行，优先让执行器使用同一个账户。本文示例账户为 `redisops`。若账户已存在，跳过：

```bash
sudo useradd --system --home-dir /opt/redis --shell /sbin/nologin redisops
```

不要在没有核对现有 Redis 权限的情况下，直接递归修改系统目录或现有生产数据目录的属主。

## 4. 一次性创建全部目录

以下命令复制执行一次，不需要人工逐个创建：

```bash
sudo install -d -o redisops -g redisops -m 0750 /opt/redis
sudo install -d -o redisops -g redisops -m 0750 /opt/redis/agent
sudo install -d -o redisops -g redisops -m 0750 /opt/redis/agent/data
sudo install -d -o redisops -g redisops -m 0750 /opt/redis/agent/logs
sudo install -d -o redisops -g redisops -m 0750 /opt/redis/agent/task-results
sudo install -d -o redisops -g redisops -m 0750 /opt/redis/scripts
sudo install -d -o redisops -g redisops -m 0750 /opt/redis/packages
sudo install -d -o redisops -g redisops -m 0750 /opt/redis/configfiles
sudo install -d -o redisops -g redisops -m 0750 /opt/redis/versions
sudo install -d -o redisops -g redisops -m 0750 /opt/redis/conf
sudo install -d -o redisops -g redisops -m 0750 /opt/redis/backup
sudo install -d -o redisops -g redisops -m 0750 /opt/redis/data
sudo install -d -o redisops -g redisops -m 0750 /opt/redis/logs
```

执行器启动时会严格校验：

- `scripts` 必须存在且可访问；
- `packages`、`configfiles`、`versions` 必须存在且可读、可写、可访问；
- `current` 的父目录必须存在且可写；
- 正式配置和备份配置的父目录必须存在且可写；
- Redis 正式配置与备份不能位于同一目录；
- Sentinel 正式配置与备份不能位于同一目录。

## 5. 上传 JAR 和六个脚本

从构建机器上传，下面的 `redis-server` 替换为目标服务器地址：

```bash
scp target/redis-monitor-agent.jar redis-server:/tmp/
scp scripts/deploy-version.sh redis-server:/tmp/
scp scripts/switch-version.sh redis-server:/tmp/
scp scripts/start-redis.sh redis-server:/tmp/
scp scripts/stop-redis.sh redis-server:/tmp/
scp scripts/start-sentinel.sh redis-server:/tmp/
scp scripts/stop-sentinel.sh redis-server:/tmp/
```

在目标服务器安装：

```bash
sudo install -o redisops -g redisops -m 0640 /tmp/redis-monitor-agent.jar /opt/redis/agent/redis-monitor-agent.jar

sudo install -o redisops -g redisops -m 0750 /tmp/deploy-version.sh /opt/redis/scripts/deploy-version.sh
sudo install -o redisops -g redisops -m 0750 /tmp/switch-version.sh /opt/redis/scripts/switch-version.sh
sudo install -o redisops -g redisops -m 0750 /tmp/start-redis.sh /opt/redis/scripts/start-redis.sh
sudo install -o redisops -g redisops -m 0750 /tmp/stop-redis.sh /opt/redis/scripts/stop-redis.sh
sudo install -o redisops -g redisops -m 0750 /tmp/start-sentinel.sh /opt/redis/scripts/start-sentinel.sh
sudo install -o redisops -g redisops -m 0750 /tmp/stop-sentinel.sh /opt/redis/scripts/stop-sentinel.sh
```

当前只有以下六个脚本，不存在 `precheck-host.sh`：

```text
deploy-version.sh
switch-version.sh
start-redis.sh
stop-redis.sh
start-sentinel.sh
stop-sentinel.sh
```

如果脚本从 Windows 机器传来，先移除 CRLF：

```bash
sudo sed -i 's/\r$//' /opt/redis/scripts/*.sh
sudo chmod 750 /opt/redis/scripts/*.sh
```

检查：

```bash
ls -l /opt/redis/scripts
file /opt/redis/scripts/*.sh
```

## 6. 创建外置 application.yml

```bash
sudo touch /opt/redis/agent/application.yml
sudo chown redisops:redisops /opt/redis/agent/application.yml
sudo chmod 600 /opt/redis/agent/application.yml
sudo vi /opt/redis/agent/application.yml
```

写入以下内容，并替换所有尖括号占位符：

```yaml
server:
  port: 9101
  address: 0.0.0.0

spring:
  application:
    name: redis-monitor-agent

agent:
  platform:
    url: http://<平台IP>:8100
    register-code: <与平台完全一致的注册码>
    token-file: /opt/redis/agent/agent-token.dat

  agent-name: <能够区分本机的名称>
  host-ip: <本机供平台访问的IPv4>

  data-dir: /opt/redis/agent/data
  data-retain-days: 7
  log-dir: /opt/redis/agent/logs

  task-result:
    directory: /opt/redis/agent/task-results

  redis:
    host: 127.0.0.1
    port: 6379
    password: <Redis密码>
    db: 0
    timeout: 2000
    config-file: /opt/redis/conf/redis.conf
    data-dir: /opt/redis/data
    log-dir: /opt/redis/logs
    cli-bin: /opt/redis/current/bin/redis-cli
    backup-file: /opt/redis/backup/redis.conf

  sentinel:
    enabled: true
    host: 127.0.0.1
    port: 26379
    password: <Sentinel密码>
    service-name: <Sentinel监控的主节点名称，例如mymaster>
    nodes:
      - <哨兵1的IP>:26379
      - <哨兵2的IP>:26379
      - <哨兵3的IP>:26379
    timeout: 2000
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
    script-timeout-seconds: 10
    version-deploy-timeout-seconds: 1800
    stop-check-timeout-seconds: 10
    stop-check-interval-millis: 1000
    start-check-timeout-seconds: 30
    start-check-interval-millis: 1000
    topology-check-timeout-seconds: 60
    topology-check-interval-millis: 5000

logging:
  level:
    com.icbc.nbfh: INFO
```

注意：

- 当前代码要求 Redis 和 Sentinel 密码非空；
- `host-ip` 必须是平台能够访问的本机 IP，不要误填 `127.0.0.1`；
- 每台服务器使用自己的 Token 文件，禁止复制其他服务器的 `agent-token.dat`；
- 所有运维路径必须是干净的绝对路径，不能带首尾空格、`.` 或 `..`；
- `agent.redis.cli-bin` 必须是绝对路径，不能只写 `redis-cli`；
- 正式配置目录 `/opt/redis/conf` 与备份目录 `/opt/redis/backup` 必须不同；
- 配置文件含密码，权限保持为 `600`。

## 7. 场景一：首次纳管已有生产 Redis

本场景要求 Redis 和 Sentinel 当前已经正常运行。

### 7.1 准备正式配置

将本机当前真实生效的 Redis 和 Sentinel 配置复制到标准目录：

```bash
sudo cp <当前Redis配置文件> /opt/redis/conf/redis.conf
sudo cp <当前Sentinel配置文件> /opt/redis/conf/sentinel.conf
sudo chown redisops:redisops /opt/redis/conf/redis.conf /opt/redis/conf/sentinel.conf
sudo chmod 640 /opt/redis/conf/redis.conf /opt/redis/conf/sentinel.conf
```

复制前必须确认文件内容与当前生产实例一致。以后平台执行启动操作时会使用这里配置的文件。

### 7.2 准备可信备份

```bash
sudo cp /opt/redis/conf/redis.conf /opt/redis/backup/redis.conf
sudo cp /opt/redis/conf/sentinel.conf /opt/redis/backup/sentinel.conf
sudo chown redisops:redisops /opt/redis/backup/redis.conf /opt/redis/backup/sentinel.conf
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
sudo chown -h redisops:redisops /opt/redis/current
```

验证：

```bash
readlink -f /opt/redis/current
sudo -u redisops test -x /opt/redis/current/bin/redis-server
sudo -u redisops test -x /opt/redis/current/bin/redis-cli
sudo -u redisops /opt/redis/current/bin/redis-cli --version
```

`application.yml` 中始终保持：

```yaml
agent:
  redis:
    cli-bin: /opt/redis/current/bin/redis-cli
```

不要把具体版本号直接写进 `cli-bin`。后续升级只切换 `current` 软链接，配置无需修改。

## 8. 场景二：准备没有 Redis 的新服务器

新服务器等待以后执行服务器切换时：

- 不需要提前安装 Redis；
- 不需要提前创建 `/opt/redis/current`；
- 不需要提前创建正式 Redis/Sentinel 配置文件；
- `/opt/redis/conf`、`/opt/redis/backup` 和 `/opt/redis` 必须已经存在并可写；
- `agent.redis.cli-bin` 仍配置 `/opt/redis/current/bin/redis-cli`；
- 启动阶段允许 `redis-cli` 文件不存在；
- 真正执行需要 CLI 的运维任务时，会动态检查文件存在、是普通文件且可执行。

执行器启动后应只完成注册和心跳，不应产生 Redis 实例绑定。版本、配置和 `current` 软链接会在服务器切换任务中创建。

## 9. 配置 systemd

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
User=redisops
Group=redisops
WorkingDirectory=/opt/redis/agent
ExecStart=/usr/bin/java -jar /opt/redis/agent/redis-monitor-agent.jar --spring.config.additional-location=file:/opt/redis/agent/application.yml
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

若 `command -v java` 不是 `/usr/bin/java`，修改 `ExecStart`。

加载并启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now redis-monitor-agent
sudo systemctl status redis-monitor-agent --no-pager
```

查看日志：

```bash
sudo journalctl -u redis-monitor-agent -f
```

## 10. 启动验收

### 10.1 检查端口

```bash
ss -lntp | grep 9101
```

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
sudo -u redisops test -r /opt/redis/scripts
sudo -u redisops test -x /opt/redis/scripts
sudo -u redisops test -w /opt/redis/packages
sudo -u redisops test -w /opt/redis/configfiles
sudo -u redisops test -w /opt/redis/versions
sudo -u redisops test -w /opt/redis/conf
sudo -u redisops test -w /opt/redis/backup
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
sudo install -o redisops -g redisops -m 0640 \
  /tmp/redis-monitor-agent.jar \
  /opt/redis/agent/redis-monitor-agent.jar
sudo systemctl start redis-monitor-agent
sudo systemctl status redis-monitor-agent --no-pager
```

不要删除 `agent-token.dat`。删除后执行器会重新注册。

## 13. 常见启动失败

### 报错固定脚本不存在或不可读

确认 `/opt/redis/scripts` 中恰好准备了第 5 节列出的六个脚本，并检查权限和换行符。

### 报错路径必须是绝对路径

检查 `agent.operation.*`、Redis/Sentinel 配置文件、备份文件和 `agent.redis.cli-bin`，不能使用相对路径。

### 报错路径包含首尾空格、`.` 或 `..`

直接修正 `application.yml`。执行器不会自动修正错误配置。

### 报错目录不存在、不可读、不可写或不可访问

重新执行第 4 节的目录创建命令，并检查完整路径：

```bash
namei -l /opt/redis/versions
```

### 报错正式配置与备份配置位于同一目录

保持：

```text
正式配置：/opt/redis/conf
可信备份：/opt/redis/backup
```

### 运维任务报 redis-cli 不存在或不可执行

已有生产 Redis 的服务器检查：

```bash
readlink -f /opt/redis/current
ls -l /opt/redis/current/bin/redis-cli
sudo -u redisops test -x /opt/redis/current/bin/redis-cli
```

新服务器首次部署 Redis 前出现文件不存在是允许的，但在需要执行 CLI 的运维步骤前必须已经部署版本并建立 `current`。

### 执行器注册失败

检查平台地址、注册码、本机 `host-ip`、双向网络和 Token 文件父目录权限。不要复制其他服务器的 Token 文件。

## 14. 执行器投产检查清单

- [ ] Java 8、bash、gcc、make、tar、gzip 已安装；
- [ ] JAR 和六个脚本已安装；
- [ ] 六个脚本是 Unix 换行符且权限正确；
- [ ] `/opt/redis` 下全部父目录已经一次性创建；
- [ ] 外置 `application.yml` 已填写并设置为 `600`；
- [ ] 平台地址、注册码、本机 IP 和执行器端口正确；
- [ ] Redis/Sentinel 地址、密码和服务名称正确；
- [ ] 正式配置目录与备份目录不同；
- [ ] 每台服务器使用独立 Token 文件；
- [ ] systemd 服务已启用并正常运行；
- [ ] `9101` 端口已监听；
- [ ] 平台与执行器网络双向可达；
- [ ] 已有生产 Redis：正式配置、可信备份和 `current` 软链接已准备；
- [ ] 新服务器：没有提前创建假的 `current` 目录或 `redis-cli` 文件；
- [ ] 平台前端显示执行器状态符合当前部署场景。
