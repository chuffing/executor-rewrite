package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.MetricDataDTO;
import com.icbc.nbfh.service.CollectService;
import com.icbc.nbfh.service.RegisterService;
import com.icbc.nbfh.service.client.PlatformClient;
import com.icbc.nbfh.service.collector.RedisInfoCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CollectServiceImpl implements CollectService {

    private final RedisInfoCollector collector;
    private final PlatformClient platformClient;
    private final RegisterService registerService;
    private final AgentProperties properties;

    public CollectServiceImpl(RedisInfoCollector collector,
                              PlatformClient platformClient,
                              RegisterService registerService,
                              AgentProperties properties) {
        this.collector = collector;
        this.platformClient = platformClient;
        this.registerService = registerService;
        this.properties = properties;
    }

    @Override
    public void collectAndReport() {
        if (!registerService.isRegistered()) {
            log.warn("执行器尚未注册，跳过指标采集");
            return;
        }

        String token = registerService.getToken();

        try {
            MetricDataDTO metric = collector.collect();
            platformClient.reportMetric(metric, properties.getPlatform().getUrl(),
                    token, registerService.getAgentId());
            log.debug("指标采集并上报完成，status={}", metric.getStatus());
        } catch (Exception e) {
            log.error("指标采集或上报失败", e);
        }
    }
}
