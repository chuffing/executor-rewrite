package com.icbc.nbfh.controller;

import com.icbc.nbfh.service.ConfigFileService;
import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.Enum.ParameterErrorCode;
import com.icbc.nbfh.common.Enum.TaskSubmitStatus;
import com.icbc.nbfh.common.exception.ParameterException;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.entity.VO.TaskSubmitResultVO;
import com.icbc.nbfh.service.AgentTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

class AgentTaskControllerTest {

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
        ResponseEntity<TaskSubmitResultVO> response =
                controller.receiveTask(task);

        // 3. 验证任务提交和响应内容。
        verify(agentTaskService).submitTask(task);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("TASK202610160001", response.getBody().getTaskNo());
        assertEquals(
                TaskSubmitStatus.ACCEPTED.name(),
                response.getBody().getStatus()
        );
        assertEquals("任务已接收", response.getBody().getMessage());
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
}
