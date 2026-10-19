package com.icbc.nbfh.controller;

import com.icbc.nbfh.service.ConfigFileService;
import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.exception.ParameterException;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.service.AgentTaskService;
import com.icbc.nbfh.service.VersionPackageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrecheckHostControllerTest {

    /** 无业务参数的主机预检查通过现有普通任务接口提交。 */
    @Test
    void shouldAcceptPrecheckWithoutBusinessParameters() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentTaskController controller = new AgentTaskController(tasks, mock(VersionPackageService.class), mock(ConfigFileService.class));
        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610170001");
        task.setStepNo(1);
        task.setOperationType(OperationType.PRECHECK_HOST.name());
        assertEquals(HttpStatus.ACCEPTED.value(),
                controller.receiveTask(task).getCode());
        verify(tasks).submitTask(task);
    }

    /** 平台不能通过预检查参数指定任意服务器路径。 */
    @Test
    void shouldRejectUnexpectedParameters() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentTaskController controller = new AgentTaskController(tasks, mock(VersionPackageService.class), mock(ConfigFileService.class));
        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610170001");
        task.setStepNo(1);
        task.setOperationType(OperationType.PRECHECK_HOST.name());
        task.setParams(Collections.singletonMap("path", "/uncontrolled"));
        assertThrows(ParameterException.class, () -> controller.receiveTask(task));
        verifyNoInteractions(tasks);
    }
}
