package com.icbc.nbfh.controller;

import com.icbc.nbfh.service.ConfigFileService;
import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.common.exception.ParameterException;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.service.AgentTaskService;
import com.icbc.nbfh.service.VersionPackageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证切换任务继续使用普通接口，版本格式由Controller拦截。 */
class SwitchVersionControllerTest {

    /** 合法版本无需安装包即可提交。 */
    @Test
    void shouldAcceptSwitchTask() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentTaskController controller = new AgentTaskController(tasks, mock(VersionPackageService.class), mock(ConfigFileService.class));
        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610170001");
        task.setStepNo(1);
        task.setOperationType(OperationType.SWITCH_VERSION.name());
        task.setParams(Collections.singletonMap(TaskParamConstants.TARGET_VERSION, "6.2.19"));

        assertEquals(HttpStatus.ACCEPTED.value(),
                controller.receiveTask(task).getCode());
        verify(tasks).submitTask(task);
    }

    /** 空参数和路径穿越形式的版本不能进入业务服务。 */
    @Test
    void shouldRejectInvalidSwitchParameters() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentTaskController controller = new AgentTaskController(tasks, mock(VersionPackageService.class), mock(ConfigFileService.class));
        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610170001");
        task.setStepNo(1);
        task.setOperationType(OperationType.SWITCH_VERSION.name());
        assertThrows(ParameterException.class, () -> controller.receiveTask(task));
        for (String version : new String[]{"", " ", "../6.2.19", "6.2.19/bin"}) {
            task.setParams(Collections.singletonMap(TaskParamConstants.TARGET_VERSION, version));
            assertThrows(ParameterException.class, () -> controller.receiveTask(task));
        }
        verifyNoInteractions(tasks);
    }
}
