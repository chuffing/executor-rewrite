package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.Enum.TaskResultStatus;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;
import com.icbc.nbfh.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/** 脚本与平台通信均使用替身，验证切换任务的结果上报。 */
class SwitchVersionTaskTest {

    /** 切换结果通过现有任务服务发送给平台。 */
    @Test
    void shouldDispatchSwitchAndReportResult() {
        SwitchVersionService switching = mock(SwitchVersionService.class);
        TaskResultReportService resultReports =
                mock(TaskResultReportService.class);
        RegisterService registration = mock(RegisterService.class);
        when(registration.getAgentId()).thenReturn("agent-test");
        AgentTaskServiceImpl tasks = new AgentTaskServiceImpl(
                mock(PrecheckHostService.class),
                mock(StopRedisService.class), mock(StartRedisService.class),
                mock(StopSentinelService.class), mock(StartSentinelService.class),
                mock(RestoreConfigService.class), mock(com.icbc.nbfh.service.RecoveryTopologyVerifyService.class),
                mock(DeployConfigService.class), mock(DeployVersionService.class),
                switching, resultReports, registration);
        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610170001");
        task.setStepNo(2);
        task.setOperationType(OperationType.SWITCH_VERSION.name());
        task.setParams(Collections.singletonMap(TaskParamConstants.TARGET_VERSION, "6.2.19"));
        when(switching.switchVersion(task.getParams()))
                .thenReturn(OperationResultDTO.success("链接已切换", 1L, 2L));

        tasks.submitTask(task);

        ArgumentCaptor<TaskResultReportDTO> report = ArgumentCaptor.forClass(TaskResultReportDTO.class);
        verify(resultReports, timeout(2000))
                .saveAndReport(report.capture());
        verify(switching).switchVersion(task.getParams());
        assertEquals(OperationType.SWITCH_VERSION.name(), report.getValue().getOperationType());
        assertEquals(TaskResultStatus.SUCCESS.name(), report.getValue().getStatus());
        assertEquals(task.getStepNo(), report.getValue().getStepNo());
    }
}
