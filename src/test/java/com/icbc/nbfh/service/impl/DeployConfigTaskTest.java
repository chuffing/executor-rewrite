package com.icbc.nbfh.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.Enum.TaskResultStatus;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.config.handler.GlobalExceptionHandler;
import com.icbc.nbfh.controller.AgentTaskController;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;
import com.icbc.nbfh.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** 真实multipart接收、异步文件部署及结果上报；全部文件位于测试临时目录。 */
class DeployConfigTaskTest {

    @TempDir
    Path root;

    /** 入队后暂存文件须保留到后台执行完毕，部署前篡改则上报失败并清理。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldKeepFilesUntilBackgroundTaskFinishes(boolean unchanged) throws Exception {
        AgentProperties properties = properties();
        ConfigFileService files = new ConfigFileServiceImpl(properties);
        DeployConfigService realDeployment = new DeployConfigServiceImpl(properties, files);
        DeployConfigService deployment = mock(DeployConfigService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(deployment.deployConfig(anyMap())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return realDeployment.deployConfig(invocation.getArgument(0));
        });
        TaskResultReportService resultReports =
                mock(TaskResultReportService.class);
        RegisterService registration = mock(RegisterService.class);
        when(registration.getAgentId()).thenReturn("test-agent");
        AgentTaskServiceImpl tasks = new AgentTaskServiceImpl(
                mock(PrecheckHostService.class), mock(StopRedisService.class), mock(StartRedisService.class),
                mock(StopSentinelService.class), mock(StartSentinelService.class), mock(RestoreConfigService.class),
                mock(com.icbc.nbfh.service.RecoveryTopologyVerifyService.class),
                deployment, mock(DeployVersionService.class), mock(SwitchVersionService.class),
                resultReports, registration);
        ThreadPoolExecutor worker = (ThreadPoolExecutor) ReflectionTestUtils.getField(tasks, "executor");
        try {
            AgentTaskController controller = new AgentTaskController(tasks, mock(VersionPackageService.class), files);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            byte[] redis = "# deployment-private-marker\r\n\r\n  port 6379\n".getBytes(StandardCharsets.UTF_8);
            byte[] sentinel = "# Sentinel注释\r\nport 26379\r\n".getBytes(StandardCharsets.UTF_16);
            PlatformTaskDTO task = task(redis, sentinel);
            MockMultipartFile metadata = new MockMultipartFile("task", "", MediaType.APPLICATION_JSON_VALUE,
                    new ObjectMapper().writeValueAsBytes(task));
            assertEquals(200, mvc.perform(multipart("/api/agent/config/deploy").file(metadata)
                    .file(new MockMultipartFile("redisFile", "redis.conf", "application/octet-stream", redis))
                    .file(new MockMultipartFile("sentinelFile", "sentinel.conf", "application/octet-stream", sentinel)))
                    .andReturn().getResponse().getStatus());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Path directory;
            try (Stream<Path> requests = Files.list(root.resolve("configfiles/staging"))) {
                directory = requests.findFirst().orElseThrow(AssertionError::new);
            }
            assertArrayEquals(redis, Files.readAllBytes(directory.resolve("redis.conf")));
            assertArrayEquals(sentinel, Files.readAllBytes(directory.resolve("sentinel.conf")));
            if (!unchanged) {
                Files.write(directory.resolve("redis.conf"), new byte[]{1});
            }
            release.countDown();

            ArgumentCaptor<TaskResultReportDTO> report = ArgumentCaptor.forClass(TaskResultReportDTO.class);
            verify(resultReports, timeout(5000))
                    .saveAndReport(report.capture());
            assertEquals(task.getTaskNo(), report.getValue().getTaskNo());
            assertEquals(task.getStepNo(), report.getValue().getStepNo());
            assertEquals(OperationType.DEPLOY_CONFIG.name(), report.getValue().getOperationType());
            assertEquals(unchanged ? TaskResultStatus.SUCCESS.name() : TaskResultStatus.FAILED.name(),
                    report.getValue().getStatus());
            assertFalse(new ObjectMapper().writeValueAsString(report.getValue()).contains("deployment-private-marker"));
            assertFalse(Files.exists(directory));
            if (unchanged) {
                assertArrayEquals(redis, Files.readAllBytes(root.resolve("redis.conf")));
                assertArrayEquals(sentinel, Files.readAllBytes(root.resolve("sentinel.conf")));
            } else {
                assertFalse(Files.exists(root.resolve("redis.conf")));
                assertFalse(Files.exists(root.resolve("sentinel.conf")));
            }
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** HTTP上传的任一摘要不一致时返回400，不提交异步任务且不残留目录。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRejectEitherUploadChecksum(boolean redisChecksum) throws Exception {
        AgentProperties properties = properties();
        AgentTaskService tasks = mock(AgentTaskService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentTaskController(tasks,
                mock(VersionPackageService.class), new ConfigFileServiceImpl(properties)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        byte[] redis = {1, 2, 3};
        byte[] sentinel = {4, 5, 6};
        PlatformTaskDTO request = task(redis, sentinel);
        request.getParams().put(redisChecksum ? TaskParamConstants.REDIS_CONFIG_SHA256
                : TaskParamConstants.SENTINEL_CONFIG_SHA256, sha256(new byte[]{99}));
        assertEquals(200, mvc.perform(multipart("/api/agent/config/deploy")
                .file(new MockMultipartFile("task", "", "application/json", new ObjectMapper().writeValueAsBytes(request)))
                .file(new MockMultipartFile("redisFile", redis)).file(new MockMultipartFile("sentinelFile", sentinel)))
                .andReturn().getResponse().getStatus());
        verifyNoInteractions(tasks);
        try (Stream<Path> requests = Files.list(root.resolve("configfiles/staging"))) {
            assertEquals(0, requests.count());
        }
    }

    /** 真正保存成功后遇到异步队列拒绝，Controller应清理本次文件。 */
    @Test
    void shouldCleanRealStagedFilesWhenQueueRejectsTask() throws Exception {
        AgentProperties properties = properties();
        AgentTaskService tasks = mock(AgentTaskService.class);
        doThrow(new java.util.concurrent.RejectedExecutionException()).when(tasks).submitTask(any());
        AgentTaskController controller = new AgentTaskController(tasks, mock(VersionPackageService.class),
                new ConfigFileServiceImpl(properties));
        byte[] redis = {1};
        byte[] sentinel = {2};
        assertEquals(503, controller.receiveConfigDeployTask(task(redis, sentinel),
                new MockMultipartFile("redisFile", redis), new MockMultipartFile("sentinelFile", sentinel))
                .getCode());
        try (Stream<Path> requests = Files.list(root.resolve("configfiles/staging"))) {
            assertEquals(0, requests.count());
        }
    }

    /** 创建预先存在的根目录及固定正式配置路径。 */
    private AgentProperties properties() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getOperation().setConfigFileDir(Files.createDirectory(root.resolve("configfiles")).toString());
        properties.getRedis().setConfigFile(root.resolve("redis.conf").toString());
        properties.getSentinel().setConfigFile(root.resolve("sentinel.conf").toString());
        properties.getRedis().setBackupFile(root.resolve("redis.backup").toString());
        properties.getSentinel().setBackupFile(root.resolve("sentinel.backup").toString());
        properties.getPlatform().setUrl("http://test-platform");
        return properties;
    }

    /** 任务JSON仅包含两个文件摘要。 */
    private PlatformTaskDTO task(byte[] redis, byte[] sentinel) throws Exception {
        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610170001");
        task.setStepNo(3);
        task.setOperationType(OperationType.DEPLOY_CONFIG.name());
        Map<String, String> params = new HashMap<>();
        params.put(TaskParamConstants.REDIS_CONFIG_SHA256, sha256(redis));
        params.put(TaskParamConstants.SENTINEL_CONFIG_SHA256, sha256(sentinel));
        task.setParams(params);
        return task;
    }

    /** 按原始字节生成摘要。 */
    private String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
