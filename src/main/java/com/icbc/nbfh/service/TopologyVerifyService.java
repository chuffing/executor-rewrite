package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.OperationResultDTO;

/**
 * Redis和Sentinel拓扑验收服务。
 */
public interface TopologyVerifyService {

    /**
     * 验收当前Redis和Sentinel的实际运行拓扑。
     */
    OperationResultDTO verifyTopology();
}
