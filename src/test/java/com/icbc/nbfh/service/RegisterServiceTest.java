package com.icbc.nbfh.service;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RegisterServiceTest {
    // 在 RedisInfoCollector 中加一个 main 方法
    public static void main(String[] args) {
        String host = env("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("REDIS_PORT", "6379"));
        String password = System.getenv("REDIS_PASSWORD");

        JedisPoolConfig config = new JedisPoolConfig();
        JedisPool pool = new JedisPool(
                config,
                host,
                port,
                5000,
                password
        );
        try (Jedis jedis = pool.getResource()) {
            System.out.println("INFO server:");
            System.out.println(jedis.info("server"));
            System.out.println("---");
            System.out.println("INFO stats:");
            System.out.println(jedis.info("stats"));
            System.out.println("---");
            System.out.println("INFO replication:");
            System.out.println(jedis.info("replication"));
            System.out.println("---");
            System.out.println("INFO commandstats:");
            System.out.println(jedis.info("commandstats"));
        }
        pool.close();
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

}
