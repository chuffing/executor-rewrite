package com.icbc.nbfh.service;

import com.icbc.nbfh.service.impl.DeployVersionServiceImpl;
import com.icbc.nbfh.service.impl.VersionPackageServiceImpl;

import java.util.Collections;
import java.util.Arrays;
import java.util.Map;
import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.command.FixedScriptExecutor;
import com.icbc.nbfh.common.Enum.ParameterErrorCode;
import com.icbc.nbfh.common.constant.ValidationConstants;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.Enum.TaskSubmitStatus;
import com.icbc.nbfh.common.exception.ParameterException;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.controller.AgentTaskController;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.entity.VO.TaskSubmitResultVO;
import org.springframework.http.ResponseEntity;
import com.icbc.nbfh.util.FileChecksumUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VersionPackageServiceTest {

    @TempDir
    Path temporaryDirectory;

    /**
     * 正常包保存后可供异步任务读取，重复请求互不覆盖。
     */
    @Test
    void shouldStageValidPackagesSeparately() throws Exception {
        VersionPackageService service = createService();
        byte[] archive = createPackageBytes();
        String sha256 = checksum(archive);
        Path first = service.saveAndValidate("TASK202610160001",
                sha256, upload(archive));
        Path second = service.saveAndValidate("TASK202610160001",
                sha256, upload(archive));
        assertNotEquals(first, second);
        assertArrayEquals(archive, Files.readAllBytes(first));
        assertEquals("package.tar.gz", first.getFileName().toString());
        service.deleteStagedPackage(first);
        service.deleteStagedPackage(second);
        assertEquals(0, stagingEntryCount());
    }

    /**
     * 摘要不符时不留下供部署使用的文件。
     */
    @Test
    void shouldRejectChecksumMismatchAndCleanTemporaryFiles() throws Exception {
        byte[] archive = createPackageBytes();
        ParameterException error = assertThrows(ParameterException.class,
                () -> createService().saveAndValidate("TASK202610160001",
                        String.join("", Collections.nCopies(64, "0")), upload(archive)));
        assertEquals(ParameterErrorCode.PACKAGE_CHECKSUM_MISMATCH.getCode(),
                error.getCode());
        assertEquals(0, stagingEntryCount());
    }

    /**
     * 超过50 MiB时入口应立即拒绝，不调用暂存及任务服务。
     */
    @Test
    void shouldRejectOversizeUploadBeforeSaving() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        VersionPackageService packages = mock(VersionPackageService.class);
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(
                ValidationConstants.MAX_UPGRADE_PACKAGE_SIZE_BYTES + 1);
        AgentTaskController controller = new AgentTaskController(tasks, packages, mock(ConfigFileService.class));
        assertThrows(ParameterException.class,
                () -> controller.receiveVersionDeployTask(createTask(), file));
        verifyNoInteractions(tasks, packages);
    }

    /**
     * 恰好50 MiB允许通过入口大小校验，继续进行文件保存和摘要校验。
     */
    @Test
    void shouldAllowExactSizeBoundaryToReachChecksumValidation() throws Exception {
        AgentTaskService tasks = mock(AgentTaskService.class);
        VersionPackageService packages = mock(VersionPackageService.class);
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(ValidationConstants.MAX_UPGRADE_PACKAGE_SIZE_BYTES);
        when(file.getOriginalFilename()).thenReturn("redis-7.2.4-runtime.tar.gz");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(
                new byte[]{0x1f, (byte) 0x8b}));
        PlatformTaskDTO task = createTask();
        Path staged = temporaryDirectory.resolve("package.tar.gz");
        when(packages.saveAndValidate(task.getTaskNo(),
                task.getParams().get(TaskParamConstants.PACKAGE_SHA256), file))
                .thenReturn(staged);
        AgentTaskController controller = new AgentTaskController(tasks, packages, mock(ConfigFileService.class));
        assertEquals(202, controller.receiveVersionDeployTask(task, file).getStatusCodeValue());
        verify(tasks).submitTask(task);
        assertEquals(staged.toString(),
                task.getParams().get(TaskParamConstants.PACKAGE_LOCAL_PATH));
    }

    /**
     * 线程池拒收任务时清理已暂存文件。
     */
    @Test
    void shouldCleanPackageWhenSubmissionFails() throws Exception {
        AgentTaskService tasks = mock(AgentTaskService.class);
        VersionPackageService packages = createService();
        doThrow(new RejectedExecutionException()).when(tasks).submitTask(any());
        byte[] archive = createPackageBytes();
        PlatformTaskDTO task = createTask();
        task.getParams().put(TaskParamConstants.PACKAGE_SHA256, checksum(archive));
        AgentTaskController controller = new AgentTaskController(tasks, packages, mock(ConfigFileService.class));
        ResponseEntity<TaskSubmitResultVO> response =
                controller.receiveVersionDeployTask(task, upload(archive));
        assertEquals(503, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertEquals(TaskSubmitStatus.BUSY.name(), response.getBody().getStatus());
        assertEquals(task.getTaskNo(), response.getBody().getTaskNo());
        assertEquals(0, stagingEntryCount());
    }

    /**
     * 普通JSON任务接口不能绕过安装包上传校验。
     */
    @Test
    void shouldRejectDeploymentThroughJsonEndpoint() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentTaskController controller = new AgentTaskController(tasks,
                mock(VersionPackageService.class), mock(ConfigFileService.class));
        assertThrows(ParameterException.class, () -> controller.receiveTask(createTask()));
        verifyNoInteractions(tasks);
    }

    /**
     * 创建测试接收服务。
     */
    private VersionPackageService createService() {
        AgentProperties properties = new AgentProperties();
        properties.getOperation().setPackageDir(temporaryDirectory.toString());
        properties.getOperation().setInstallDir(temporaryDirectory.toString());
        return new VersionPackageServiceImpl(properties);
    }

    /**
     * 创建合法部署任务。
     */
    private PlatformTaskDTO createTask() {
        PlatformTaskDTO task = new PlatformTaskDTO();
        task.setTaskNo("TASK202610160001");
        task.setStepNo(1);
        task.setOperationType(OperationType.DEPLOY_VERSION.name());
        task.setParams(new HashMap<>());
        task.getParams().put(TaskParamConstants.PACKAGE_ID, "10001");
        task.getParams().put(TaskParamConstants.TARGET_VERSION, "7.2.4");
        task.getParams().put(TaskParamConstants.PACKAGE_SHA256,
                String.join("", Collections.nCopies(64, "0")));
        return task;
    }

    /**
     * 创建内存上传文件。
     */
    private MockMultipartFile upload(byte[] bytes) {
        return new MockMultipartFile("file", "redis-7.2.4-runtime.tar.gz",
                "application/gzip", bytes);
    }

    /**
     * 计算测试数据摘要。
     */
    private String checksum(byte[] bytes) throws Exception {
        Path file = temporaryDirectory.resolve("checksum-input");
        Files.write(file, bytes);
        return FileChecksumUtil.calculateSha256(file);
    }

    /**
     * 检查失败后独立暂存目录已被清理。
     */
    private long stagingEntryCount() throws Exception {
        try (Stream<Path> entries = Files.list(temporaryDirectory.resolve("staging"))) {
            return entries.count();
        }
    }

    /**
     * 创建测试字节，仅接收阶段不再检查TAR内部业务结构。
     */
    private byte[] createPackageBytes() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
            output.write("平台已经校验的包内容".getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    /**
     * 清理时同时尝试删除临时文件和正式暂存文件。
     */
    @Test
    void shouldCleanTemporaryAndFinalFilesTogether() throws Exception {
        Path request = Files.createDirectories(temporaryDirectory
                .resolve("staging").resolve("TASK202610160001-test"));
        Files.write(request.resolve("package.part"), new byte[]{1});
        Path ready = request.resolve("package.tar.gz");
        Files.write(ready, new byte[]{2});
        createService().deleteStagedPackage(ready);
        assertFalse(Files.exists(request));
    }

    /**
     * 清理接口不能删除暂存目录外的文件。
     */
    @Test
    void shouldNotDeleteFilesOutsideStaging() throws Exception {
        Path external = temporaryDirectory.resolve("package.tar.gz");
        Files.write(external, new byte[]{1});
        createService().deleteStagedPackage(external);
        assertTrue(Files.exists(external));
    }

    /**
     * 流中断时清理已写入的临时文件。
     */
    @Test
    void shouldCleanFilesOnReadFailure() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getInputStream()).thenReturn(new InputStream() {
            /** 模拟网络上传流中断。 */
            @Override
            public int read() throws IOException {
                throw new IOException("上传流中断");
            }
        });
        assertThrows(IOException.class,
                () -> createService().saveAndValidate("TASK202610160001",
                        "unused", file));
        assertEquals(0, stagingEntryCount());
    }

    /**
     * 文件元信息伪报较小值时，实际上传流仍受50 MiB限制。
     */
    @Test
    void shouldLimitActualStreamSize() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1L);
        when(file.getInputStream()).thenReturn(new InputStream() {
            private long remaining = ValidationConstants.MAX_UPGRADE_PACKAGE_SIZE_BYTES + 1;

            /** 返回固定字节，避免把测试安装包整体放到内存。 */
            @Override
            public int read() {
                if (remaining == 0) {
                    return -1;
                }
                remaining--;
                return 0;
            }

            /** 按缓冲区大小模拟连续上传。 */
            @Override
            public int read(byte[] bytes, int offset, int length) {
                if (remaining == 0) {
                    return -1;
                }
                int count = (int) Math.min(remaining, length);
                Arrays.fill(bytes, offset, offset + count, (byte) 0);
                remaining -= count;
                return count;
            }
        });
        ParameterException error = assertThrows(ParameterException.class,
                () -> createService().saveAndValidate("TASK202610160001", "unused", file));
        assertEquals("Redis安装包不能超过50 MiB", error.getErrorMsg());
        assertEquals(0, stagingEntryCount());
    }

    /**
     * 实际接收长度与文件元信息不一致时拒绝并清理。
     */
    @Test
    void shouldRejectIncompleteTransfer() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(2L);
        when(file.getInputStream()).thenReturn(
                new ByteArrayInputStream(new byte[]{1}));
        ParameterException error = assertThrows(ParameterException.class,
                () -> createService().saveAndValidate("TASK202610160001", "unused", file));
        assertEquals("Redis安装包传输不完整", error.getErrorMsg());
        assertEquals(0, stagingEntryCount());
    }

    /**
     * 暂存文件被修改时，部署前二次摘要校验必须阻止调用脚本。
     */
    @Test
    void shouldRejectTamperedPackageBeforeDeployment() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getOperation().setPackageDir(temporaryDirectory.toString());
        properties.getOperation().setInstallDir(temporaryDirectory.toString());
        FixedScriptExecutor scripts =
                mock(FixedScriptExecutor.class);
        Path request = Files.createDirectories(temporaryDirectory
                .resolve("staging").resolve("TASK202610160001-test"));
        Path ready = request.resolve("package.tar.gz");
        Files.write(ready, new byte[]{1});
        String expected = FileChecksumUtil.calculateSha256(ready);
        Files.write(ready, new byte[]{2});
        Map<String, String> params = new HashMap<>();
        params.put(TaskParamConstants.PACKAGE_LOCAL_PATH, ready.toString());
        params.put(TaskParamConstants.TARGET_VERSION, "7.2.4");
        params.put(TaskParamConstants.PACKAGE_SHA256, expected);
        OperationResultDTO result =
                new DeployVersionServiceImpl(scripts, properties, new VersionPackageServiceImpl(properties))
                        .deployVersion(params);
        assertFalse(result.isSuccess());
        assertEquals(OperationErrorCode.CHECKSUM_MISMATCH,
                result.getErrorCode());
        verifyNoInteractions(scripts);
        assertFalse(Files.exists(request));
    }
}
