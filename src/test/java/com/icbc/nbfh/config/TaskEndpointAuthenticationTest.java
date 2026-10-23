package com.icbc.nbfh.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.config.handler.GlobalExceptionHandler;
import com.icbc.nbfh.config.interceptor.PlatformTokenInterceptor;
import com.icbc.nbfh.controller.AgentTaskController;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.service.AgentTaskService;
import com.icbc.nbfh.service.ConfigFileService;
import com.icbc.nbfh.service.RegisterService;
import com.icbc.nbfh.service.VersionPackageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实MVC拦截器配置验证三个任务入口，不连接平台或启动后台任务。 */
@WebMvcTest(AgentTaskController.class)
@ContextConfiguration(classes = {AgentTaskController.class, WebConfig.class,
        PlatformTokenInterceptor.class, GlobalExceptionHandler.class})
class TaskEndpointAuthenticationTest {

    private static final String SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000";

    @TempDir
    Path root;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RegisterService registration;

    @MockBean
    private AgentTaskService tasks;

    @MockBean
    private ConfigFileService configFiles;

    @MockBean
    private VersionPackageService packages;

    /** 模拟已注册执行器和文件接收结果，实际不写入配置或安装包。 */
    @BeforeEach
    void prepare() throws Exception {
        when(registration.isRegistered()).thenReturn(true);
        when(registration.getToken()).thenReturn("test-token");
        when(configFiles.saveAndValidate(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(root.resolve("config-task"));
        when(packages.saveAndValidate(anyString(), anyString(), any()))
                .thenReturn(root.resolve("package.tar.gz"));
    }

    /** 缺少Token时，普通任务及两种上传均不得进入保存或调度逻辑。 */
    @ParameterizedTest
    @ValueSource(strings = {"/api/agent/task", "/api/agent/config/deploy", "/api/agent/version/deploy"})
    void shouldRejectMissingToken(String path) throws Exception {
        mvc.perform(taskRequest(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(tasks, configFiles, packages);
    }

    /** 错误Token不能绕过任一任务入口的身份校验。 */
    @ParameterizedTest
    @ValueSource(strings = {"/api/agent/task", "/api/agent/config/deploy", "/api/agent/version/deploy"})
    void shouldRejectWrongToken(String path) throws Exception {
        mvc.perform(taskRequest(path).header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tasks, configFiles, packages);
    }

    /** 未注册时，即使请求携带相同Token也不能进入业务。 */
    @ParameterizedTest
    @ValueSource(strings = {"/api/agent/task", "/api/agent/config/deploy", "/api/agent/version/deploy"})
    void shouldRejectUnregisteredAgent(String path) throws Exception {
        when(registration.isRegistered()).thenReturn(false);
        mvc.perform(taskRequest(path).header("Authorization", "Bearer test-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tasks, configFiles, packages);
    }

    /** 正确身份可提交任务，HTTP返回200且响应体业务码为202。 */
    @ParameterizedTest
    @ValueSource(strings = {"/api/agent/task", "/api/agent/config/deploy", "/api/agent/version/deploy"})
    void shouldAcceptCorrectToken(String path) throws Exception {
        mvc.perform(taskRequest(path).header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(202));
        verify(tasks).submitTask(any(PlatformTaskDTO.class));
    }

    /** 构造合法报文，保证测试针对鉴权路径匹配，而非缺少请求参数。 */
    private MockHttpServletRequestBuilder taskRequest(String path) throws Exception {
        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610170001");
        task.setStepNo(1);
        if ("/api/agent/task".equals(path)) {
            task.setOperationType(OperationType.START_REDIS.name());
            return post(path).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(task));
        }

        Map<String, String> params = new HashMap<>();
        task.setParams(params);
        if ("/api/agent/config/deploy".equals(path)) {
            task.setOperationType(OperationType.DEPLOY_CONFIG.name());
            params.put(TaskParamConstants.REDIS_CONFIG_SHA256, SHA256);
            params.put(TaskParamConstants.SENTINEL_CONFIG_SHA256, SHA256);
            return multipart(path)
                    .file(new MockMultipartFile("task", "", MediaType.APPLICATION_JSON_VALUE,
                            objectMapper.writeValueAsBytes(task)))
                    .file(new MockMultipartFile("redisFile", new byte[]{1}))
                    .file(new MockMultipartFile("sentinelFile", new byte[]{2}));
        }

        task.setOperationType(OperationType.DEPLOY_VERSION.name());
        params.put(TaskParamConstants.PACKAGE_ID, "1");
        params.put(TaskParamConstants.TARGET_VERSION, "6.2.19");
        params.put(TaskParamConstants.PACKAGE_SHA256, SHA256);
        return multipart(path)
                .file(new MockMultipartFile("task", "", MediaType.APPLICATION_JSON_VALUE,
                        objectMapper.writeValueAsBytes(task)))
                .file(new MockMultipartFile("file", "redis-6.2.19.tar.gz",
                        MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1}));
    }
}
