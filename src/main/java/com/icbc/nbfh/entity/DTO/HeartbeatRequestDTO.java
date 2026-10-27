package com.icbc.nbfh.entity.DTO;

import lombok.Data;

/**
 * 心跳请求，字段与平台端AgentHeartbeatRequest对应
 * 平台端通过 token 定位执行器，并校验 hostIp + executorPort 与注册时一致
 */
@Data
public class HeartbeatRequestDTO {

    /** 执行器所在服务器 IP */
    private String hostIp;

    /** 执行器 HTTP 服务端口 */
    private String executorPort;
}
