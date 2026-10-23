package com.icbc.nbfh.service;

import com.icbc.nbfh.command.FixedScriptExecutor;
import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.Enum.ScriptType;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.CommandResultDTO;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.service.impl.DeployVersionServiceImpl;
import com.icbc.nbfh.util.FileChecksumUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 源码编译部署服务只调用假脚本，不执行真实编译或操作current。 */
class DeployVersionServiceTest {

    @TempDir
    Path root;

    private Path packageFile;
    private Path packages;
    private Path versions;
    private FixedScriptExecutor scripts;
    private VersionPackageService packageService;
    private DeployVersionService service;
    private Map<String, String> params;

    /** 创建完全隔离的目录与脚本替身。 */
    @BeforeEach
    void prepare() throws Exception {
        packages = Files.createDirectory(root.resolve("packages"));
        versions = Files.createDirectory(root.resolve("versions"));
        Path request = Files.createDirectories(packages.resolve("staging").resolve("TASK-test"));
        packageFile = request.resolve("package.tar.gz");
        Files.write(packageFile, new byte[]{1, 2, 3});
        AgentProperties properties = new AgentProperties();
        properties.getOperation().setInstallDir(versions.toString());
        properties.getOperation().setCurrentDir(root.resolve("current").toString());
        properties.getOperation().setVersionDeployTimeoutSeconds(1800);
        scripts = mock(FixedScriptExecutor.class);
        packageService = mock(VersionPackageService.class);
        service = new DeployVersionServiceImpl(scripts, properties, packageService);
        params = new HashMap<>();
        params.put(TaskParamConstants.PACKAGE_LOCAL_PATH, packageFile.toString());
        params.put(TaskParamConstants.TARGET_VERSION, "6.2.19");
        params.put(TaskParamConstants.PACKAGE_SHA256, FileChecksumUtil.calculateSha256(packageFile));
    }

    /** Java只传三个业务参数，由脚本负责创建正式版本目录。 */
    @Test
    void shouldDeployIntoSeparateInstallDirectory() throws Exception {
        when(scripts.executeScript(eq(ScriptType.DEPLOY_VERSION), anyLong(),
                anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            Files.createDirectories(versions.resolve("6.2.19/bin"));
            return new CommandResultDTO(0, "", "", false);
        });
        OperationResultDTO result = service.deployVersion(params);
        assertTrue(result.isSuccess());
        verify(scripts).executeScript(ScriptType.DEPLOY_VERSION, 1800,
                packageFile.toString(), "6.2.19", versions.toAbsolutePath().toString());
        verify(packageService).deleteStagedPackage(packageFile);
        assertFalse(Files.exists(root.resolve("current")));
        assertFalse(Files.exists(packages.resolve("6.2.19")));
    }

    /** 已有目录和其他版本不受新部署影响。 */
    @Test
    void shouldNotOverwriteExistingVersion() throws Exception {
        Path existing = Files.createDirectory(versions.resolve("6.2.19"));
        Path marker = existing.resolve("keep");
        Files.write(marker, new byte[]{7});
        when(scripts.executeScript(eq(ScriptType.DEPLOY_VERSION), anyLong(),
                anyString(), anyString(), anyString()))
                .thenReturn(new CommandResultDTO(1, "", "目标版本已存在", false));
        OperationResultDTO result = service.deployVersion(params);
        assertEquals(OperationErrorCode.COMMAND_EXECUTION_FAILED, result.getErrorCode());
        verify(scripts).executeScript(eq(ScriptType.DEPLOY_VERSION), anyLong(),
                anyString(), anyString(), anyString());
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(marker));
    }

    /** 内容被修改时必须在调用脚本前拒绝。 */
    @Test
    void shouldRejectChangedPackage() throws Exception {
        Files.write(packageFile, new byte[]{4});
        assertEquals(OperationErrorCode.CHECKSUM_MISMATCH,
                service.deployVersion(params).getErrorCode());
        verifyNoInteractions(scripts);
    }

    /** 脚本超时继续保留现有超时错误码。 */
    @Test
    void shouldReportScriptTimeout() throws Exception {
        when(scripts.executeScript(eq(ScriptType.DEPLOY_VERSION), anyLong(),
                anyString(), anyString(), anyString()))
                .thenReturn(new CommandResultDTO(-1, "", "", true));
        assertEquals(OperationErrorCode.COMMAND_TIMEOUT,
                service.deployVersion(params).getErrorCode());
        verify(packageService).deleteStagedPackage(packageFile);
    }

    /** Java只解释退出码，正式目录是否完整由脚本确认。 */
    @Test
    void shouldUseScriptExitCodeAsDeploymentResult() throws Exception {
        when(scripts.executeScript(eq(ScriptType.DEPLOY_VERSION), anyLong(),
                anyString(), anyString(), anyString()))
                .thenReturn(new CommandResultDTO(1, "", "failed", false))
                .thenReturn(new CommandResultDTO(0, "", "", false));
        assertEquals(OperationErrorCode.COMMAND_EXECUTION_FAILED,
                service.deployVersion(params).getErrorCode());
        assertTrue(service.deployVersion(params).isSuccess());
    }

    /** 源码包不存在或是目录时，不计算摘要、不执行脚本。 */
    @Test
    void shouldRejectMissingOrNonRegularPackage() throws Exception {
        Files.delete(packageFile);
        assertEquals(OperationErrorCode.PRECONDITION_FAILED,
                service.deployVersion(params).getErrorCode());
        Files.createDirectory(packageFile);
        assertEquals(OperationErrorCode.PRECONDITION_FAILED,
                service.deployVersion(params).getErrorCode());
        verifyNoInteractions(scripts);
        verify(packageService, times(2)).deleteStagedPackage(packageFile);
    }
}
