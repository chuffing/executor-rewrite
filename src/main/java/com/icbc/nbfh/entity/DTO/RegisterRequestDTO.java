package com.icbc.nbfh.entity.DTO;

import lombok.Data;

import java.util.Map;

/**
 * 执行器注册请求
 * 字段与平台端AgentRegisterRequest对应
 * {hostIp + executorPort} 作为执行器唯一标识
 */
@Data
public class RegisterRequestDTO {

    /** 平台注册码 */
    private String registerCode;

    /** 执行器所在服务器 IPv4 */
    private String hostIp;

    /** 执行器 HTTP 服务端口，平台端为字符串 */
    private String executorPort;

    /**
     * 本机 Redis 是否连接正常
     * false 时平台只注册执行器，不创建/绑定 Redis 实例；Redis 恢复后再次注册绑定
     */
    private Boolean redisReady;

    /**
     * 随注册上报的 Redis 采集信息，仅 {@code redisReady=true} 时携带
     */
    private Map<String, String> redisInfo;
}
