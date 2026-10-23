package com.icbc.nbfh.service.impl;

import com.alibaba.fastjson.JSON;
import com.icbc.nbfh.client.PlatformClient;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证任务结果先保存本地，并且只向平台上报一次。
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
            List<Path> savedFiles = resultFiles();
            assertEquals(1, savedFiles.size());
            assertEquals(report.getResult(),
                    readReport(savedFiles.get(0)).getResult());
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

    /** 首次上报失败时保留本地结果文件，不再自动补报。 */
    @Test
    void shouldKeepLocalResultAfterFailedReport() throws Exception {
        TaskResultReportDTO report = report();
        doThrow(new IllegalStateException("平台暂时不可用"))
                .when(platformClient)
                .reportTaskResult(any(), anyString(), anyString());

        service.saveAndReport(report);

        List<Path> savedFiles = resultFiles();
        assertEquals(1, savedFiles.size());
        TaskResultReportDTO savedReport = readReport(
                savedFiles.get(0)
        );
        assertEquals(report.getTaskNo(), savedReport.getTaskNo());
        assertEquals(report.getStepNo(), savedReport.getStepNo());
        assertEquals(report.getAgentId(), savedReport.getAgentId());
        assertEquals(report.getOperationType(), savedReport.getOperationType());
        assertEquals(report.getStatus(), savedReport.getStatus());
        assertEquals(report.getResult(), savedReport.getResult());

        verify(platformClient, times(1)).reportTaskResult(
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

    /** 读取并解析本地任务结果。 */
    private TaskResultReportDTO readReport(Path path)
            throws Exception {
        return JSON.parseObject(
                new String(
                        Files.readAllBytes(path),
                        StandardCharsets.UTF_8
                ),
                TaskResultReportDTO.class
        );
    }
}
