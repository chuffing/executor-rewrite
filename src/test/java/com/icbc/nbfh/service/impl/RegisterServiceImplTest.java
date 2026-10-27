package com.icbc.nbfh.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.icbc.nbfh.client.PlatformClient;
import com.icbc.nbfh.collector.RedisInfoCollector;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.RegisterRequestDTO;
import com.icbc.nbfh.entity.DTO.RegisterResultDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 两阶段注册：Redis 未就绪只注册执行器，Redis 就绪后二次注册并绑定实例
 */
class RegisterServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRegisterUnboundWhenRedisNotReady() throws Exception {
        AgentProperties properties = createProperties();
        PlatformClient platformClient = mock(PlatformClient.class);
        RedisInfoCollector redisInfoCollector = mock(RedisInfoCollector.class);

        when(redisInfoCollector.isRedisReady()).thenReturn(false);
        when(platformClient.register(any(RegisterRequestDTO.class), any(String.class)))
                .thenReturn(new RegisterResultDTO("token-001", "", false));

        RegisterServiceImpl service = new RegisterServiceImpl(
                properties, platformClient, redisInfoCollector, 9101);

        assertTrue(service.registerIfNeeded());
        assertTrue(service.isRegistered());
        assertTrue(service.hasToken());
        assertFalse(service.isBound());
        assertNull(service.getInstanceId());
        assertEquals("token-001", service.getToken());

        ArgumentCaptor<RegisterRequestDTO> captor =
                ArgumentCaptor.forClass(RegisterRequestDTO.class);
        verify(platformClient).register(captor.capture(),
                eq("http://127.0.0.1:8100"));
        RegisterRequestDTO request = captor.getValue();
        assertEquals("register-code", request.getRegisterCode());
        assertEquals("127.0.0.1", request.getHostIp());
        assertEquals("9101", request.getExecutorPort());
        assertEquals(Boolean.FALSE, request.getRedisReady());
        assertNull(request.getRedisInfo());

        // token 已落盘，且 bound=false 用于后续二次注册
        String content = new String(Files.readAllBytes(
                Paths.get(properties.getPlatform().getTokenFile())), StandardCharsets.UTF_8);
        JSONObject state = JSON.parseObject(content);
        assertEquals("token-001", state.getString("token"));
        assertEquals("", state.getString("instanceId"));
        assertFalse(state.getBooleanValue("bound"));

        // 30s 内重复调用不能再次请求平台
        assertTrue(service.registerIfNeeded());
        verify(platformClient, times(1)).register(any(RegisterRequestDTO.class), any(String.class));
    }

    @Test
    void shouldRegisterAgainAndBindWhenRedisBecomesReady() throws Exception {
        AgentProperties properties = createProperties();
        PlatformClient platformClient = mock(PlatformClient.class);
        RedisInfoCollector redisInfoCollector = mock(RedisInfoCollector.class);

        when(redisInfoCollector.isRedisReady()).thenReturn(false);
        when(platformClient.register(any(RegisterRequestDTO.class), any(String.class)))
                .thenReturn(new RegisterResultDTO("token-001", "", false));

        RegisterServiceImpl service = new RegisterServiceImpl(
                properties, platformClient, redisInfoCollector, 9101);
        assertTrue(service.registerIfNeeded());
        assertFalse(service.isBound());

        // 模拟 Redis 恢复，并等待注册重试间隔（实现下限 1s）
        Map<String, String> redisInfo = new LinkedHashMap<>();
        redisInfo.put("host", "127.0.0.1");
        redisInfo.put("tcp_port", "6379");
        redisInfo.put("role", "MASTER");
        redisInfo.put("version", "7.2.4");
        redisInfo.put("run_id", "0123456789abcdef0123456789abcdef01234567");
        when(redisInfoCollector.isRedisReady()).thenReturn(true);
        when(redisInfoCollector.collectRegistrationInfo()).thenReturn(redisInfo);
        when(platformClient.register(any(RegisterRequestDTO.class), any(String.class)))
                .thenReturn(new RegisterResultDTO("token-001", "instance-001", true));

        Thread.sleep(1100L);
        assertTrue(service.registerIfNeeded());
        assertTrue(service.isBound());
        assertEquals("instance-001", service.getInstanceId());

        ArgumentCaptor<RegisterRequestDTO> captor =
                ArgumentCaptor.forClass(RegisterRequestDTO.class);
        verify(platformClient, times(2)).register(captor.capture(), any(String.class));
        RegisterRequestDTO second = captor.getAllValues().get(1);
        assertEquals(Boolean.TRUE, second.getRedisReady());
        assertEquals("6379", second.getRedisInfo().get("tcp_port"));
        assertEquals("MASTER", second.getRedisInfo().get("role"));
    }

    @Test
    void shouldLoadBoundCredentialAndSkipPlatform() throws Exception {
        AgentProperties properties = createProperties();
        writeState(properties, "127.0.0.1", "9101", "instance-local", true);

        PlatformClient platformClient = mock(PlatformClient.class);
        RedisInfoCollector redisInfoCollector = mock(RedisInfoCollector.class);

        RegisterServiceImpl service = new RegisterServiceImpl(
                properties, platformClient, redisInfoCollector, 9101);

        assertTrue(service.registerIfNeeded());
        assertTrue(service.isBound());
        assertEquals("instance-local", service.getInstanceId());
        assertEquals("127.0.0.1:9101", service.getAgentId());
        verify(platformClient, never()).register(any(RegisterRequestDTO.class), any(String.class));
    }

    @Test
    void shouldReRegisterWhenSavedAddressChanged() throws Exception {
        AgentProperties properties = createProperties();
        writeState(properties, "10.0.0.9", "9101", "instance-old", true);

        PlatformClient platformClient = mock(PlatformClient.class);
        RedisInfoCollector redisInfoCollector = mock(RedisInfoCollector.class);
        when(redisInfoCollector.isRedisReady()).thenReturn(false);
        when(platformClient.register(any(RegisterRequestDTO.class), any(String.class)))
                .thenReturn(new RegisterResultDTO("token-new", "", false));

        RegisterServiceImpl service = new RegisterServiceImpl(
                properties, platformClient, redisInfoCollector, 9101);

        assertTrue(service.registerIfNeeded());
        assertFalse(service.isBound());
        assertEquals("token-new", service.getToken());
        verify(platformClient).register(any(RegisterRequestDTO.class),
                eq("http://127.0.0.1:8100"));
    }

    @Test
    void shouldReadLegacyCredentialAndReRegisterBinding() throws Exception {
        AgentProperties properties = createProperties();
        Path tokenFile = Paths.get(properties.getPlatform().getTokenFile());
        Files.createDirectories(tokenFile.getParent());
        Files.write(tokenFile,
                "legacy-agent|legacy-token".getBytes(StandardCharsets.UTF_8));

        PlatformClient platformClient = mock(PlatformClient.class);
        RedisInfoCollector redisInfoCollector = mock(RedisInfoCollector.class);
        when(redisInfoCollector.isRedisReady()).thenReturn(false);
        when(platformClient.register(any(RegisterRequestDTO.class), any(String.class)))
                .thenReturn(new RegisterResultDTO("legacy-token", "", false));

        RegisterServiceImpl service = new RegisterServiceImpl(
                properties, platformClient, redisInfoCollector, 9101);

        assertTrue(service.registerIfNeeded());
        assertTrue(service.hasToken());
        assertFalse(service.isBound());
        verify(platformClient).register(any(RegisterRequestDTO.class), any(String.class));
    }

    @Test
    void shouldRejectWhenPlatformUrlMissing() throws Exception {
        AgentProperties properties = createProperties();
        properties.getPlatform().setUrl(" ");
        writeState(properties, "127.0.0.1", "9101", "instance-local", true);

        PlatformClient platformClient = mock(PlatformClient.class);
        RedisInfoCollector redisInfoCollector = mock(RedisInfoCollector.class);

        RegisterServiceImpl service = new RegisterServiceImpl(
                properties, platformClient, redisInfoCollector, 9101);

        assertFalse(service.registerIfNeeded());
        assertFalse(service.isRegistered());
        verify(platformClient, never()).register(any(RegisterRequestDTO.class), any(String.class));
    }

    @Test
    void shouldInvalidateTokenAndDeleteFile() throws Exception {
        AgentProperties properties = createProperties();
        writeState(properties, "127.0.0.1", "9101", "instance-local", true);

        RegisterServiceImpl service = new RegisterServiceImpl(
                properties,
                mock(PlatformClient.class),
                mock(RedisInfoCollector.class),
                9101);
        assertTrue(service.registerIfNeeded());

        service.invalidateToken();

        assertFalse(service.isRegistered());
        assertFalse(service.isBound());
        assertFalse(Files.exists(Paths.get(properties.getPlatform().getTokenFile())));
    }

    private AgentProperties createProperties() {
        AgentProperties properties = new AgentProperties();
        properties.setAgentName("redis-agent");
        properties.setHostIp("127.0.0.1");
        properties.setRegisterRetryInterval(1000);
        properties.getPlatform().setUrl("http://127.0.0.1:8100");
        properties.getPlatform().setRegisterCode("register-code");
        properties.getPlatform().setTokenFile(
                tempDir.resolve("agent-token.dat").toString());
        properties.getRedis().setHost("127.0.0.1");
        properties.getRedis().setPort(6379);
        return properties;
    }

    private void writeState(AgentProperties properties,
                            String hostIp,
                            String executorPort,
                            String instanceId,
                            boolean bound) throws Exception {
        JSONObject state = new JSONObject(true);
        state.put("token", "token-local");
        state.put("hostIp", hostIp);
        state.put("executorPort", executorPort);
        state.put("instanceId", instanceId);
        state.put("bound", bound);
        Path tokenFile = Paths.get(properties.getPlatform().getTokenFile());
        Files.createDirectories(tokenFile.getParent());
        Files.write(tokenFile, state.toJSONString().getBytes(StandardCharsets.UTF_8));
    }
}
