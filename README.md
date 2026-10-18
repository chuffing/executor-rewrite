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
# REDIS_PASSWORD、SENTINEL_PASSWORD由受控环境注入，示例不列出密码值。
```

`REDIS_PASSWORD` 和 `SENTINEL_PASSWORD` 必须配置，值不能为空或只包含空格。其他参数及默认值见 `src/main/resources/application.yml`。

## 当前代码范围

当前保留注册、心跳、指标采集及异步任务执行机制，已实现主机预检查、Redis与Sentinel启停、配置部署、配置恢复、版本部署和版本软链接切换。

## Linux 服务器目录结构与配置说明

以下是统一的开发示例，**不代表银行生产服务器的实际路径**。已有有效目录不需要迁移；按服务器现状配置对应的绝对路径即可。源码项目目录、执行器JAR位置与Redis运行目录是不同概念，无需修改Java包结构，也不要求将JAR放在`/opt/redis`下。

### 目录结构及用途

```text
/opt/redis/
├── packages/                         # 安装包暂存根目录，提前准备
│   └── staging/                      # Java按需创建
│       └── TASK202610170001-随机字符/ # 版本包独立上传目录
│           └── package.tar.gz        # 校验通过的版本包
├── configfiles/                      # 配置文件暂存根目录，提前准备
│   └── staging/                      # Java按需创建
│       └── TASK202610170001-config-随机字符/
│           ├── redis.conf            # 配置上传的原始字节
│           └── sentinel.conf
├── versions/                         # 正式版本根目录，提前准备
│   ├── 6.2.18/
│   │   └── bin/
│   │       ├── redis-server
│   │       ├── redis-cli
│   │       └── redis-sentinel
│   └── 6.2.19/
│       └── bin/
│           ├── redis-server
│           ├── redis-cli
│           └── redis-sentinel
├── current -> /opt/redis/versions/6.2.19 # 当前启用版本，由SWITCH_VERSION创建或切换
├── scripts/                          # 提前放入仓库中真实存在的7个运维脚本
│   ├── start-redis.sh
│   ├── stop-redis.sh
│   ├── start-sentinel.sh
│   ├── stop-sentinel.sh
│   ├── deploy-version.sh
│   ├── switch-version.sh
│   └── precheck-host.sh
├── conf/                             # 独立于版本程序的生产配置目录
│   ├── redis.conf
│   └── sentinel.conf
└── data/                             # Redis数据目录
```

任务编号按当前接口要求为`TASK`加12位数字，`TASK001`仅能作为概念示例，不能用于实际请求。版本包上传期间文件名为`package.part`，完成大小与SHA256校验后才改名为`package.tar.gz`。原始上传文件名不会用作本地保存路径，独立暂存目录在部署结束或上传失败时按现有范围检查清理，不是历史安装包仓库。

版本目录中的三个程序来自平台提供的预编译运行包；`conf/`保存正式配置，`data/`保存运行数据。版本部署和版本切换均不覆盖这两个目录。`agent.redis.data-dir`只供Java预检查使用，不会自动写入`redis.conf`；Redis实际数据、日志和PID文件位置仍由生产配置决定。`sentinel.conf`允许Sentinel运行时自行重写，执行器部署及切换操作不会改写它。

### 配置项与目录对应关系

以下字段全部位于现有`agent`节点下，不创建第二份顶层`operation`、`redis`或`sentinel`节点。

| application.yml配置项 | 环境变量 | Linux开发示例 | 实际用途 |
| --- | --- | --- | --- |
| `agent.operation.package-dir` | `AGENT_PACKAGE_DIR` | `/opt/redis/packages` | Java接收安装包、检查磁盘及限定暂存清理范围 |
| `agent.operation.config-file-dir` | `AGENT_CONFIG_FILE_DIR` | `/opt/redis/configfiles` | 配置文件上传暂存根目录，限定保存、路径校验及清理范围 |
| `agent.operation.install-dir` | `AGENT_INSTALL_DIR` | `/opt/redis/versions` | 预检查、部署和切换脚本使用的正式版本根目录 |
| `agent.operation.script-dir` | `AGENT_SCRIPT_DIR` | `/opt/redis/scripts` | 固定脚本定位及主机预检查 |
| `agent.operation.current-dir` | `AGENT_CURRENT_DIR` | `/opt/redis/current` | 当前版本链接，Java传给启动及切换脚本 |
| `agent.redis.config-file` | `REDIS_CONFIG_FILE` | `/opt/redis/conf/redis.conf` | Java路径检查、配置恢复；环境变量同时供启动脚本读取 |
| `agent.redis.data-dir` | `REDIS_DATA_DIR` | `/opt/redis/data` | Java检查数据目录权限，应与Redis实际数据位置一致 |
| `agent.sentinel.config-file` | `SENTINEL_CONFIG_FILE` | `/opt/redis/conf/sentinel.conf` | Java路径检查、配置恢复；环境变量同时供启动脚本读取 |
| `agent.redis.backup-file` | `REDIS_BACKUP_FILE` | 按实际备份位置配置 | `RESTORE_CONFIG`读取的已有Redis配置备份 |
| `agent.sentinel.backup-file` | `SENTINEL_BACKUP_FILE` | 按实际备份位置配置 | `RESTORE_CONFIG`读取的已有Sentinel配置备份 |
| `agent.platform.token-file` | `AGENT_TOKEN_FILE` | 默认`./agent-token.dat` | 注册凭证文件，独立于Redis版本和安装包 |

安装包暂存根目录、配置文件暂存根目录、版本根目录、current、脚本目录、两个正式配置文件及数据目录均已配置上述开发默认值，生产环境仍可通过对应环境变量覆盖。路径末尾有无`/`不改变这里的目录用途。备份路径仍由现有配置指定，没有新增备份目录或自动备份流程；只有执行恢复任务时才要求备份文件已存在且摘要匹配。

### 其他已核对的配置

| 配置项 | 当前默认值或要求 | 用途及单位 |
| --- | --- | --- |
| `agent.operation.script-timeout-seconds` | `10` | 固定Shell脚本总超时，秒；可通过外部配置覆盖 |
| `agent.operation.min-free-space-bytes` | `2147483648`，环境变量`AGENT_MIN_FREE_SPACE_BYTES` | 暂存和安装目录所在文件系统的最低可用空间，字节，默认2 GiB |
| `agent.operation.stop-check-timeout-seconds` / `stop-check-interval-millis` | `10` / `1000` | Java停止状态检查，总超时为秒、间隔为毫秒 |
| `agent.operation.start-check-timeout-seconds` / `start-check-interval-millis` | `30` / `1000` | Java启动健康检查，总超时为秒、间隔为毫秒 |
| `agent.redis.host` / `port` | `127.0.0.1` / `6379` | `REDIS_HOST` / `REDIS_PORT`，Java连接及停止脚本应一致；主机预检查只接受本机地址 |
| `agent.redis.password` | 必填，无默认密码 | `REDIS_PASSWORD`，Java认证及停止脚本认证；与Redis实际配置一致 |
| `agent.redis.timeout` | `2000` | `REDIS_TIMEOUT`，Java连接和本机端口探测超时，毫秒 |
| `agent.redis.db` | `0` | `REDIS_DB`，指标采集连接使用的数据库 |
| `agent.redis.service-name` | 空 | 保留原有字段；当前未发现业务读取，不替代Sentinel主从组名称 |
| `agent.redis.pool.*` | `max-total=5`、`max-idle=2`、`min-idle=1`、`max-wait-millis=2000` | 既有指标采集连接池配置，等待时间为毫秒 |
| `agent.sentinel.host` / `port` | `127.0.0.1` / `26379` | `SENTINEL_HOST` / `SENTINEL_PORT`，Java连接及停止脚本应一致；预检查只接受本机地址 |
| `agent.sentinel.password` | 必填，无默认密码 | `SENTINEL_PASSWORD`，Sentinel自身认证密码，不是其连接Redis主节点使用的密码 |
| `agent.sentinel.timeout` | `2000` | `SENTINEL_TIMEOUT`，Java连接和本机端口探测超时，毫秒 |
| `agent.sentinel.service-name` | 启动Sentinel时必须配置 | `SENTINEL_SERVICE_NAME`，与`sentinel monitor`配置的主从组名称一致 |
| `server.port` | `9101` | `SERVER_PORT`，执行器HTTP端口及注册上报端口 |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | `50MB` / `51MB` | Spring Boot上传限制，对应单文件50 MiB、总请求51 MiB |

平台注册、Token保存、执行器名称及定时采集配置全部保留：`agent.platform.url`、`register-code`、`token-file`、`agent.agent-name`及`agent.schedule.collect-interval`、`heartbeat-interval`。采集和心跳间隔单位为毫秒，默认分别为300000和30000。平台注册凭证、Redis/Sentinel密码应通过银行允许的受控方式注入，不将实际值写入README、配置仓库或示例命令。

### 创建责任与首次准备

| 目录或文件 | 创建责任及当前实现 |
| --- | --- |
| `/opt/redis`及`packages/`、`configfiles/`、`versions/`、`scripts/`、`conf/`、`data/` | 部署人员提前准备；Java不会自动补建这些根目录，配置上传时要求configfiles已存在 |
| `scripts/`下7个运维脚本 | 部署人员从本仓库复制；测试辅助脚本不属于运行依赖 |
| `conf/.config-deploy-随机字符.tmp` | Java配置部署时创建的私有临时文件，正常结束或可处理的失败时清理；父目录随实际配置路径确定 |
| `conf/redis.conf`、`conf/sentinel.conf` | 由平台通过`DEPLOY_CONFIG`初始化，或按现有配置管理流程提前准备；预检查允许暂不存在，但启动前必须存在 |
| `packages/staging/` | `VersionPackageServiceImpl`接收版本包时按需创建 |
| `configfiles/staging/` | `ConfigFileServiceImpl`接收配置文件时按需创建 |
| `packages/staging/{taskNo}-{随机字符}/`及`package.part`、`package.tar.gz` | Java版本包上传独立创建，完成校验后返回完整路径 |
| `configfiles/staging/{taskNo}-config-{随机字符}/`及其中`redis.conf`、`sentinel.conf` | Java配置上传独立创建，两份摘要通过后提交内部路径，后台结束后清理 |
| `versions/{targetVersion}/bin/`及三个程序 | `deploy-version.sh`校验运行包后创建，已有目标版本不覆盖 |
| `versions/.deploy-随机字符/`、`.deploy-{targetVersion}.lock/` | 部署脚本的私有临时目录和互斥目录，正常退出时清理 |
| `current` | `switch-version.sh`首次创建或切换；目标版本须已部署，已有普通文件、真实目录或失效链接会拒绝处理 |
| `.current-switch-随机字符/`、`.current.switch-lock/` | 切换脚本在current父目录中临时创建并清理；名称随current文件名变化 |

部署约定：除版本管理使用的`current`外，本地配置文件及各级目录、执行器生成的暂存文件在整个运行期间均不使用软链接，由受控账户管理。普通业务方法不再逐层排除软链接；删除范围、不覆盖发布、脚本定位边界及归档内部链接检查仍保留。

根目录及所需父目录必须具备运行账户所需的读、写、访问权限。脚本由`/bin/bash`调用，脚本文件需可读，程序需有执行权限；暂存目录、任务目录及安装包须属于执行器账户且禁止组和其他账户写入，版本根目录应只允许受信任的部署账户写入。按银行账户和权限规范准备，不通过放开全局写权限解决部署问题。

主机预检查不读取Redis配置内容，也不确认目录中的配置与实际端口、密码、日志或数据位置一致；这些需部署时核对。2 GiB是两个文件系统各自的基线，不是预留空间，也不是任意包都能完成部署的保证。

### 四个版本相关操作如何使用目录

1. **DEPLOY_VERSION**：Java在`packages/staging/`接收文件，校验非空、50 MiB限制和SHA256；异步部署前再核对SHA256。固定`deploy-version.sh`接收安装包完整路径、目标版本号、`installDir`，在版本根目录内创建独立工作目录，安全读取固定的三个运行程序并检查版本，成功后发布到`versions/{targetVersion}/bin/`。不会运行make、停止服务、修改current或覆盖正式配置。
2. **SWITCH_VERSION**：Java将`targetVersion`、`installDir`、`currentDir`传给固定脚本，检查目标版本文件后原子替换current。只改变以后启动时使用的程序，当前运行的进程不会自动换版本。current缺失时首次创建，已有有效链接时切换；失效链接、普通文件及真实目录返回失败。
3. **START_REDIS**：Java只将`currentDir`传给脚本。脚本解析有效链接，用`{currentDir}/bin/redis-server`对应的实际程序读取`REDIS_CONFIG_FILE`，保留`--daemonize yes --supervised no`参数；Java随后使用已配置的Redis连接信息检查PING。
4. **START_SENTINEL**：同样由Java传入current，脚本使用`{currentDir}/bin/redis-sentinel`对应的实际程序及`SENTINEL_CONFIG_FILE`，保留`--sentinel --daemonize yes --supervised no`参数；Java检查Sentinel能否发现配置的主从组。

新服务器预检查允许没有current，但**首次启动Redis或Sentinel前必须已初始化为指向有效已部署版本目录的软链接**，不能用真实目录或普通文件代替。平台在部署版本后下发SWITCH_VERSION即可完成首次初始化；部署版本本身不会创建current。

### Java配置与Shell环境的边界

`currentDir`由Java作为参数传递，因此YAML覆盖会直接影响启动路径；部署和切换使用的`installDir`也由Java传递。启动脚本中的`REDIS_CONFIG_FILE`、`SENTINEL_CONFIG_FILE`，停止脚本中的host、port、password及`REDIS_CLI_BIN`，则来自Java进程继承的环境，**脚本不会解析application.yml，Java也没有把这些YAML值写回进程环境**。应在启动执行器的同一环境中设置这些变量，或在使用外部YAML时同步相应环境值。

两份停止脚本通过`REDIS_CLI_BIN`定位客户端，未设置时从PATH查找`redis-cli`，没有自动跟随current。本目录方案可将其配置为`/opt/redis/current/bin/redis-cli`，但这只是环境部署建议，本次未修改停止脚本。预检查脚本检查Linux、Bash和系统命令，不检查`REDIS_CLI_BIN`是否可用。

Token文件默认相对于Java工作目录保存，实际部署建议通过已有`AGENT_TOKEN_FILE`指定独立绝对路径并限制访问；它不放入运行包，不随版本切换改变。Shell日志、PID文件和Sentinel运行状态文件的位置沿用实际Redis/Sentinel配置，项目没有新增统一日志或PID目录。执行器目录设计不自动改写任何生产配置文件。

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
包目录和安装目录配置。启动阶段通过`currentDir`定位程序，部署阶段不读取、不创建、不修改该软链接。

完整目录及创建责任见上文“Linux 服务器目录结构与配置说明”。

`VersionPackageService`同步保存并校验摘要，`DeployVersionService`部署前再次核对摘要，
再通过固定脚本准备新版本。脚本参数为：安装包完整路径、目标版本、正式安装目录。
脚本不再读取packageDir，仍要求输入位于 `staging/TASK加12位数字-随机值/package.tar.gz`，
本地路径遵循上述部署约定，暂存文件和目录仍须由执行器账户拥有且不允许组或其他账户写入，
安装包不能是硬链接。该约束依赖独立的执行器运行账户，不构成同一账户内程序之间的隔离。
包服务清理仍以配置的packageDir为删除范围，脚本自身不删除输入安装包。
脚本放入 `AGENT_SCRIPT_DIR`，Linux主机需要 Bash 4及GNU tar、coreutils等系统命令，不需要Python或make。
只在独立临时安装目录解压，拒绝路径穿越、归档链接、特殊文件、重复文件和生产配置；
最多100个归档条目、解压总大小1 GiB。GNU tar以转义格式输出清单并校验固定成员，
三个程序使用--to-stdout分别写入私有目录的固定文件，不按归档路径落盘。
三份程序版本校验通过后才发布目标版本。
已有目标版本（包括空目录及失效软链接）拒绝覆盖，普通失败及SIGTERM终止时清理本次产物。
不可捕获的SIGKILL、断电可能留下 `.deploy-*` 目录或锁，需确认没有运行中任务后人工检查。

部署不停止Redis或Sentinel、不运行make、不修改current和生产配置。实际平台升级顺序
及结果上报保持原有流程；本次没有修改平台代码。

## DEPLOY_CONFIG配置部署

使用`POST /api/agent/config/deploy`，请求类型为`multipart/form-data`，沿用现有身份认证、任务线程池及结果上报。普通`/api/agent/task`不再接受DEPLOY_CONFIG。

上传三个部分：

| part名称 | 类型 | 内容 |
| --- | --- | --- |
| `task` | `application/json` | 任务编号、步骤编号、操作类型及两份文件SHA256 |
| `redisFile` | 文件，建议`application/octet-stream` | 平台生成并校验的完整Redis配置文件 |
| `sentinelFile` | 文件，建议`application/octet-stream` | 平台生成并校验的完整Sentinel配置文件 |

`task`结构如下，摘要占位说明需要替换为实际原始文件字节的64位SHA256：

```json
{
  "taskNo": "TASK202610170001",
  "stepNo": 3,
  "operationType": "DEPLOY_CONFIG",
  "params": {
    "redisConfigSha256": "Redis文件原始字节的64位SHA256",
    "sentinelConfigSha256": "Sentinel文件原始字节的64位SHA256"
  }
}
```

请求示意如下，身份凭证通过现有受控方式提供，不能把真实值提交到仓库：

```bash
curl -X POST 'http://执行器地址:9101/api/agent/config/deploy' \
  -H 'Authorization: Bearer <平台Token>' \
  -F 'task=@task.json;type=application/json' \
  -F 'redisFile=@redis.conf;type=application/octet-stream' \
  -F 'sentinelFile=@sentinel.conf;type=application/octet-stream'
```

普通任务接口及两个multipart部署接口均使用现有Bearer Token身份校验；未注册、缺少或错误Token返回401，不进入文件保存及任务提交逻辑。

两份文件和两个摘要均必填，每份文件最大1 MiB，空文件会拒绝。参数只接受两个摘要，不再接收配置正文，也不接受平台指定的本地路径。共享HTTP multipart限制仍为单文件50 MiB、总请求51 MiB以兼容版本包接口；配置文件的1 MiB上限由Controller在保存前检查，接收服务不再重复判断大小上限。文件名不参与保存路径拼接，执行器不解析、解码或重新编码内容，注释、空行、缩进、换行、BOM及原始编码均按字节保留。配置语法、密码和拓扑参数由平台生成并校验。

`ConfigFileServiceImpl`使用独立的`agent.operation.config-file-dir`配置，默认根目录为`/opt/redis/configfiles`，可通过环境变量`AGENT_CONFIG_FILE_DIR`覆盖。该根目录由部署人员提前创建，执行器只自动创建其下的`staging`和独立任务目录：

```text
configfiles/staging/TASK202610170001-config-随机字符/
├── redis.conf
└── sentinel.conf
```

`packages/staging`只暂存版本包，`configfiles/staging`只暂存配置文件，`conf`存放正式配置。配置任务目录保留`config`标记，任务编号仍为TASK加12位数字。Linux任务目录权限为700，两份暂存文件为600；父目录应只允许受信任账户写入。文件流式保存，核对接收长度与上传文件长度，并校验两个SHA256。两份都成功后，Controller把执行器生成的`redisConfigLocalPath`、`sentinelConfigLocalPath`写入内部任务参数；后台线程接收路径，不持有MultipartFile或请求文件流。

上传失败时由接收服务清理；文件保存成功但任务入队被拒绝或提交异常时由Controller清理。成功入队后Controller不清理，由`DeployConfigServiceImpl`在后台完成读取、发布或失败处理后，在finally中清理。202仅表示任务已接收，503表示繁忙未接收；上传文件或摘要错误返回400，分别沿用参数错误体系中的`CONFIG_FILE_INVALID`（PARAM_008）和`CONFIG_CHECKSUM_MISMATCH`（PARAM_009），异步部署阶段摘要变化则返回原有`CHECKSUM_MISMATCH`。

后台先确认两条内部路径属于同一受控配置任务目录、使用固定文件名，并保存该目录供finally清理。正式目标只读取本地`agent.redis.config-file`及`agent.sentinel.config-file`，父目录须提前存在，两个正式目标不能是同一文件，也不能位于上传暂存目录中。正式目标拒绝目录或特殊文件。

部署前重新计算暂存文件SHA256；接着原样复制到两个正式目标各自父目录中的`.config-deploy-*.tmp`私有文件，并再次核对实际发布副本，防止校验后复制期间内容变化。两份副本都正确后，才检查两个正式目标的冲突并逐份发布：

- 正式文件不存在：以同目录硬链接创建正式名称，发布时已是完整文件，不会覆盖竞争创建的目标。
- 已有可读写普通文件且摘要相同：保留原文件，视为该份已完成。
- 已有内容不同：返回`PRECONDITION_FAILED`，拒绝覆盖。

Linux副本及新正式文件保留600权限。运行账户须按银行规范安排，确保Redis/Sentinel可读，Sentinel可重写自身配置。文件系统不支持硬链接时返回失败，不退回覆盖式复制。

两份配置不是整体原子提交。若第一份发布后第二份失败，保留已发布正式文件，清理本次暂存和临时副本；平台重新上传相同文件及摘要即可补齐。清理范围只允许配置任务目录内的两个固定名称及空目录，不递归删除、不清理其他任务或正式配置。单个清理失败会记录日志并继续尝试其余文件，不将已成功发布的配置删除；失败残留须按银行运维流程处理。SIGKILL、断电也可能造成残留。

日志和上报结果不包含配置正文。本操作不启停服务、不修改数据目录、不创建current；首次current初始化由SWITCH_VERSION完成。

## SWITCH_VERSION版本切换

两个启动Service将已有的 `agent.operation.current-dir` 配置作为唯一内部参数传给脚本，
无需平台任务参数，也不新增配置项。Redis通过 `current/bin/redis-server` 启动，
Sentinel通过 `current/bin/redis-sentinel` 启动，保留 `--sentinel` 参数。
脚本要求current为有效软链接，解析后固定本次启动的版本目录，并检查程序文件及执行权限。
缺失、失效的软链接或无效程序会输出错误并退出，不会回退到PATH或旧程序路径。

两份停止脚本仍使用 `REDIS_CLI_BIN`，本次不修改；可按现有部署环境设置该路径。
配置文件仍指向生产原路径，不放到versions目录。

`SWITCH_VERSION` 通过普通任务接口提交，params只需传入 `targetVersion`。
`SwitchVersionService`传给固定脚本 `switch-version.sh` 的参数依次为：
目标版本号、已有配置installDir、已有配置currentDir。脚本使用Bash及GNU coreutils。
脚本检查目标版本及三个可执行程序。current尚不存在时用`ln -sT`首次创建，不加强制覆盖参数；
已存在时要求是有效软链接，沿用原子替换逻辑。普通文件、真实目录及失效链接均拒绝处理，
检查后出现的竞争文件或目录也不会在首次创建时被覆盖。操作不执行程序、重复部署或修改生产配置。
首次创建和已有链接切换共用同级 `.current.switch-lock` 目录互斥；已有链接切换的临时链接创建在同级独立目录，
通过同一文件系统的GNU mv -Tf原子替换。已指向目标版本时直接成功。
已有链接切换前的原版本目录写入执行器日志，平台应在编排时保存原版本号，回退时将其作为新的targetVersion。首次创建没有原版本可供回退。
普通失败及SIGTERM会清理本次临时目录和锁；SIGKILL或断电可能遗留锁，需核对任务后人工处理。
超时、中断或创建、替换后的检查失败不能据此认定链接未创建或仍指向旧版本，应核对current实际指向。

平台按部署、停止相应实例、切换、启动、检查实际运行版本的顺序编排。
切换成功仅表示链接已改变，正在运行的旧进程不会自动升级。执行器不自动停止或启动服务。

## Redis和Sentinel启停脚本

将四个脚本放到 `AGENT_SCRIPT_DIR` 指定目录，执行器通过 `/bin/bash` 调用，无需任务参数。
启动脚本直接调用Redis程序并后台运行，停止脚本调用带认证的正常 `SHUTDOWN`，
Java负责后续健康检查。停止前会检查 `INFO server` 返回的服务类型，避免误停。

在启动Java执行器的同一个环境中配置，例如：

```bash
export AGENT_SCRIPT_DIR=/opt/redis/scripts
export AGENT_CURRENT_DIR=/opt/redis/current
export REDIS_CLI_BIN=/opt/redis/current/bin/redis-cli
export REDIS_CONFIG_FILE=/opt/redis/conf/redis.conf
export REDIS_DATA_DIR=/opt/redis/data
export SENTINEL_CONFIG_FILE=/opt/redis/conf/sentinel.conf
export SENTINEL_SERVICE_NAME=redis-main
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export SENTINEL_HOST=127.0.0.1
export SENTINEL_PORT=26379
```

`REDIS_PASSWORD`、`SENTINEL_PASSWORD` 使用已有的执行器环境变量，不要写进脚本。
Sentinel停止使用的是Sentinel自身认证密码，不是 `sentinel auth-pass` 的Redis主节点密码。
启动程序只通过Java传入的current软链接定位，不再使用 `REDIS_SERVER_BIN`、`SENTINEL_SERVER_BIN` 或PATH。
`REDIS_CLI_BIN` 默认从PATH查找 `redis-cli`。

current路径由Java读取配置后直接传入，支持环境变量和外部YAML覆盖。手动调用启动脚本时，
也必须传入一个current软链接绝对路径参数。其他配置仍继承Java进程环境变量，脚本不会自动读取
`application.yml`。如果通过外部YAML覆盖了
配置路径、IP、端口或密码，也需同步配置上面的环境变量，保持二者一致。
实际监听地址、端口、密码、日志和数据目录由对应Redis/Sentinel配置文件决定。
运行账户应具备程序执行权限及日志/数据目录写权限，Sentinel配置文件还必须可写。
这套脚本直接管理Redis进程，不适用于同时由systemd负责启停的实例。

## PRECHECK_HOST主机预检查

通过 `/api/agent/task` 提交PRECHECK_HOST，不接收业务params（允许省略或空对象）。
执行器只读取本机配置及状态，不创建目录、不修改配置、不启停服务，不检查systemd。
检查暂存目录、安装目录、脚本目录、数据目录及配置文件父目录的权限；新机允许尚无
redis.conf、sentinel.conf、备份文件及current。已有current必须是指向installDir内版本目录的有效软链接。
各基础目录应事先准备；已有配置文件必须是可读写普通文件。Redis和Sentinel连接地址必须属于本机，
TCP连接成功视为端口已有服务监听，连接被拒绝视为未监听，超时或其他异常视为检查失败。
端口探测只反映配置地址当时的可连接状态，不预占端口，也不保证后续启动一定能绑定成功。
当前已实现的7个运维脚本须放入scriptDir，不要求尚未开发的配置下发脚本；precheck-host.sh只检查Linux、Bash及实际使用的系统命令，不执行业务脚本。
暂存及安装目录的最低可用空间由 `AGENT_MIN_FREE_SPACE_BYTES` 配置，默认2147483648字节（2 GiB）。
这是部署前基线，不承诺任意安装包空间充足。任一检查失败即通过现有OperationResultDTO上报原因。
配置下发使用DEPLOY_CONFIG；新机current的首次创建使用SWITCH_VERSION，预检查本身不创建链接。
