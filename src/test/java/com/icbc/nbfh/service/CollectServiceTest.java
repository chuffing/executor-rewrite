package com.icbc.nbfh.service;

import com.alibaba.fastjson.JSON;
import com.icbc.nbfh.common.constant.CommonConstant;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.MetricDataDTO;
import com.icbc.nbfh.collector.RedisInfoCollector;


public class CollectServiceTest {

    public static void main(String[] args) {
        AgentProperties properties = new AgentProperties();
        properties.getRedis().setHost(env("REDIS_HOST", "172.20.10.3"));
        properties.getRedis().setPort(Integer.parseInt(env("REDIS_PORT", "6379")));
        properties.getRedis().setPassword(env("REDIS_PASSWORD","20021227"));
//        properties.getRedis().setPassword(System.getenv("REDIS_PASSWORD"));

        RedisInfoCollector collector = new RedisInfoCollector(properties);
        MetricDataDTO metric = collector.collect();
        System.out.println("采集结果: " + JSON.toJSONString(metric));
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }
}

