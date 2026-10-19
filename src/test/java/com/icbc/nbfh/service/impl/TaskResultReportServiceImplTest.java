package com.icbc.nbfh.service.impl;

import com.alibaba.fastjson.JSON;
import com.icbc.nbfh.client.PlatformClient;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;
import com.icbc.nbfh.entity.DTO.TaskResultReportRecordDTO;
import com.icbc.nbfh.service.RegisterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证任务结果先保存本地，并在失败后按次数进行补报。
 */
class TaskResultReportServiceImplTest {

    @TempDir
    Path resultDirectory;

    private PlatformClient platformClient;

    private RegisterService registerService;

    private AgentProperties agentProperties;

    private TaskResultReportServiceImpl service;

    @BeforeEach
    void prepare() {
        platformClient = mock(PlatformClient.class);
        registerService = mock(RegisterService.class);

        agentProperties = new AgentProperties();
        agentProperties.getPlatform().setUrl("http://test-platform");
        agentProperties.getTaskResult().setDirectory(
                resultDirectory.toString()
        );
        agentProperties.getTaskResult().setRetryIntervalMillis(1);
        agentProperties.getTaskResult().setMaxAttempts(5);

        when(registerService.registerIfNeeded()).thenReturn(true);
        when(registerService.getToken()).thenReturn("test-token");

        service = new TaskResultReportServiceImpl(
                platformClient,
                registerService,
                agentProperties
        );
    }

    /** 上报成功后删除已经保存的本地结果文件。 */
    @Test
    void shouldDeleteLocalResultAfterSuccessfulReport() throws Exception {
        TaskResultReportDTO report = report();
        doAnswer(invocation -> {
            // 调用平台前，本地结果必须已经可靠保存。
            assertEquals(1, resultFiles().size());
            return null;
        }).when(platformClient).reportTaskResult(
                any(),
                anyString(),
                anyString()
        );

        service.saveAndReport(report);

        verify(platformClient).reportTaskResult(
                report,
                "http://test-platform",
                "test-token"
        );
        assertTrue(resultFiles().isEmpty());
    }

    /** 首次上报失败时保留文件，定时重试成功后再删除。 */
    @Test
    void shouldRetrySavedResultAndDeleteItAfterSuccess() throws Exception {
        TaskResultReportDTO report = report();
        doThrow(new IllegalStateException("平台暂时不可用"))
                .doNothing()
                .when(platformClient)
                .reportTaskResult(any(), anyString(), anyString());

        service.saveAndReport(report);

        List<Path> savedFiles = resultFiles();
        assertEquals(1, savedFiles.size());
        TaskResultReportRecordDTO firstRecord = readRecord(
                savedFiles.get(0)
        );
        assertEquals(1, firstRecord.getAttemptCount());
        assertEquals("平台暂时不可用", firstRecord.getLastError());

        // 重新创建服务对象，模拟执行器重启后从本地文件恢复补报。
        TaskResultReportServiceImpl restartedService =
                new TaskResultReportServiceImpl(
                        platformClient,
                        registerService,
                        agentProperties
                );

        Thread.sleep(5);
        restartedService.retryPendingResults();

        verify(platformClient, times(2)).reportTaskResult(
                any(),
                anyString(),
                anyString()
        );
        assertTrue(resultFiles().isEmpty());
    }

    /** 达到最大上报次数后保留结果文件并停止自动重试。 */
    @Test
    void shouldKeepLocalResultAfterMaximumAttempts() throws Exception {
        agentProperties.getTaskResult().setMaxAttempts(2);
        doThrow(new IllegalStateException("平台不可用"))
                .when(platformClient)
                .reportTaskResult(any(), anyString(), anyString());

        service.saveAndReport(report());
        Thread.sleep(5);
        service.retryPendingResults();

        List<Path> savedFiles = resultFiles();
        assertEquals(1, savedFiles.size());

        TaskResultReportRecordDTO record = readRecord(savedFiles.get(0));
        assertEquals(2, record.getAttemptCount());
        assertNull(record.getNextAttemptTime());
        assertFalse(record.getLastError().isEmpty());

        Thread.sleep(5);
        service.retryPendingResults();

        verify(platformClient, times(2)).reportTaskResult(
                any(),
                anyString(),
                anyString()
        );
    }

    /** 没有本地待上报结果时不访问平台。 */
    @Test
    void shouldDoNothingWhenNoPendingResultExists() {
        service.retryPendingResults();

        verify(platformClient, never()).reportTaskResult(
                any(),
                anyString(),
                anyString()
        );
    }

    /** 构造一条成功的任务执行结果。 */
    private TaskResultReportDTO report() {
        return new TaskResultReportDTO(
                "TASK202610180001",
                1,
                "agent-001",
                "STOP_SENTINEL",
                "SUCCESS",
                "{\"success\":true,\"startTime\":1,\"endTime\":2}"
        );
    }

    /** 查询测试目录中的本地结果文件。 */
    private List<Path> resultFiles() throws Exception {
        try (Stream<Path> files = Files.list(resultDirectory)) {
            return files
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".json"))
                    .collect(Collectors.toList());
        }
    }

    /** 读取并解析本地任务结果记录。 */
    private TaskResultReportRecordDTO readRecord(Path path)
            throws Exception {
        return JSON.parseObject(
                new String(
                        Files.readAllBytes(path),
                        StandardCharsets.UTF_8
                ),
                TaskResultReportRecordDTO.class
        );
    }
}
