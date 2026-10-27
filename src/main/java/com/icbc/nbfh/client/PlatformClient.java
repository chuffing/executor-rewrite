package com.icbc.nbfh.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.icbc.nbfh.common.constant.TaskResultConstants;
import com.icbc.nbfh.common.exception.BusinessCode;
import com.icbc.nbfh.common.exception.BusinessException;
import com.icbc.nbfh.entity.DTO.HeartbeatRequestDTO;
import com.icbc.nbfh.entity.DTO.MetricReportDTO;
import com.icbc.nbfh.entity.DTO.RegisterRequestDTO;
import com.icbc.nbfh.entity.DTO.RegisterResultDTO;
import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * 负责执行器与管理平台之间的 HTTP 通信
 * 与平台端 Controller 对应的路径：
 *   POST {@code /agent/register}
 *   POST {@code /agent/heartbeat}
 *   GET  {@code /agent/ping}
 *   POST {@code /metric/report}

 */
@Slf4j
@Component
public class PlatformClient {

    /** 注册接口 */
    private static final String PATH_REGISTER = "/agent/register";

    /** 心跳接口 */
    private static final String PATH_HEARTBEAT = "/agent/heartbeat";

    /** 执行器 ping 平台接口 */
    private static final String PATH_PING = "/agent/ping";

    /** 指标上报接口 */
    private static final String PATH_METRIC_REPORT = "/metric/report";

    /** 平台 ping 采样次数 */
    private static final int PING_SAMPLE_TIMES = 3;

    /** 最近一次成功测得的执行器到平台延迟（ms），探测失败时沿用 */
    private volatile String lastPingLatency = "0.00";

    private final RestTemplate restTemplate;

    public PlatformClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 注册执行器
     *
     * @return 平台下发的 token、绑定的 instanceId 和 bound，据此判断是否需要“Redis 就绪后二次注册”
     */
    public RegisterResultDTO register(RegisterRequestDTO request, String platformUrl) {
        JSONObject response = post(platformUrl, PATH_REGISTER, request, null);
        JSONObject data = response.getJSONObject("data");
        if (data == null) {
            throw new BusinessException(BusinessCode.SYSTEM_ERROR.getCode(),
                    "注册响应缺少 data，平台返回: " + abbreviate(response.toJSONString()));
        }

        String token = data.getString("executorToken");
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(BusinessCode.SYSTEM_ERROR.getCode(),
                    "注册响应缺少 executorToken，平台返回: " + abbreviate(response.toJSONString()));
        }

        String instanceId = data.getString("instanceId");
        Boolean bound = data.getBoolean("bound");
        if (bound == null) {
            // 兼容旧平台：未返回 bound 时按 instanceId 是否为空判断
            bound = StringUtils.hasText(instanceId);
        }
        // instanceId 为空时 bound 必须为 false，避免平台返回不一致导致执行器误判
        if (!StringUtils.hasText(instanceId)) {
            bound = false;
        }

        return new RegisterResultDTO(token.trim(),
                instanceId == null ? "" : instanceId.trim(), bound);
    }

    /**
     * 发送心跳
     *
     * @return 平台是否成功刷新执行器心跳
     */
    public boolean heartbeat(HeartbeatRequestDTO request,
                             String platformUrl,
                             String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(BusinessCode.PARAMS_ERROR.getCode(),
                    "执行器 token 为空，无法发送心跳");
        }
        JSONObject response = post(platformUrl, PATH_HEARTBEAT, request, token);
        Object data = response.get("data");
        return !(data instanceof Boolean) || (Boolean) data;
    }

    /**
     * 上报一次采集数据（Redis 指标 + ping 延迟 + Sentinel 节点）
     *
     * @param metric      上报报文
     * @param platformUrl 平台地址
     * @param token       执行器 token，会写入请求体
     */
    public void reportMetric(MetricReportDTO metric,
                             String platformUrl,
                             String token) {
        if (metric == null) {
            throw new BusinessException(BusinessCode.PARAMS_ERROR.getCode(),
                    "指标上报报文为空");
        }
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(BusinessCode.PARAMS_ERROR.getCode(),
                    "执行器 token 为空，无法上报指标");
        }
        metric.setToken(token);
        post(platformUrl, PATH_METRIC_REPORT, metric, token);
    }

    /**
     * 测量执行器到平台的 HTTP 往返延迟（毫秒）
     *
     * 连续请求平台 {@code GET /agent/ping} 3 次，取平均值；
     * 全部失败时沿用上一次成功值，首次失败返回 0.00，不抛异常
     */
    public String measurePingLatency(String platformUrl) {
        String url;
        try {
            url = resolveBaseUrl(platformUrl) + PATH_PING;
        } catch (Exception e) {
            log.debug("平台地址未配置，跳过 ping 延迟测量: {}", e.getMessage());
            return lastPingLatency;
        }

        double totalMillis = 0;
        int success = 0;
        for (int i = 0; i < PING_SAMPLE_TIMES; i++) {
            long start = System.nanoTime();
            try {
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                long elapsed = System.nanoTime() - start;
                if (!response.getStatusCode().is2xxSuccessful()) {
                    continue;
                }
                JSONObject json = JSON.parseObject(response.getBody());
                if (json == null || json.getIntValue("code") != 0) {
                    continue;
                }
                totalMillis += elapsed / 1_000_000.0;
                success++;
            } catch (Exception e) {
                log.debug("平台连通性探测失败: {}", e.getMessage());
            }
        }
        if (success == 0) {
            return lastPingLatency;
        }
        String value = String.format(Locale.ROOT, "%.2f", totalMillis / success);
        lastPingLatency = value;
        return value;
    }

    /**
     * 向平台上报任务执行结果
     */
    public void reportTaskResult(TaskResultReportDTO request,
                                 String platformUrl,
                                 String token) {

        String url = resolveBaseUrl(platformUrl) + TaskResultConstants.REPORT_PATH;

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
     * 校验平台是否真正接收并保存了任务结果
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
            throw new IllegalStateException("平台任务结果响应格式错误", e);
        }

        Object codeValue = response.get("code");
        if (!(codeValue instanceof Number)) {
            throw new IllegalStateException("平台任务结果响应缺少状态码");
        }

        int code = ((Number) codeValue).intValue();
        if (code != TaskResultConstants.PLATFORM_SUCCESS_CODE) {
            // 新版统一响应使用 msg，兼容尚未升级平台的 message 字段
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

    /**
     * 统一 POST 调用
     *
     * @param platformUrl 平台根地址
     * @param path        接口路径
     * @param body        请求体对象（fastjson 序列化）
     * @param token       执行器 token，注册时为 null
     */
    private JSONObject post(String platformUrl,
                            String path,
                            Object body,
                            String token) {
        String baseUrl = resolveBaseUrl(platformUrl);
        String url = baseUrl + path;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        if (StringUtils.hasText(token)) {
            headers.set("Authorization", "Bearer " + token);
        }
        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(body), headers);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(url, entity, String.class);
        } catch (HttpStatusCodeException e) {
            throw new BusinessException(BusinessCode.OPERATION_ERROR.getCode(),
                    "平台接口 " + path + " 返回 HTTP " + e.getRawStatusCode()
                            + ": " + extractHttpError(e));
        } catch (ResourceAccessException e) {
            throw new BusinessException(BusinessCode.OPERATION_ERROR.getCode(),
                    "无法连接平台 " + baseUrl + "，请检查网络/防火墙及 agent.platform.url: "
                            + e.getMessage());
        } catch (Exception e) {
            throw new BusinessException(BusinessCode.OPERATION_ERROR.getCode(),
                    "调用平台接口 " + path + " 失败: " + e.getMessage());
        }

        String responseBody = response.getBody();
        if (!StringUtils.hasText(responseBody)) {
            throw new BusinessException(BusinessCode.SYSTEM_ERROR.getCode(),
                    "平台接口 " + path + " 返回为空");
        }

        JSONObject json;
        try {
            json = JSON.parseObject(responseBody);
        } catch (Exception e) {
            throw new BusinessException(BusinessCode.SYSTEM_ERROR.getCode(),
                    "平台接口 " + path + " 返回非 JSON: " + abbreviate(responseBody));
        }
        if (json == null || !json.containsKey("code")) {
            throw new BusinessException(BusinessCode.SYSTEM_ERROR.getCode(),
                    "平台响应格式异常（缺少 code 字段）: " + abbreviate(responseBody));
        }
        int code = json.getIntValue("code");
        if (code != 0) {
            String message = defaultMessage(json.getString("message"),
                    defaultMessage(json.getString("msg"), "平台返回错误码 " + code));
            throw new BusinessException(BusinessCode.OPERATION_ERROR.getCode(), message);
        }
        return json;
    }

    private String resolveBaseUrl(String platformUrl) {
        if (!StringUtils.hasText(platformUrl)) {
            throw new BusinessException(BusinessCode.PARAMS_ERROR.getCode(),
                    "未配置 agent.platform.url，无法连接平台端");
        }
        String baseUrl = platformUrl.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private String extractHttpError(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        if (StringUtils.hasText(body)) {
            try {
                JSONObject json = JSON.parseObject(body);
                if (json != null) {
                    String message = json.getString("message");
                    if (!StringUtils.hasText(message)) {
                        message = json.getString("msg");
                    }
                    if (StringUtils.hasText(message)) {
                        return message;
                    }
                }
            } catch (Exception ignored) {
                // 非 JSON 错误页，直接返回截断内容
            }
            return abbreviate(body);
        }
        HttpStatus status = e.getStatusCode();
        return status == null ? "" : status.getReasonPhrase();
    }

    private String defaultMessage(String message, String fallback) {
        return StringUtils.hasText(message) ? message : fallback;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
    }
}
