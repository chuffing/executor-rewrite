package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.OperationResultDTO;

/**
 * Redis停止服务。
 */
public interface StopRedisService {

    /**
     * 执行Redis停止操作并检查停止状态。
     */
    OperationResultDTO stopRedis();
}
