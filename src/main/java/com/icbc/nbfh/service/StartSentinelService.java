package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.OperationResultDTO;

/**
 * Sentinel启动服务。
 */
public interface StartSentinelService {

    /**
     * 执行Sentinel启动操作并检查运行状态。
     */
    OperationResultDTO startSentinel();
}
