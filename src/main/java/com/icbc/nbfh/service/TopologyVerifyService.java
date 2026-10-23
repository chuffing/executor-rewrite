package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.OperationResultDTO;

import java.util.Map;

/**
 * Redis和Sentinel拓扑验收服务。
 */
public interface TopologyVerifyService {

    /**
     * 验收拓扑，并在提供期望参数时核对主节点和本机角色。
     */
    OperationResultDTO verifyTopology(Map<String, String> params);
}
