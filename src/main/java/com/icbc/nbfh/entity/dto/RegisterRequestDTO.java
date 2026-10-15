package com.icbc.nbfh.entity.dto;

import lombok.Data;

import java.util.Map;

/**
 * 执行器注册请求。
 */
@Data
public class RegisterRequestDTO {

    private String registerCode;

    private String agentName;

    private String hostname;

    private String ip;

    private Integer agentPort;

    private String os;

    private Map<String, String> redisInfo;
}
