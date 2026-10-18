package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.OperationResultDTO;

/**
 * Redis启动服务。
 */
public interface StartRedisService {

    /**
     * 执行Redis启动操作并检查运行状态。
     */
    OperationResultDTO startRedis();
}
