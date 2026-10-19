package com.icbc.nbfh.controller;

import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.Enum.ParameterErrorCode;
import com.icbc.nbfh.common.Enum.TaskSubmitStatus;
import com.icbc.nbfh.common.ResultBean;
import com.icbc.nbfh.common.constant.ConfigFileConstants;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.common.constant.ValidationConstants;
import com.icbc.nbfh.common.exception.ParameterException;
import com.icbc.nbfh.config.handler.GlobalExceptionHandler;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.entity.VO.TaskSubmitResultVO;
import com.icbc.nbfh.service.AgentTaskService;
import com.icbc.nbfh.service.ConfigFileService;
import com.icbc.nbfh.service.VersionPackageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** 文件必须在请求线程中保存完毕，异步线程只接收执行器生成的路径。 */
class DeployConfigControllerTest {

    @TempDir
    Path directory;

    private AgentTaskService tasks;
    private ConfigFileService files;
    private AgentTaskController controller;
    private PlatformTaskDTO task;
    private MockMultipartFile redis;
    private MockMultipartFile sentinel;

    /** 准备两个摘要和文件，文件存储使用替身以检查提交及清理时机。 */
    @BeforeEach
    void prepare() throws Exception {
        tasks = mock(AgentTaskService.class);
        files = mock(ConfigFileService.class);
        controller = new AgentTaskController(tasks, mock(VersionPackageService.class), files);
        task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610170001");
        task.setStepNo(3);
        task.setOperationType(OperationType.DEPLOY_CONFIG.name());
        Map<String, String> params = new HashMap<>();
        char[] sha256 = new char[64];
        Arrays.fill(sha256, 'a');
        params.put(TaskParamConstants.REDIS_CONFIG_SHA256, new String(sha256));
        params.put(TaskParamConstants.SENTINEL_CONFIG_SHA256, new String(sha256));
        task.setParams(params);
        redis = new MockMultipartFile("redisFile", "redis.conf", "text/plain", new byte[]{1});
        sentinel = new MockMultipartFile("sentinelFile", "sentinel.conf", "text/plain", new byte[]{2});
        when(files.saveAndValidate(anyString(), anyString(), anyString(), any(), any())).thenReturn(directory);
    }

    /** 成功入队后不能由Controller删除后台仍需读取的文件。 */
    @Test
    void shouldSubmitInternalPathsAndKeepStagedFiles() throws Exception {
        Map<String, String> original = task.getParams();
        assertEquals(HttpStatus.ACCEPTED.value(),
                controller.receiveConfigDeployTask(task, redis, sentinel).getCode());
        assertEquals(2, original.size());
        assertEquals(directory.resolve(ConfigFileConstants.REDIS_FILE_NAME).toString(),
                task.getParams().get(TaskParamConstants.REDIS_CONFIG_LOCAL_PATH));
        assertEquals(directory.resolve(ConfigFileConstants.SENTINEL_FILE_NAME).toString(),
                task.getParams().get(TaskParamConstants.SENTINEL_CONFIG_LOCAL_PATH));
        verify(tasks).submitTask(task);
        verify(files, never()).deleteStagedFiles(any());
    }

    /** 普通JSON任务接口不接受配置部署。 */
    @Test
    void shouldRejectOrdinaryTaskEndpoint() {
        assertThrows(ParameterException.class, () -> controller.receiveTask(task));
        verifyNoInteractions(tasks, files);
    }

    /** 参数整体缺失时不能保存文件。 */
    @Test
    void shouldRejectMissingParameters() {
        task.setParams(null);
        assertThrows(ParameterException.class, () -> controller.receiveConfigDeployTask(task, redis, sentinel));
        verifyNoInteractions(tasks, files);
    }

    /** 两个摘要均需满足既有SHA256格式。 */
    @ParameterizedTest
    @ValueSource(strings = {TaskParamConstants.REDIS_CONFIG_SHA256, TaskParamConstants.SENTINEL_CONFIG_SHA256})
    void shouldRejectMalformedChecksum(String key) {
        task.getParams().put(key, "invalid");
        assertThrows(ParameterException.class, () -> controller.receiveConfigDeployTask(task, redis, sentinel));
        verifyNoInteractions(tasks, files);
    }

    /** 配置正文和外部暂存路径不再作为请求参数接收。 */
    @ParameterizedTest
    @ValueSource(strings = {"redisConfigContent", "sentinelConfigContent", TaskParamConstants.REDIS_CONFIG_LOCAL_PATH,
            TaskParamConstants.SENTINEL_CONFIG_LOCAL_PATH})
    void shouldRejectUnexpectedParameters(String key) {
        task.getParams().put(key, "uncontrolled");
        assertThrows(ParameterException.class, () -> controller.receiveConfigDeployTask(task, redis, sentinel));
        verifyNoInteractions(tasks, files);
    }

    /** 任一文件为空或缺失时都不能保存或提交任务。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRejectEmptyOrMissingFile(boolean redisFile) {
        MockMultipartFile empty = new MockMultipartFile("file", new byte[0]);
        for (MultipartFile invalid : new MultipartFile[]{null, empty}) {
            ParameterException exception = assertThrows(ParameterException.class, () ->
                    controller.receiveConfigDeployTask(task, redisFile ? invalid : redis, redisFile ? sentinel : invalid));
            assertEquals(ParameterErrorCode.CONFIG_FILE_INVALID.getCode(), exception.getCode());
        }
        verifyNoInteractions(tasks, files);
    }

    /** 任一文件超过1 MiB时在保存前拒绝。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRejectOversizedFile(boolean redisFile) {
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.getSize()).thenReturn((long) ValidationConstants.MAX_CONFIG_FILE_SIZE_BYTES + 1);
        assertThrows(ParameterException.class, () -> controller.receiveConfigDeployTask(task,
                redisFile ? oversized : redis, redisFile ? sentinel : oversized));
        verifyNoInteractions(tasks, files);
    }

    /** 请求缺少任一multipart文件时返回400，不触发保存和任务提交。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRejectMissingMultipartPart(boolean redisFile) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        MockMultipartFile metadata = new MockMultipartFile("task", "", "application/json",
                new ObjectMapper().writeValueAsBytes(task));
        assertEquals(400, mvc.perform(multipart("/api/agent/config/deploy")
                .file(metadata).file(redisFile ? sentinel : redis)).andReturn().getResponse().getStatus());
        verifyNoInteractions(tasks, files);
    }

    /** 队列拒绝时清理已保存的文件，沿用503结果。 */
    @Test
    void shouldCleanWhenQueueIsFull() throws Exception {
        doThrow(new RejectedExecutionException()).when(tasks).submitTask(any());
        ResultBean<TaskSubmitResultVO> response =
                controller.receiveConfigDeployTask(task, redis, sentinel);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), response.getCode());
        assertNotNull(response.getData());
        assertEquals(TaskSubmitStatus.BUSY.name(),
                response.getData().getStatus());
        verify(files).deleteStagedFiles(directory);
    }

    /** 其他提交异常同样清理暂存文件并继续抛出原异常。 */
    @Test
    void shouldCleanWhenSubmissionFails() {
        doThrow(new IllegalStateException("test-submit-failure")).when(tasks).submitTask(any());
        assertThrows(IllegalStateException.class, () -> controller.receiveConfigDeployTask(task, redis, sentinel));
        verify(files).deleteStagedFiles(directory);
    }

    /** 保存校验失败时不应把任务交给后台。 */
    @Test
    void shouldNotSubmitFailedUpload() throws Exception {
        when(files.saveAndValidate(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new ParameterException(ParameterErrorCode.CONFIG_CHECKSUM_MISMATCH, "摘要不一致"));
        assertThrows(ParameterException.class, () -> controller.receiveConfigDeployTask(task, redis, sentinel));
        verifyNoInteractions(tasks);
    }
}
