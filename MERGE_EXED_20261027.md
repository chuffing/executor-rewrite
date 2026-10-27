# exeD 分支合并说明

## 一、合并信息

- 目标仓库：`executor-rewrite`
- 目标分支：`main`
- 来源分支：`origin/exeD`
- 合并前 `main`：`fe40c87`
- 来源提交：`4855811`
- 合并日期：2026-10-27
- 安全备份分支：`codex/pre-exed-merge-20261027`

本次合并以当前 `main` 中已经完成的运维功能为基础，引入 `exeD` 分支中的注册、心跳、指标采集、Sentinel 采集和本地数据留存功能。没有修改平台项目，也没有推送远端。

## 二、本次合入的主要功能

1. 执行器注册、二次注册和心跳上报。
2. Redis 指标采集与上报。
3. Sentinel 节点信息采集与上报。
4. 上报失败时的本地指标留存和重传。
5. 手动触发采集的 Controller。
6. 注册及采集所需的配置项、定时任务和日志配置。
7. 注册异常、业务异常及相关统一返回对象。

## 三、冲突及处理方式

### 1. `ResultBean.java`

两个分支同时修改了统一响应对象。最终保留当前 `main` 的构造方式，去掉未使用的 Lombok 全参构造注解，避免改变现有接口返回行为。

### 2. 测试目录

`exeD` 修改或新增了测试类，而当前 `main` 已按照项目现阶段约定删除全部测试类。最终继续保持 `src/test` 不存在，后续需要测试时再根据最终业务重新编写。

### 3. 拓扑验证参数

保留当前 `main` 的统一无参拓扑验证规则。`VERIFY_TOPOLOGY` 不允许携带任务参数，删除 `exeD` 中对期望主节点 IP 和期望角色的可选参数校验。

## 四、合并后的适配修改

### 1. 平台接口地址对齐

| 功能 | 执行器调用地址 |
| --- | --- |
| 注册 | `POST /api/agent/register` |
| 心跳 | `POST /api/agent/heartbeat` |
| Ping | `GET /api/agent/ping` |
| 指标上报 | `POST /metric/report` |
| 运维结果上报 | `POST /api/task/report` |

`exeD` 原来的注册、心跳和 Ping 路径缺少 `/api` 前缀，本次已经按照平台实际 Controller 修正。

### 2. 配置文件去除开发环境硬编码

删除了开发机器的 IP、Redis 密码和固定注册码，默认使用本机安全值，并允许通过环境变量覆盖：

- `AGENT_PLATFORM_URL`
- `AGENT_REGISTER_CODE`
- `AGENT_REGISTER_RETRY_INTERVAL`
- `AGENT_DATA_DIR`
- `AGENT_DATA_RETAIN_DAYS`
- `AGENT_LOG_DIR`
- `AGENT_UPGRADE_DIR`

Redis 和 Sentinel 默认地址统一为 `127.0.0.1`，Redis 密码默认为空。

### 3. 指标单位对齐

平台的 Redis 命令平均耗时字段按微秒接收。执行器现直接使用 `INFO commandstats` 中 `usec/calls` 的差值计算微秒值，不再除以 1000。Ping 平台的网络延迟仍使用毫秒。

### 4. 版本切换操作收口

删除执行器枚举中的 `ROLLBACK_VERSION`。版本回退与普通版本切换都使用 `SWITCH_VERSION`，由目标版本参数决定最终状态。

### 5. 注册与采集关系

合并后的流程为：

```text
redisReady=false
    ↓
执行器首次注册，仅创建或更新 executor_info
    ↓
instanceId 为空，暂不采集 Redis 指标
    ↓
Redis 部署并启动完成，redisReady=true
    ↓
执行器二次注册，平台建立 Redis 绑定关系
    ↓
平台返回 instanceId 和 bound=true
    ↓
执行器开始正式采集并上报数据
```

本地凭证文件支持读取旧格式，同时使用 JSON 保存新的注册状态，避免执行器重启后丢失绑定信息。

## 五、保留的当前 main 行为

1. 保留现有 Redis/Sentinel 启停、配置部署、版本部署和版本切换等运维实现。
2. 保留统一的无参拓扑验证操作。
3. 保留任务接收、异步执行和结果上报流程。
4. 保留版本包和配置文件上传被拒绝时的临时文件清理。
5. 保留当前项目不包含测试类的状态。

## 六、验证结果

已执行：

```text
mvn -DskipTests clean package
```

验证结果：

- 编译源文件：86 个
- 编译结果：`BUILD SUCCESS`
- 生成文件：`target/redis-monitor-agent.jar`
- 测试：按照当前项目约定未执行，且仓库中不存在 `src/test`
- 编译期间仅出现既有的过时 API 警告，没有编译错误

## 七、后续联调建议

1. 使用非生产环境验证首次注册和 `redisReady=false` 时只创建执行器记录。
2. Redis 部署完成后验证二次注册能取得 `instanceId` 和 `bound=true`。
3. 验证 Redis、Sentinel、Ping 三类数据能通过同一次指标请求写入平台。
4. 断开平台网络后验证本地留存，再恢复网络验证积压数据重传。
5. 使用模拟平台验证任务下发、执行结果回调和 Bearer Token 校验。
