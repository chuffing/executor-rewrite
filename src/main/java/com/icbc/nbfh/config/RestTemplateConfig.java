package com.icbc.nbfh.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 平台 HTTP 调用客户端配置。
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(
            @Value("${agent.http-connect-timeout:5000}") int connectTimeout,
            @Value("${agent.http-read-timeout:10000}") int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }
}
