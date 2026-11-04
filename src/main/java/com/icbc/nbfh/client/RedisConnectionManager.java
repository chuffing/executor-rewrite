package com.icbc.nbfh.client;

import com.icbc.nbfh.common.constant.CollectConstant;
import com.icbc.nbfh.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import javax.annotation.PreDestroy;

/**
 * Redis 连接池生命周期管理
 *只负责建池、借还连接、就绪探测与关闭，不参与指标语义计算
 */
@Slf4j
@Component
public class RedisConnectionManager {

    private final AgentProperties properties;

    private volatile JedisPool jedisPool;

    public RedisConnectionManager(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 初始化 Redis 连接池（幂等）
     *
     * @throws IllegalStateException 未配置 agent.redis.host
     */
    public synchronized void initIfNeeded() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            return;
        }
        AgentProperties.RedisConfig redisConfig = properties.getRedis();
        if (redisConfig == null || !StringUtils.hasText(redisConfig.getHost())) {
            throw new IllegalStateException("未配置 agent.redis.host");
        }

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(redisConfig.getPool().getMaxTotal());
        poolConfig.setMaxIdle(redisConfig.getPool().getMaxIdle());
        poolConfig.setMinIdle(redisConfig.getPool().getMinIdle());
        poolConfig.setMaxWaitMillis(redisConfig.getPool().getMaxWaitMillis());

        String password = StringUtils.hasText(redisConfig.getPassword())
                ? redisConfig.getPassword() : null;

        jedisPool = new JedisPool(poolConfig,
                redisConfig.getHost().trim(),
                redisConfig.getPort(),
                redisConfig.getTimeout(),
                password,
                redisConfig.getDb());
        log.info("Redis 连接池初始化完成: {}:{} (db={})",
                redisConfig.getHost(), redisConfig.getPort(), redisConfig.getDb());
    }

    /**
     * 借用连接；调用方负责 close（try-with-resources）
     *
     * @throws IllegalStateException 连接池不可用
     */
    public Jedis borrow() {
        initIfNeeded();
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) {
            throw new IllegalStateException("Redis 连接池不可用");
        }
        return pool.getResource();
    }

    /**
     * 注册前自检：连续 PING 全部返回 PONG 视为就绪
     * 连接失败/认证失败/超时均返回 false
     */
    public synchronized boolean isRedisReady() {
        try {
            initIfNeeded();
            try (Jedis jedis = borrow()) {
                for (int i = 0; i < CollectConstant.REDIS_PING_TIMES; i++) {
                    if (!CollectConstant.PONG.equalsIgnoreCase(jedis.ping())) {
                        return false;
                    }
                }
                return true;
            }
        } catch (Exception e) {
            log.debug("Redis 就绪探测失败: {}", e.getMessage());
            return false;
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
                log.info("Redis 连接池已关闭");
            } catch (Exception e) {
                log.warn("关闭 Redis 连接池失败: {}", e.getMessage());
            }
        }
        jedisPool = null;
    }
}
