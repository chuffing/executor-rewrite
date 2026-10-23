package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.OperationResultDTO;

import java.util.Map;

/**
 * Redis和Sentinel配置恢复服务。
 */
public interface RestoreConfigService {

    /**
     * 使用本机备份恢复Redis和Sentinel配置。
     */
    OperationResultDTO restoreConfig(Map<String, String> params);
}
