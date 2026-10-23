package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.OperationResultDTO;

import java.util.Map;

/**
 * Redis和Sentinel拓扑验收服务。
 */
public interface TopologyVerifyService {

    /**
     * 在规定时间内等待Redis和Sentinel拓扑恢复正常。
     */
    OperationResultDTO verifyTopology();

    /**
     * 验收拓扑，并在提供期望参数时核对主节点和本机角色。
     */
    OperationResultDTO verifyTopology(Map<String, String> params);
}
