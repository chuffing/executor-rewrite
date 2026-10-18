package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.OperationResultDTO;

public interface PrecheckHostService {

    /**
     * 检查本机是否具备Redis部署所需的基础条件。
     */
    OperationResultDTO precheckHost();
}
