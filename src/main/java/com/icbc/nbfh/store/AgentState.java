package com.icbc.nbfh.store;

import org.springframework.util.StringUtils;

/**
 * 执行器本地注册状态快照
 *
 * 不可变对象，由  AgentStateStore 负责持久化
 */
public final class AgentState {

    private final String token;
    private final String hostIp;
    private final String executorPort;
    private final String instanceId;
    private final boolean bound;

    public AgentState(String token, String hostIp, String executorPort,
                      String instanceId, boolean bound) {
        this.token = token;
        this.hostIp = hostIp;
        this.executorPort = executorPort;
        this.instanceId = instanceId == null ? "" : instanceId;
        this.bound = bound;
    }

    public String getToken() {
        return token;
    }

    public String getHostIp() {
        return hostIp;
    }

    public String getExecutorPort() {
        return executorPort;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public boolean isBound() {
        return bound && StringUtils.hasText(instanceId);
    }

    public boolean hasToken() {
        return StringUtils.hasText(token);
    }
}
