package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.OperationResultDTO;

/**
 * 故障恢复拓扑验收服务。
 */
public interface RecoveryTopologyVerifyService {

    /**
     * 在规定时间内等待Redis和Sentinel拓扑恢复正常。
     */
    OperationResultDTO verifyTopology();
}
