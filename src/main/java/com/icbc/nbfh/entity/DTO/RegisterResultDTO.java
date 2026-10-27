package com.icbc.nbfh.entity.DTO;

import lombok.Data;

/**
 * 平台注册返回结果，对应平台端 AgentRegisterResultDTO
 */
@Data
public class RegisterResultDTO {

    /** 执行器后续心跳、指标上报使用的 token */
    private String executorToken;

    /** 平台绑定的 Redis 实例 id；未绑定时为空 */
    private String instanceId;

    /** 是否已完成 Redis 实例绑定 */
    private Boolean bound;

    public RegisterResultDTO() {
    }

    public RegisterResultDTO(String executorToken, String instanceId, Boolean bound) {
        this.executorToken = executorToken;
        this.instanceId = instanceId;
        this.bound = bound;
    }
}
