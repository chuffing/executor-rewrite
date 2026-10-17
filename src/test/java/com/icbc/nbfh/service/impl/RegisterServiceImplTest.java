package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.RegisterRequestDTO;
import com.icbc.nbfh.client.PlatformClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegisterServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRegisterAndKeepCredentialInMemory() {

        // 1. 准备注册配置和平台返回数据。
        AgentProperties properties =
                createProperties();

        PlatformClient platformClient =
                mock(PlatformClient.class);

        Map<String, Object> data = new HashMap<>();
        data.put("agentId", "agent-001");
        data.put("token", "token-001");

        Map<String, Object> response = new HashMap<>();
        response.put("data", data);

        when(platformClient.register(
                any(RegisterRequestDTO.class),
                eq("http://127.0.0.1:8100")
        )).thenReturn(response);

        RegisterServiceImpl registerService =
                new RegisterServiceImpl(
                        properties,
                        platformClient,
                        9101
                );

        // 2. 执行注册。
        assertTrue(registerService.registerIfNeeded());

        // 3. 验证注册状态和凭证。
        assertTrue(registerService.isRegistered());

        assertEquals(
                "agent-001",
                registerService.getAgentId()
        );

        assertEquals(
                "token-001",
                registerService.getToken()
        );

        // 4. 再次调用时不能重复请求平台。
        assertTrue(registerService.registerIfNeeded());

        verify(platformClient).register(
                any(RegisterRequestDTO.class),
                eq("http://127.0.0.1:8100")
        );
    }

    @Test
    void shouldLoadCompleteLocalCredential()
            throws Exception {

        // 1. 准备本地凭证文件。
        AgentProperties properties =
                createProperties();

        Files.write(
                Paths.get(
                        properties.getPlatform()
                                .getTokenFile()
                ),
                "agent-local|token-local"
                        .getBytes(StandardCharsets.UTF_8)
        );

        PlatformClient platformClient =
                mock(PlatformClient.class);

        RegisterServiceImpl registerService =
                new RegisterServiceImpl(
                        properties,
                        platformClient,
                        9101
                );

        // 2. 从本地文件恢复注册状态。
        assertTrue(registerService.registerIfNeeded());
        assertTrue(registerService.isRegistered());

        assertEquals(
                "agent-local",
                registerService.getAgentId()
        );

        assertEquals(
                "token-local",
                registerService.getToken()
        );

        // 3. 已有本地凭证时不请求平台注册。
        verify(platformClient, never()).register(
                any(RegisterRequestDTO.class),
                any(String.class)
        );
    }

    @Test
    void shouldRejectCredentialWhenPlatformUrlMissing()
            throws Exception {

        // 1. 即使本地有凭证，也必须配置平台地址。
        AgentProperties properties =
                createProperties();

        properties.getPlatform().setUrl(" ");

        Files.write(
                Paths.get(
                        properties.getPlatform()
                                .getTokenFile()
                ),
                "agent-local|token-local"
                        .getBytes(StandardCharsets.UTF_8)
        );

        PlatformClient platformClient =
                mock(PlatformClient.class);

        RegisterServiceImpl registerService =
                new RegisterServiceImpl(
                        properties,
                        platformClient,
                        9101
                );

        // 2. 平台地址无效时不能建立注册状态。
        assertFalse(registerService.registerIfNeeded());
        assertFalse(registerService.isRegistered());

        verify(platformClient, never()).register(
                any(RegisterRequestDTO.class),
                any(String.class)
        );
    }

    private AgentProperties createProperties() {

        AgentProperties properties =
                new AgentProperties();

        properties.setAgentName("redis-agent");

        properties.getPlatform().setUrl(
                "http://127.0.0.1:8100"
        );

        properties.getPlatform().setRegisterCode(
                "register-code"
        );

        properties.getPlatform().setTokenFile(
                tempDir.resolve("agent-token.dat")
                        .toString()
        );

        return properties;
    }
}


