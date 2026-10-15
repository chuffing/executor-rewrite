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
```

没有密码的 Redis 可以不配置 `REDIS_PASSWORD`。其他参数及默认值见 `src/main/resources/application.yml`。

## 当前代码范围

当前代码保留了同事项目的注册、心跳、指标采集和任务执行骨架。后续业务接口和参数校验按实际需求逐步手写，不在初始化阶段提前增加抽象层。
