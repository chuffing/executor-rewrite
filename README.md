# redis-monitor-agent

Redis 运维执行器项目。

## 本地启动前的必要配置

配置通过环境变量传入，避免把环境地址、注册码和 Redis 密码写进代码仓库。

```text
AGENT_PLATFORM_URL=http://平台地址:端口
AGENT_REGISTER_CODE=平台注册码
AGENT_NAME=执行器名称
REDIS_HOST=Redis地址
REDIS_PORT=6379
REDIS_PASSWORD=Redis密码
SENTINEL_PASSWORD=Sentinel密码
```

`REDIS_PASSWORD` 和 `SENTINEL_PASSWORD` 必须配置，值不能为空或只包含空格。其他参数及默认值见 `src/main/resources/application.yml`。

## 当前代码范围

当前代码保留了同事项目的注册、心跳、指标采集和任务执行骨架。后续业务接口和参数校验按实际需求逐步手写，不在初始化阶段提前增加抽象层。

## Redis预编译运行包部署

执行器使用 `redis-{version}-runtime.tar.gz`，例如 `redis-6.2.19-runtime.tar.gz`。
包内只包含 `redis-6.2.19/bin/redis-server`、`redis-cli`、`redis-sentinel` 三个
Linux ELF普通可执行文件，不包含 `redis.conf`、`sentinel.conf` 等生产配置。
三个程序应由相同版本构建，并适配目标服务器的CPU、glibc及其他动态库环境。
平台负责上传业务校验，执行器接收层只校验非空、50 MiB上限和SHA256。
部署脚本以 `--version` 复核程序能否运行及实际版本，不启动服务。

在可信的离线构建/打包环境中，将程序复制成普通文件后打包。若 `redis-sentinel`
原本链接到 `redis-server`，使用 `cp -L` 复制其内容，不把软链接或硬链接放进归档。
三个文件均需执行权限。不得直接在生产执行器上编译或打包。

```text
redis-6.2.19/
└── bin/
    ├── redis-server
    ├── redis-cli
    └── redis-sentinel
```

执行器目录配置：

```text
AGENT_PACKAGE_DIR=/opt/redis/packages/
AGENT_INSTALL_DIR=/opt/redis/versions/
AGENT_CURRENT_DIR=/opt/redis/current
```

`packageDir`和`installDir`须事先存在，并仅允许执行器运行账户写入。
此处默认值只用于新代码配置，不迁移或覆盖服务器已有目录；请按实际环境设置。
旧的 `AGENT_UPGRADE_DIR` / `agent.operation.upgrade-dir` 已移除，须分别迁移到上述
包目录和安装目录配置。`currentDir`仅预留给后续切换，部署阶段不读取、不创建、不修改。

```text
/opt/redis/
├── packages/staging/TASK202610170001-随机值/package.tar.gz
├── versions/6.2.18/bin/...
├── versions/6.2.19/bin/...
└── current -> versions/6.2.18
```

`VersionPackageService`同步保存并校验摘要，`DeployVersionService`部署前再次核对摘要，
再通过固定脚本准备新版本。脚本参数为：安装包完整路径、目标版本、正式安装目录。
脚本不再读取packageDir，仍要求输入位于 `staging/TASK加12位数字-随机值/package.tar.gz`，
路径不能包含软链接，暂存文件和目录须由执行器账户拥有且不允许组或其他账户写入，
安装包不能是硬链接。该约束依赖独立的执行器运行账户，不构成同一账户内程序之间的隔离。
包服务清理仍以配置的packageDir为删除范围，脚本自身不删除输入安装包。
脚本放入 `AGENT_SCRIPT_DIR`，Linux主机需要 Bash 和 Python 3标准库，不需要make。
只在独立临时安装目录解压，拒绝路径穿越、归档链接、特殊文件、重复文件和生产配置；
最多100个归档条目、解压总大小1 GiB。三份程序版本校验通过后才发布目标版本。
已有目标版本（包括空目录及失效软链接）拒绝覆盖，普通失败及SIGTERM终止时清理本次产物。
不可捕获的SIGKILL、断电可能留下 `.deploy-*` 目录或锁，需确认没有运行中任务后人工检查。

部署不停止Redis或Sentinel、不运行make、不修改current和生产配置。实际平台升级顺序
及结果上报保持原有流程；本次没有修改平台代码。

## 后续SWITCH_VERSION衔接

当前四份启停脚本未修改。`start-redis.sh`通过 `REDIS_SERVER_BIN` 选取程序，
`start-sentinel.sh`通过 `SENTINEL_SERVER_BIN` 选取程序并传入 `--sentinel`；
两份停止脚本使用 `REDIS_CLI_BIN`。因此可以兼容current软链接，无需改Shell流程。
后续先核对运行账户环境，再配置：

```text
REDIS_SERVER_BIN=/opt/redis/current/bin/redis-server
SENTINEL_SERVER_BIN=/opt/redis/current/bin/redis-server
REDIS_CLI_BIN=/opt/redis/current/bin/redis-cli
```

配置文件路径仍指向生产原路径，不放到versions目录。自定义 `AGENT_CURRENT_DIR` 时，
上面三个程序路径也应同步调整；Java配置不会自动导出为Shell环境变量。
若使用PATH默认值或硬编码旧版本的绝对路径，切换current不会改变实际启动程序，
最小改动是调整这三个环境变量。

后续 `SWITCH_VERSION` 应在本机 `installDir/{targetVersion}/bin` 找到已验证的新版本，
检查current原目标、保存回滚目标，并由平台决定何时停止对应实例后再原子替换软链接。
部署成功仅代表版本已准备好，不能直接视为整个升级流程成功。本次不实现或执行切换。

## Redis和Sentinel启停脚本

将四个脚本放到 `AGENT_SCRIPT_DIR` 指定目录，执行器通过 `/bin/bash` 调用，无需任务参数。
启动脚本直接调用Redis程序并后台运行，停止脚本调用带认证的正常 `SHUTDOWN`，
Java负责后续健康检查。停止前会检查 `INFO server` 返回的服务类型，避免误停。

在启动Java执行器的同一个环境中配置，例如：

```bash
export AGENT_SCRIPT_DIR=/opt/redis-agent/scripts
export REDIS_SERVER_BIN=/opt/redis/current/bin/redis-server
export SENTINEL_SERVER_BIN=/opt/redis/current/bin/redis-server
export REDIS_CLI_BIN=/opt/redis/current/bin/redis-cli
export REDIS_CONFIG_FILE=/etc/redis/redis.conf
export SENTINEL_CONFIG_FILE=/etc/redis/sentinel.conf
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export SENTINEL_HOST=127.0.0.1
export SENTINEL_PORT=26379
```

`REDIS_PASSWORD`、`SENTINEL_PASSWORD` 使用已有的执行器环境变量，不要写进脚本。
Sentinel停止使用的是Sentinel自身认证密码，不是 `sentinel auth-pass` 的Redis主节点密码。
两个启动程序路径都默认从PATH查找 `redis-server`；Sentinel通过 `--sentinel` 启动。
`REDIS_CLI_BIN` 默认从PATH查找 `redis-cli`。

脚本继承Java进程环境变量，不会自动读取 `application.yml`。如果通过外部YAML覆盖了
配置路径、IP、端口或密码，也需同步配置上面的环境变量，保持二者一致。
实际监听地址、端口、密码、日志和数据目录由对应Redis/Sentinel配置文件决定。
运行账户应具备程序执行权限及日志/数据目录写权限，Sentinel配置文件还必须可写。
这套脚本直接管理Redis进程，不适用于同时由systemd负责启停的实例。
