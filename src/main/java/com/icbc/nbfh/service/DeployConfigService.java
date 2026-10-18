package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.OperationResultDTO;

import java.util.Map;

/**
 * Redis和Sentinel配置部署服务。
 */
public interface DeployConfigService {

    /**
     * 读取内部暂存路径并校验文件摘要，安全初始化本机配置，相同内容允许重试。
     */
    OperationResultDTO deployConfig(Map<String, String> params);
}
