package com.icbc.nbfh.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.alibaba.fastjson.JSON;
import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.Enum.TaskSubmitStatus;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.config.handler.GlobalExceptionHandler;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.service.*;
import com.icbc.nbfh.service.impl.AgentTaskServiceImpl;
import com.icbc.nbfh.service.impl.VersionPackageServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** MockMvc并发提交测试，业务操作及平台通信使用替身，不启动真实Redis。 */
class TaskAdmissionConcurrencyTest {

    @TempDir
    Path packages;

    private MockMvc mvc;
    private StartRedisService starting;
    private DeployVersionService deploying;
    private TaskResultReportService resultReports;
    private ThreadPoolExecutor worker;
    private final CountDownLatch operationRelease = new CountDownLatch(1);
    private final CountDownLatch reportRelease = new CountDownLatch(1);
    private final AtomicInteger operationCount = new AtomicInteger();
    private final AtomicInteger activeOperations = new AtomicInteger();
    private final AtomicInteger maximumActive = new AtomicInteger();
    private final Set<String> executionThreads = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private Logger rootLogger;
    private Level originalLevel;

    /** 使用真实Controller、任务线程池及安装包服务，外部副作用全部替换。 */
    @BeforeEach
    void prepare() {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        originalLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.WARN);
        AgentProperties properties = new AgentProperties();
        properties.getOperation().setPackageDir(packages.toString());
        RegisterService registration = mock(RegisterService.class);
        when(registration.getAgentId()).thenReturn("test-agent");
        starting = mock(StartRedisService.class);
        deploying = mock(DeployVersionService.class);
        resultReports = mock(TaskResultReportService.class);
        AgentTaskServiceImpl tasks = new AgentTaskServiceImpl(
                mock(PrecheckHostService.class),
                mock(StopRedisService.class), starting,
                mock(StopSentinelService.class), mock(StartSentinelService.class),
                mock(RestoreConfigService.class), mock(com.icbc.nbfh.service.RecoveryTopologyVerifyService.class),
                mock(DeployConfigService.class), deploying, mock(SwitchVersionService.class),
                resultReports, registration);
        worker = (ThreadPoolExecutor) ReflectionTestUtils.getField(tasks, "executor");
        AgentTaskController controller = new AgentTaskController(tasks, new VersionPackageServiceImpl(properties), mock(ConfigFileService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(starting.startRedis()).thenAnswer(invocation -> {
            operationCount.incrementAndGet();
            return OperationResultDTO.success("测试操作完成", 1L, 2L);
        });
    }

    /** 即使断言失败，也释放测试闸门并关闭本测试创建的线程池。 */
    @AfterEach
    void close() throws InterruptedException {
        try {
            operationRelease.countDown();
            reportRelease.countDown();
            if (worker != null) {
                worker.shutdown();
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    worker.shutdownNow();
                    assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
                }
            }
        } finally {
            rootLogger.setLevel(originalLevel);
        }
    }

    /** 阻塞首个业务操作，精确控制线程池处于忙碌状态。 */
    private CountDownLatch blockFirstOperation() {
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(invocation -> {
            int count = operationCount.incrementAndGet();
            executionThreads.add(Thread.currentThread().getName());
            int active = activeOperations.incrementAndGet();
            maximumActive.accumulateAndGet(active, Math::max);
            try {
                if (count == 1) {
                    entered.countDown();
                    assertTrue(operationRelease.await(30, TimeUnit.SECONDS), "测试未释放首个任务");
                }
                return OperationResultDTO.success("测试操作完成", 1L, 2L);
            } finally {
                activeOperations.decrementAndGet();
            }
        }).when(starting).startRedis();
        return entered;
    }

    /** 构造合法任务编号，避免请求因参数错误提前被拒绝。 */
    private PlatformTaskDTO task(int number, OperationType operation) {
        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo(String.format("TASK%012d", number));
        task.setStepNo(1);
        task.setOperationType(operation.name());
        return task;
    }

    /** 通过MVC请求链提交JSON，校验统一响应业务码和接收状态一致。 */
    private int submit(int number) throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/agent/task")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONBytes(task(number, OperationType.START_REDIS))))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        com.alibaba.fastjson.JSONObject responseJson =
                JSON.parseObject(response.getContentAsString());
        int code = responseJson.getIntValue("code");
        String status = responseJson.getJSONObject("data").getString("status");
        if (code == 202) {
            assertEquals(TaskSubmitStatus.ACCEPTED.name(), status);
        } else {
            assertEquals(503, code, response.getContentAsString());
            assertEquals(TaskSubmitStatus.BUSY.name(), status);
        }
        return code;
    }

    /** 64个客户端线程发起1000次提交，只允许一个等待任务，释放后能够恢复接收。 */
    @Test
    void shouldBoundQueueUnderConcurrentBurstAndRecover() throws Exception {
        CountDownLatch entered = blockFirstOperation();
        assertEquals(202, submit(1));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        ExecutorService clients = Executors.newFixedThreadPool(64);
        int accepted = 0;
        int busy = 0;
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int index = 0; index < 1000; index++) {
                final int number = index + 2;
                results.add(clients.submit(() -> submit(number)));
            }
            for (Future<Integer> result : results) {
                if (result.get(30, TimeUnit.SECONDS) == 202) {
                    accepted++;
                } else {
                    busy++;
                }
            }
        } finally {
            clients.shutdownNow();
            assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, accepted);
        assertEquals(999, busy);
        assertEquals(1, worker.getQueue().size());
        assertEquals(1, worker.getActiveCount());
        assertEquals(1, worker.getMaximumPoolSize());
        assertEquals(1, operationCount.get());
        operationRelease.countDown();
        verify(resultReports, timeout(5000).times(2))
                .saveAndReport(any());
        // 工作线程的顺序屏障，确保前两个任务完成后再验证恢复接收。
        worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
        assertEquals(202, submit(2000));
        worker.shutdown();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(3, operationCount.get());
        assertEquals(1, maximumActive.get());
        assertEquals(1, executionThreads.size(), "不能由HTTP请求线程代替执行任务");
        verify(resultReports, times(3)).saveAndReport(any());
        System.out.println("BURST: clients=64, requests=1000, queued=1, busy503=999, maxConcurrentOperations=1, recovery=202");
    }

    /** 模拟平台已收到结果、上报HTTP响应尚未返回，下一步应排队等待。 */
    @Test
    void shouldQueueNextTaskWhileWaitingForReportResponse() throws Exception {
        CountDownLatch reportEntered = new CountDownLatch(1);
        AtomicInteger reportCount = new AtomicInteger();
        doAnswer(invocation -> {
            if (reportCount.incrementAndGet() == 1) {
                reportEntered.countDown();
                assertTrue(reportRelease.await(10, TimeUnit.SECONDS));
            }
            return null;
        }).when(resultReports).saveAndReport(any());
        assertEquals(202, submit(1));
        assertTrue(reportEntered.await(5, TimeUnit.SECONDS));
        assertEquals(202, submit(2));
        assertEquals(1, worker.getQueue().size());
        assertEquals(1, operationCount.get());
        assertEquals(503, submit(3));
        reportRelease.countDown();
        worker.shutdown();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(2, operationCount.get());
        assertEquals(2, reportCount.get());
    }

    /** 32个客户端并发提交100个小安装包，拒绝后不留暂存目录，也不执行部署。 */
    @Test
    void shouldCleanRejectedPackagesUnderConcurrentUploads() throws Exception {
        CountDownLatch entered = blockFirstOperation();
        assertEquals(202, submit(1));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(202, submit(2));
        byte[] data = new byte[1024];
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder expected = new StringBuilder();
        for (byte value : digest) {
            expected.append(String.format("%02x", value & 0xff));
        }
        ExecutorService clients = Executors.newFixedThreadPool(32);
        try {
            List<Future<Integer>> responses = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                final int number = index + 3;
                responses.add(clients.submit(() -> {
                    PlatformTaskDTO task = task(number, OperationType.DEPLOY_VERSION);
                    Map<String, String> params = new HashMap<>();
                    params.put(TaskParamConstants.PACKAGE_ID, "1");
                    params.put(TaskParamConstants.TARGET_VERSION, "6.2.19");
                    params.put(TaskParamConstants.PACKAGE_SHA256, expected.toString());
                    task.setParams(params);
                    MockMultipartFile taskPart = new MockMultipartFile(
                            "task", "", MediaType.APPLICATION_JSON_VALUE, JSON.toJSONBytes(task));
                    MockMultipartFile filePart = new MockMultipartFile(
                            "file", "redis-6.2.19-runtime.tar.gz", "application/gzip", data);
                    MockHttpServletResponse response = mvc.perform(multipart("/api/agent/version/deploy")
                            .file(taskPart).file(filePart)).andReturn().getResponse();
                    assertEquals(TaskSubmitStatus.BUSY.name(), JSON.parseObject(
                                    response.getContentAsString(StandardCharsets.UTF_8))
                            .getJSONObject("data")
                            .getString("status"));
                    assertEquals(200, response.getStatus());
                    return JSON.parseObject(
                            response.getContentAsString(StandardCharsets.UTF_8))
                            .getIntValue("code");
                }));
            }
            for (Future<Integer> response : responses) {
                assertEquals(503, response.get(30, TimeUnit.SECONDS).intValue());
            }
        } finally {
            clients.shutdownNow();
            assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS));
        }
        try (Stream<Path> staged = Files.list(packages.resolve("staging"))) {
            assertEquals(0, staged.count());
        }
        assertEquals(1, worker.getQueue().size());
        assertEquals(1, operationCount.get());
        verifyNoInteractions(deploying);
        System.out.println("UPLOAD_BURST: clients=32, requests=100, busy503=100, remainingStagedDirectories=0, deployments=0");
    }
}
