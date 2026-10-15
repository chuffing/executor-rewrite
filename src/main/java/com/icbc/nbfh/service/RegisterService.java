package com.icbc.nbfh.service;

/**
 * Agent注册服务。
 */
public interface RegisterService {

    /**
     * 执行注册流程。
     *
     * @return true表示已注册，false表示注册失败
     */
    boolean registerIfNeeded();

    /**
     * 判断当前执行器是否已经注册。
     */
    boolean isRegistered();

    /**
     * 获取当前Token。
     */
    String getToken();

    /**
     * 获取当前执行器编号。
     */
    String getAgentId();
}