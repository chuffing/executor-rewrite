package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.dto.PlatformTaskDTO;

/**
 * 运维任务服务，由运维业务代码实现。
 */
public interface AgentTaskService {

    void submitTask(PlatformTaskDTO task);
}
