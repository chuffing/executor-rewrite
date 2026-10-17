package com.icbc.nbfh.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.net.NetUtil;
import cn.hutool.system.HostInfo;
import cn.hutool.system.OsInfo;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.RegisterRequestDTO;
import com.icbc.nbfh.service.RegisterService;
import com.icbc.nbfh.client.PlatformClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class RegisterServiceImpl implements RegisterService {

    private final AgentProperties properties;

    private final PlatformClient platformClient;

    private final int agentPort;

    private volatile String token;

    private volatile String agentId;

    public RegisterServiceImpl(AgentProperties properties,
                               PlatformClient platformClient,
                               @Value("${server.port:9101}") int agentPort) {
        this.properties = properties;
        this.platformClient = platformClient;
        this.agentPort = agentPort;
    }

    @Override
    public synchronized boolean registerIfNeeded() {

        // 1. 已经注册时直接返回。
        if (isRegistered()) {
            return true;
        }

        // 2. 校验平台通信配置。
        String platformUrl = properties.getPlatform().getUrl();
        String tokenFilePath = properties.getPlatform().getTokenFile();

        if (platformUrl == null || platformUrl.trim().isEmpty()) {
            log.error("注册失败：agent.platform.url未配置");
            return false;
        }

        if (tokenFilePath == null || tokenFilePath.trim().isEmpty()) {
            log.error("注册失败：agent.platform.token-file未配置");
            return false;
        }

        // 3. 优先读取本地注册凭证。
        File tokenFile = new File(tokenFilePath.trim());

        try {
            if (loadLocalCredential(tokenFile)) {
                return true;
            }
        } catch (Exception e) {
            log.warn("本地凭证文件读取失败，将重新注册", e);
        }

        // 4. 本地没有有效凭证时校验注册码。
        String registerCode = properties.getPlatform().getRegisterCode();

        if (registerCode == null || registerCode.trim().isEmpty()) {
            log.error("注册失败：agent.platform.register-code未配置");
            return false;
        }

        // 5. 调用平台注册接口。
        try {
            RegisterRequestDTO request =
                    buildRegisterRequest(registerCode);

            Map<String, Object> response =
                    platformClient.register(
                            request,
                            platformUrl.trim()
                    );

            Object data =
                    response == null ? null : response.get("data");

            if (!(data instanceof Map)) {
                log.error(
                        "注册失败：平台返回data格式不正确，response={}",
                        response
                );
                return false;
            }

            Map<?, ?> dataMap = (Map<?, ?>) data;

            Object tokenValue = dataMap.get("token");
            Object agentIdValue = dataMap.get("agentId");

            if (!(tokenValue instanceof String)
                    || !(agentIdValue instanceof String)) {

                log.error("注册失败：平台未返回有效的token和agentId");
                return false;
            }

            String registeredToken =
                    ((String) tokenValue).trim();

            String registeredAgentId =
                    ((String) agentIdValue).trim();

            if (registeredToken.isEmpty()
                    || registeredAgentId.isEmpty()) {

                log.error("注册失败：平台返回的token或agentId为空");
                return false;
            }

            // 6. 平台返回内容校验通过后，再更新本地注册状态。
            token = registeredToken;
            agentId = registeredAgentId;

            saveLocalCredential(tokenFile);

            log.info("执行器注册成功，agentId={}", agentId);
            return true;

        } catch (Exception e) {
            log.error("执行器注册请求失败", e);
            return false;
        }
    }

    /**
     * 组装平台注册请求。
     */
    private RegisterRequestDTO buildRegisterRequest(
            String registerCode) {

        String hostname = new HostInfo().getName();
        String configuredName = properties.getAgentName();

        RegisterRequestDTO request =
                new RegisterRequestDTO();

        request.setRegisterCode(registerCode.trim());

        request.setAgentName(
                configuredName == null
                        || configuredName.trim().isEmpty()
                        ? hostname
                        : configuredName.trim()
        );

        request.setHostname(hostname);
        request.setIp(NetUtil.getLocalhostStr());
        request.setAgentPort(agentPort);
        request.setOs(new OsInfo().getName());

        AgentProperties.RedisConfig redis =
                properties.getRedis();

        Map<String, String> redisInfo =
                new HashMap<>();

        redisInfo.put("host", redis.getHost());
        redisInfo.put(
                "port",
                String.valueOf(redis.getPort())
        );

        request.setRedisInfo(redisInfo);
        return request;
    }

    /**
     * 从本地文件读取注册凭证。
     */
    private boolean loadLocalCredential(File tokenFile) {

        if (!tokenFile.isFile()) {
            return false;
        }

        String content = FileUtil.readString(
                tokenFile,
                StandardCharsets.UTF_8
        );

        String[] parts = content.split("\\|", -1);

        if (parts.length != 2
                || parts[0].trim().isEmpty()
                || parts[1].trim().isEmpty()) {

            log.warn("本地凭证文件格式不正确，将重新注册");
            return false;
        }

        agentId = parts[0].trim();
        token = parts[1].trim();

        log.info(
                "已读取本地执行器凭证，agentId={}",
                agentId
        );
        return true;
    }

    /**
     * 保存平台注册凭证。
     */
    private void saveLocalCredential(File tokenFile) {

        try {
            FileUtil.writeString(
                    agentId + "|" + token,
                    tokenFile,
                    StandardCharsets.UTF_8
            );
        } catch (Exception e) {
            log.warn(
                    "注册成功，但本地凭证文件写入失败，agentId={}",
                    agentId,
                    e
            );
        }
    }

    @Override
    public boolean isRegistered() {
        return agentId != null
                && !agentId.trim().isEmpty()
                && token != null
                && !token.trim().isEmpty();
    }

    @Override
    public String getToken() {
        return token;
    }

    @Override
    public String getAgentId() {
        return agentId;
    }
}
