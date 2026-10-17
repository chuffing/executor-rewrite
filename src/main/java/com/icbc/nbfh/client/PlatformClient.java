package com.icbc.nbfh.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.icbc.nbfh.entity.DTO.HeartbeatRequestDTO;
import com.icbc.nbfh.entity.DTO.MetricDataDTO;
import com.icbc.nbfh.entity.DTO.RegisterRequestDTO;
import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 负责执行器与管理平台之间的HTTP通信。
 */
@Component
public class PlatformClient {

    private final RestTemplate restTemplate;

    public PlatformClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Map<String, Object> register(RegisterRequestDTO request, String platformUrl) {
        String url = platformUrl + "/api/agent/register";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(request), headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                url, entity, String.class);
        return JSON.parseObject(response.getBody(),
                new TypeReference<Map<String, Object>>() { });
    }

    public void heartbeat(HeartbeatRequestDTO request, String platformUrl, String token) {
        String url = platformUrl + "/api/agent/heartbeat";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(request), headers);
        restTemplate.postForEntity(url, entity, String.class);
    }

    public void reportMetric(MetricDataDTO metric, String platformUrl,
                             String token, String agentId) {
        String url = platformUrl + "/api/metric/report";
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("metric", metric);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(body), headers);
        restTemplate.postForEntity(url, entity, String.class);
    }

    /**
     * 向平台上报任务执行结果。
     */
    public void reportTaskResult(TaskResultReportDTO request,
                                 String platformUrl,
                                 String token) {

        String url = platformUrl + "/api/task/report";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);

        HttpEntity<String> entity = new HttpEntity<>(
                JSON.toJSONString(request),
                headers
        );

        restTemplate.postForEntity(url, entity, String.class);
    }
}
