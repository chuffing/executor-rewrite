package com.icbc.nbfh.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.icbc.nbfh.entity.DTO.HeartbeatRequestDTO;
import com.icbc.nbfh.entity.DTO.MetricDataDTO;
import com.icbc.nbfh.entity.DTO.RegisterRequestDTO;
import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;
import com.icbc.nbfh.common.constant.TaskResultConstants;
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

        String url = platformUrl + TaskResultConstants.REPORT_PATH;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(
                TaskResultConstants.AUTHORIZATION_HEADER,
                TaskResultConstants.BEARER_PREFIX + token
        );

        HttpEntity<String> entity = new HttpEntity<>(
                JSON.toJSONString(request),
                headers
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
                url,
                entity,
                String.class
        );

        validateTaskResultResponse(response.getBody());
    }

    /**
     * 校验平台是否真正接收并保存了任务结果。
     */
    private void validateTaskResultResponse(String responseBody) {

        if (responseBody == null || responseBody.trim().isEmpty()) {
            throw new IllegalStateException("平台任务结果响应为空");
        }

        Map<String, Object> response;
        try {
            response = JSON.parseObject(
                    responseBody,
                    new TypeReference<Map<String, Object>>() {
                    }
            );
        } catch (Exception e) {
            throw new IllegalStateException(
                    "平台任务结果响应格式错误",
                    e
            );
        }

        Object codeValue = response.get("code");
        if (!(codeValue instanceof Number)) {
            throw new IllegalStateException("平台任务结果响应缺少状态码");
        }

        int code = ((Number) codeValue).intValue();
        if (code != TaskResultConstants.PLATFORM_SUCCESS_CODE) {
            // 新版统一响应使用msg，兼容尚未升级平台的message字段。
            Object message = response.get("msg");
            if (message == null) {
                message = response.get("message");
            }
            throw new IllegalStateException(
                    message == null
                            ? "平台拒绝接收任务结果，code=" + code
                            : String.valueOf(message)
            );
        }
    }
}
