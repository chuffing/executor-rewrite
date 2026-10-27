package com.icbc.nbfh.service;

/**
 * 执行器注册服务
 * 平台端注册分两阶段：
 *   Redis 未连接：只注册执行器（redisReady=false），平台不创建/绑定实例；
 *   Redis 连接正常：再次注册并携带 redisReady=true + redisInfo，
 *       平台创建 redis_instance 并回写 instanceId，执行器随后才允许采集上报
 */
public interface RegisterService {

    /**
     * 按需执行注册/二次注册
     *
     * @return true 表示已拿到平台 token（不要求已绑定实例）
     */
    boolean registerIfNeeded();

    /**
     * 判断当前执行器是否已经拿到平台 token
     */
    boolean isRegistered();

    /**
     * 是否已拿到 token
     */
    boolean hasToken();

    /**
     * 是否已完成 Redis 实例绑定
     */
    boolean isBound();

    /**
     * 获取平台绑定的 Redis 实例 id；未绑定时为 null
     */
    String getInstanceId();

    /**
     * 获取当前 token
     */
    String getToken();

    /**
     * 获取执行器本地标识（hostIp:executorPort）
     *
     * 平台注册接口未返回执行器主键，运维任务结果本地留存使用该稳定标识
     */
    String getAgentId();

    /**
     * 获取注册使用的执行器 IP
     */
    String getHostIp();

    /**
     * 获取注册使用的执行器端口
     */
    String getExecutorPort();

    /**
     * 清除本地 token、实例绑定状态和 token 文件，等待重新注册
     */
    void invalidateToken();
}
