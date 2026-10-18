package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.OperationResultDTO;

import java.util.Map;

public interface SwitchVersionService {

    /**
     * 将current软链接切换到已部署的目标版本。
     */
    OperationResultDTO switchVersion(Map<String, String> params);
}
