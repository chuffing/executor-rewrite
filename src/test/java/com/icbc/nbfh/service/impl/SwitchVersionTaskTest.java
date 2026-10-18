package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.client.PlatformClient;
import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.Enum.TaskResultStatus;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;
import com.icbc.nbfh.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 脚本与平台通信均使用替身，验证切换任务的结果上报。 */
class SwitchVersionTaskTest {

    /** 切换结果通过现有任务服务发送给平台。 */
    @Test
    void shouldDispatchSwitchAndReportResult() {
        SwitchVersionService switching = mock(SwitchVersionService.class);
        PlatformClient platform = mock(PlatformClient.class);
        RegisterService registration = mock(RegisterService.class);
        AgentProperties properties = new AgentProperties();
        properties.getPlatform().setUrl("http://test-platform");
        when(registration.getAgentId()).thenReturn("agent-test");
        when(registration.getToken()).thenReturn("test-token");
        AgentTaskServiceImpl tasks = new AgentTaskServiceImpl(
                mock(PrecheckHostService.class),
                mock(StopRedisService.class), mock(StartRedisService.class),
                mock(StopSentinelService.class), mock(StartSentinelService.class),
                mock(RestoreConfigService.class), mock(DeployConfigService.class), mock(DeployVersionService.class),
                switching, platform, registration, properties);
        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610170001");
        task.setStepNo(2);
        task.setOperationType(OperationType.SWITCH_VERSION.name());
        task.setParams(Collections.singletonMap(TaskParamConstants.TARGET_VERSION, "6.2.19"));
        when(switching.switchVersion(task.getParams()))
                .thenReturn(OperationResultDTO.success("链接已切换", 1L, 2L));

        tasks.submitTask(task);

        ArgumentCaptor<TaskResultReportDTO> report = ArgumentCaptor.forClass(TaskResultReportDTO.class);
        verify(platform, timeout(2000)).reportTaskResult(
                report.capture(), eq("http://test-platform"), eq("test-token"));
        verify(switching).switchVersion(task.getParams());
        assertEquals(OperationType.SWITCH_VERSION.name(), report.getValue().getOperationType());
        assertEquals(TaskResultStatus.SUCCESS.name(), report.getValue().getStatus());
        assertEquals(task.getStepNo(), report.getValue().getStepNo());
    }
}
