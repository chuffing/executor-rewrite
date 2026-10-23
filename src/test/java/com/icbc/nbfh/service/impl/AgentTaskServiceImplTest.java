package com.icbc.nbfh.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.Enum.TaskResultStatus;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;
import com.icbc.nbfh.service.RegisterService;
import com.icbc.nbfh.service.DeployVersionService;
import com.icbc.nbfh.service.DeployConfigService;
import com.icbc.nbfh.service.RestoreConfigService;
import com.icbc.nbfh.service.StartRedisService;
import com.icbc.nbfh.service.StartSentinelService;
import com.icbc.nbfh.service.StopRedisService;
import com.icbc.nbfh.service.PrecheckHostService;
import com.icbc.nbfh.service.SwitchVersionService;
import com.icbc.nbfh.service.StopSentinelService;
import com.icbc.nbfh.service.TaskResultReportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskServiceImplTest {

    /**
     * 验证Redis停止任务的结果上报。
     */
    @Test
    void shouldReportOperationResultToPlatform() {

        // 1. 准备依赖和执行结果。
        StopRedisService stopRedisService =
                mock(StopRedisService.class);
        StartRedisService startRedisService =
                mock(StartRedisService.class);
        StopSentinelService stopSentinelService =
                mock(StopSentinelService.class);
        StartSentinelService startSentinelService =
                mock(StartSentinelService.class);
        RestoreConfigService restoreConfigService =
                mock(RestoreConfigService.class);
        DeployVersionService deployVersionService =
                mock(DeployVersionService.class);
        TaskResultReportService taskResultReportService =
                mock(TaskResultReportService.class);
        RegisterService registerService =
                mock(RegisterService.class);

        OperationResultDTO operationResult =
                OperationResultDTO.success(
                        "Redis已停止",
                        100L,
                        200L
                );

        when(stopRedisService.stopRedis())
                .thenReturn(operationResult);
        when(registerService.getAgentId())
                .thenReturn("agent-001");

        AgentTaskServiceImpl service =
                new AgentTaskServiceImpl(
                        mock(PrecheckHostService.class),
                        stopRedisService,
                        startRedisService,
                        stopSentinelService,
                        startSentinelService,
                        restoreConfigService,
                        mock(com.icbc.nbfh.service.TopologyVerifyService.class),
                        mock(DeployConfigService.class),
                        deployVersionService,
                        mock(SwitchVersionService.class),
                        taskResultReportService,
                        registerService,
                        mock(RedisReadyConfigUpdater.class)
                );

        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610150001");
        task.setStepNo(1);
        task.setOperationType(OperationType.STOP_REDIS.name());

        // 2. 提交任务。
        service.submitTask(task);

        // 3. 验证上报报文。
        ArgumentCaptor<TaskResultReportDTO> captor =
                ArgumentCaptor.forClass(TaskResultReportDTO.class);

        verify(taskResultReportService, timeout(2000))
                .saveAndReport(captor.capture());

        TaskResultReportDTO report = captor.getValue();

        assertEquals("TASK202610150001", report.getTaskNo());
        assertEquals(1, report.getStepNo());
        assertEquals("agent-001", report.getAgentId());
        assertEquals(
                OperationType.STOP_REDIS.name(),
                report.getOperationType()
        );
        assertEquals(
                TaskResultStatus.SUCCESS.name(),
                report.getStatus()
        );

        JSONObject reportedResult =
                JSON.parseObject(report.getResult());

        assertTrue(reportedResult.getBooleanValue("success"));
        assertEquals("Redis已停止", reportedResult.getString("message"));
    }

    /**
     * 验证Redis启动任务能够进入正确的操作类。
     */
    @Test
    void shouldExecuteStartRedisAndReportResult() {

        // 1. 准备依赖和启动结果。
        StopRedisService stopRedisService =
                mock(StopRedisService.class);
        StartRedisService startRedisService =
                mock(StartRedisService.class);
        StopSentinelService stopSentinelService =
                mock(StopSentinelService.class);
        StartSentinelService startSentinelService =
                mock(StartSentinelService.class);
        RestoreConfigService restoreConfigService =
                mock(RestoreConfigService.class);
        DeployVersionService deployVersionService =
                mock(DeployVersionService.class);
        TaskResultReportService taskResultReportService =
                mock(TaskResultReportService.class);
        RegisterService registerService =
                mock(RegisterService.class);

        OperationResultDTO operationResult =
                OperationResultDTO.success(
                        "Redis已启动并能够正常响应PING",
                        100L,
                        200L
                );

        when(startRedisService.startRedis())
                .thenReturn(operationResult);
        when(registerService.getAgentId())
                .thenReturn("agent-001");

        AgentTaskServiceImpl service =
                new AgentTaskServiceImpl(
                        mock(PrecheckHostService.class),
                        stopRedisService,
                        startRedisService,
                        stopSentinelService,
                        startSentinelService,
                        restoreConfigService,
                        mock(com.icbc.nbfh.service.TopologyVerifyService.class),
                        mock(DeployConfigService.class),
                        deployVersionService,
                        mock(SwitchVersionService.class),
                        taskResultReportService,
                        registerService,
                        mock(RedisReadyConfigUpdater.class)
                );

        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610150002");
        task.setStepNo(1);
        task.setOperationType(OperationType.START_REDIS.name());

        // 2. 提交任务。
        service.submitTask(task);

        // 3. 验证启动操作和上报报文。
        verify(startRedisService, timeout(2000)).startRedis();

        ArgumentCaptor<TaskResultReportDTO> captor =
                ArgumentCaptor.forClass(TaskResultReportDTO.class);

        verify(taskResultReportService, timeout(2000))
                .saveAndReport(captor.capture());

        TaskResultReportDTO report = captor.getValue();

        assertEquals("TASK202610150002", report.getTaskNo());
        assertEquals(
                OperationType.START_REDIS.name(),
                report.getOperationType()
        );
        assertEquals(
                TaskResultStatus.SUCCESS.name(),
                report.getStatus()
        );
    }

    /**
     * 验证Sentinel停止任务能够进入正确的操作类。
     */
    @Test
    void shouldExecuteStopSentinelAndReportResult() {

        // 1. 准备依赖和停止结果。
        StopRedisService stopRedisService =
                mock(StopRedisService.class);
        StartRedisService startRedisService =
                mock(StartRedisService.class);
        StopSentinelService stopSentinelService =
                mock(StopSentinelService.class);
        StartSentinelService startSentinelService =
                mock(StartSentinelService.class);
        RestoreConfigService restoreConfigService =
                mock(RestoreConfigService.class);
        DeployVersionService deployVersionService =
                mock(DeployVersionService.class);
        TaskResultReportService taskResultReportService =
                mock(TaskResultReportService.class);
        RegisterService registerService =
                mock(RegisterService.class);

        OperationResultDTO operationResult =
                OperationResultDTO.success(
                        "Sentinel已停止，端口已释放",
                        100L,
                        200L
                );

        when(stopSentinelService.stopSentinel())
                .thenReturn(operationResult);
        when(registerService.getAgentId())
                .thenReturn("agent-001");

        AgentTaskServiceImpl service =
                new AgentTaskServiceImpl(
                        mock(PrecheckHostService.class),
                        stopRedisService,
                        startRedisService,
                        stopSentinelService,
                        startSentinelService,
                        restoreConfigService,
                        mock(com.icbc.nbfh.service.TopologyVerifyService.class),
                        mock(DeployConfigService.class),
                        deployVersionService,
                        mock(SwitchVersionService.class),
                        taskResultReportService,
                        registerService,
                        mock(RedisReadyConfigUpdater.class)
                );

        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610160001");
        task.setStepNo(1);
        task.setOperationType(OperationType.STOP_SENTINEL.name());

        // 2. 提交任务。
        service.submitTask(task);

        // 3. 验证停止操作和上报报文。
        verify(stopSentinelService, timeout(2000)).stopSentinel();

        ArgumentCaptor<TaskResultReportDTO> captor =
                ArgumentCaptor.forClass(TaskResultReportDTO.class);

        verify(taskResultReportService, timeout(2000))
                .saveAndReport(captor.capture());

        TaskResultReportDTO report = captor.getValue();

        assertEquals("TASK202610160001", report.getTaskNo());
        assertEquals(
                OperationType.STOP_SENTINEL.name(),
                report.getOperationType()
        );
        assertEquals(
                TaskResultStatus.SUCCESS.name(),
                report.getStatus()
        );
    }

    /**
     * 验证Sentinel启动任务能够进入正确的操作类。
     */
    @Test
    void shouldExecuteStartSentinelAndReportResult() {

        // 1. 准备依赖和启动结果。
        StopRedisService stopRedisService =
                mock(StopRedisService.class);
        StartRedisService startRedisService =
                mock(StartRedisService.class);
        StopSentinelService stopSentinelService =
                mock(StopSentinelService.class);
        StartSentinelService startSentinelService =
                mock(StartSentinelService.class);
        RestoreConfigService restoreConfigService =
                mock(RestoreConfigService.class);
        DeployVersionService deployVersionService =
                mock(DeployVersionService.class);
        TaskResultReportService taskResultReportService =
                mock(TaskResultReportService.class);
        RegisterService registerService =
                mock(RegisterService.class);

        OperationResultDTO operationResult =
                OperationResultDTO.success(
                        "Sentinel已启动并发现Redis主节点",
                        100L,
                        200L
                );

        when(startSentinelService.startSentinel())
                .thenReturn(operationResult);
        when(registerService.getAgentId())
                .thenReturn("agent-001");

        AgentTaskServiceImpl service =
                new AgentTaskServiceImpl(
                        mock(PrecheckHostService.class),
                        stopRedisService,
                        startRedisService,
                        stopSentinelService,
                        startSentinelService,
                        restoreConfigService,
                        mock(com.icbc.nbfh.service.TopologyVerifyService.class),
                        mock(DeployConfigService.class),
                        deployVersionService,
                        mock(SwitchVersionService.class),
                        taskResultReportService,
                        registerService,
                        mock(RedisReadyConfigUpdater.class)
                );

        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610160002");
        task.setStepNo(1);
        task.setOperationType(OperationType.START_SENTINEL.name());

        // 2. 提交任务。
        service.submitTask(task);

        // 3. 验证启动操作和上报报文。
        verify(startSentinelService, timeout(2000)).startSentinel();

        ArgumentCaptor<TaskResultReportDTO> captor =
                ArgumentCaptor.forClass(TaskResultReportDTO.class);

        verify(taskResultReportService, timeout(2000))
                .saveAndReport(captor.capture());

        TaskResultReportDTO report = captor.getValue();

        assertEquals("TASK202610160002", report.getTaskNo());
        assertEquals(
                OperationType.START_SENTINEL.name(),
                report.getOperationType()
        );
        assertEquals(
                TaskResultStatus.SUCCESS.name(),
                report.getStatus()
        );
    }
}

