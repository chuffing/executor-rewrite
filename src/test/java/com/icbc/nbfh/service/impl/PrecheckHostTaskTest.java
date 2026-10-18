package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.client.PlatformClient;
import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.Enum.TaskResultStatus;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;
import com.icbc.nbfh.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PrecheckHostTaskTest {

    /** 新操作仍使用现有任务线程池和结果上报协议。 */
    @Test
    void shouldDispatchAndReportPrecheck() throws Exception {
        PrecheckHostService checking = mock(PrecheckHostService.class);
        PlatformClient platform = mock(PlatformClient.class);
        RegisterService registration = mock(RegisterService.class);
        AgentProperties properties = new AgentProperties();
        properties.getPlatform().setUrl("http://test-platform");
        when(registration.getAgentId()).thenReturn("test-agent");
        when(registration.getToken()).thenReturn("test-token");
        when(checking.precheckHost()).thenReturn(OperationResultDTO.success("预检查通过", 1L, 2L));
        AgentTaskServiceImpl tasks = new AgentTaskServiceImpl(checking,
                mock(StopRedisService.class), mock(StartRedisService.class),
                mock(StopSentinelService.class), mock(StartSentinelService.class),
                mock(RestoreConfigService.class), mock(DeployConfigService.class), mock(DeployVersionService.class), mock(SwitchVersionService.class),
                platform, registration, properties);
        ThreadPoolExecutor worker = (ThreadPoolExecutor) ReflectionTestUtils.getField(tasks, "executor");
        try {
            PlatformTaskDTO task = new PlatformTaskDTO();
            task.setTaskNo("TASK202610170001");
            task.setStepNo(1);
            task.setOperationType(OperationType.PRECHECK_HOST.name());
            tasks.submitTask(task);
            ArgumentCaptor<TaskResultReportDTO> report = ArgumentCaptor.forClass(TaskResultReportDTO.class);
            verify(platform, timeout(2000)).reportTaskResult(
                    report.capture(), eq("http://test-platform"), eq("test-token"));
            verify(checking).precheckHost();
            assertEquals(OperationType.PRECHECK_HOST.name(), report.getValue().getOperationType());
            assertEquals(TaskResultStatus.SUCCESS.name(), report.getValue().getStatus());
        } finally {
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
