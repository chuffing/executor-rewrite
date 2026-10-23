package com.icbc.nbfh.controller;

import com.icbc.nbfh.service.ConfigFileService;
import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.Enum.ParameterErrorCode;
import com.icbc.nbfh.common.Enum.TaskSubmitStatus;
import com.icbc.nbfh.common.ResultBean;
import com.icbc.nbfh.common.exception.ParameterException;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.entity.VO.TaskSubmitResultVO;
import com.icbc.nbfh.service.AgentTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.concurrent.RejectedExecutionException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AgentTaskControllerTest {

    @Test
    void shouldAcceptTopologyVerificationWithExpectedRole() {
        AgentTaskService agentTaskService = mock(AgentTaskService.class);
        AgentTaskController controller = new AgentTaskController(
                agentTaskService,
                mock(com.icbc.nbfh.service.VersionPackageService.class),
                mock(ConfigFileService.class)
        );

        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610210004");
        task.setStepNo(1);
        task.setOperationType(OperationType.VERIFY_TOPOLOGY.name());
        Map<String, String> params = new HashMap<>();
        params.put("expectedMasterHostIp", "10.0.1.1");
        params.put("expectedRole", "MASTER");
        task.setParams(params);

        ResultBean<TaskSubmitResultVO> response = controller.receiveTask(task);

        assertEquals(HttpStatus.ACCEPTED.value(), response.getCode());
        verify(agentTaskService).submitTask(task);
    }

    @Test
    void shouldAcceptRestoreConfigWithTwoBackupChecksums() {
        AgentTaskService agentTaskService = mock(AgentTaskService.class);
        AgentTaskController controller = new AgentTaskController(
                agentTaskService,
                mock(com.icbc.nbfh.service.VersionPackageService.class),
                mock(ConfigFileService.class)
        );

        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610210001");
        task.setStepNo(4);
        task.setOperationType(OperationType.RESTORE_CONFIG.name());
        Map<String, String> params = new HashMap<>();
        params.put("redisConfigSha256", buildSha256('a'));
        params.put("sentinelConfigSha256", buildSha256('b'));
        task.setParams(params);

        ResultBean<TaskSubmitResultVO> response = controller.receiveTask(task);

        assertEquals(HttpStatus.ACCEPTED.value(), response.getCode());
        verify(agentTaskService).submitTask(task);
    }

    /**
     * 验证合法任务提交后返回任务接收结果。
     */
    @Test
    void shouldReturnAcceptedResultAfterSubmittingTask() {

        // 1. 准备Controller和任务参数。
        AgentTaskService agentTaskService =
                mock(AgentTaskService.class);

        AgentTaskController controller =
                new AgentTaskController(agentTaskService,
                        mock(com.icbc.nbfh.service.VersionPackageService.class), mock(ConfigFileService.class));

        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610160001");
        task.setStepNo(1);
        task.setOperationType(OperationType.START_REDIS.name());

        // 2. 提交任务。
        ResultBean<TaskSubmitResultVO> response =
                controller.receiveTask(task);

        // 3. 验证任务提交和响应内容。
        verify(agentTaskService).submitTask(task);

        assertEquals(HttpStatus.ACCEPTED.value(), response.getCode());
        assertNotNull(response.getData());
        assertEquals("TASK202610160001", response.getData().getTaskNo());
        assertEquals(
                TaskSubmitStatus.ACCEPTED.name(),
                response.getData().getStatus()
        );
        assertEquals("任务已接收", response.getMsg());
    }

    /**
     * 验证线程池拒绝任务时，通过统一响应体返回503业务码。
     */
    @Test
    void shouldReturnBusyResultWhenTaskQueueIsFull() {

        // 1. 准备Controller和合法任务参数。
        AgentTaskService agentTaskService =
                mock(AgentTaskService.class);

        AgentTaskController controller =
                new AgentTaskController(agentTaskService,
                        mock(com.icbc.nbfh.service.VersionPackageService.class),
                        mock(ConfigFileService.class));

        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610160002");
        task.setStepNo(1);
        task.setOperationType(OperationType.START_REDIS.name());

        doThrow(new RejectedExecutionException())
                .when(agentTaskService).submitTask(task);

        // 2. 提交任务并获取统一响应体。
        ResultBean<TaskSubmitResultVO> response =
                controller.receiveTask(task);

        // 3. 验证业务码、提示信息和响应数据保持一致。
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), response.getCode());
        assertEquals("执行器繁忙，任务未接收", response.getMsg());
        assertNotNull(response.getData());
        assertEquals("TASK202610160002", response.getData().getTaskNo());
        assertEquals(TaskSubmitStatus.BUSY.name(), response.getData().getStatus());
    }

    /**
     * 验证任务编号不合法时抛出统一参数异常。
     */
    @Test
    void shouldThrowParameterExceptionWhenTaskNoIsInvalid() {

        // 1. 准备Controller和错误任务参数。
        AgentTaskService agentTaskService =
                mock(AgentTaskService.class);

        AgentTaskController controller =
                new AgentTaskController(agentTaskService,
                        mock(com.icbc.nbfh.service.VersionPackageService.class), mock(ConfigFileService.class));

        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("INVALID_TASK_NO");
        task.setStepNo(1);
        task.setOperationType(OperationType.STOP_REDIS.name());

        // 2. 提交任务并捕获参数异常。
        ParameterException exception = assertThrows(
                ParameterException.class,
                () -> controller.receiveTask(task)
        );

        // 3. 验证错误码和详细错误信息。
        assertEquals(
                ParameterErrorCode.TASK_NO_INVALID.getCode(),
                exception.getCode()
        );
        assertEquals(
                ParameterErrorCode.TASK_NO_INVALID.getMsg(),
                exception.getMsg()
        );
        assertEquals(
                "taskNo必须符合TASK加12位数字的格式",
                exception.getErrorMsg()
        );
        verifyNoInteractions(agentTaskService);
    }

    private String buildSha256(char value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
