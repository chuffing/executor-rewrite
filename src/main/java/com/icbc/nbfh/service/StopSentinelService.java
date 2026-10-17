package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.OperationResultDTO;

/**
 * Sentinel停止服务。
 */
public interface StopSentinelService {

    /**
     * 执行Sentinel停止操作并检查停止状态。
     */
    OperationResultDTO execute();
}
