package com.icbc.nbfh.service;

import com.icbc.nbfh.command.FixedScriptExecutor;
import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.Enum.ScriptType;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.CommandResultDTO;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.service.impl.PrecheckHostServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** 使用临时目录及脚本替身，不修改真实主机环境。 */
class PrecheckHostServiceTest {

    @TempDir
    Path root;

    private AgentProperties properties;
    private FixedScriptExecutor scripts;
    private PrecheckHostService service;

    /** 准备新机目录，但不创建Redis配置、版本程序、备份或current。 */
    @BeforeEach
    void prepare() throws Exception {
        properties = new AgentProperties();
        properties.getOperation().setPackageDir(Files.createDirectory(root.resolve("packages")).toString());
        properties.getOperation().setInstallDir(Files.createDirectory(root.resolve("versions")).toString());
        properties.getOperation().setScriptDir(Files.createDirectory(root.resolve("scripts")).toString());
        properties.getOperation().setCurrentDir(root.resolve("current").toString());
        properties.getOperation().setMinFreeSpaceBytes(1L);
        properties.getRedis().setDataDir(Files.createDirectory(root.resolve("data")).toString());
        Path configs = Files.createDirectory(root.resolve("configs"));
        properties.getRedis().setConfigFile(configs.resolve("redis.conf").toString());
        properties.getSentinel().setConfigFile(configs.resolve("sentinel.conf").toString());
        Path backups = Files.createDirectory(root.resolve("backups"));
        properties.getRedis().setBackupFile(backups.resolve("redis.conf").toString());
        properties.getSentinel().setBackupFile(backups.resolve("sentinel.conf").toString());
        properties.getSentinel().setServiceName("test-master");
        properties.getRedis().setHost("127.0.0.1");
        properties.getSentinel().setHost("127.0.0.1");
        // Windows拒绝本机连接通常约需2秒，测试超时留出余量。
        properties.getRedis().setTimeout(3000);
        properties.getSentinel().setTimeout(3000);
        try (ServerSocket redis = new ServerSocket(0); ServerSocket sentinel = new ServerSocket(0)) {
            properties.getRedis().setPort(redis.getLocalPort());
            properties.getSentinel().setPort(sentinel.getLocalPort());
        }
        for (ScriptType type : ScriptType.values()) {
            Files.write(root.resolve("scripts").resolve(type.getFileName()),
                    "# test-only-script\n".getBytes(StandardCharsets.UTF_8));
        }
        scripts = mock(FixedScriptExecutor.class);
        when(scripts.executeScript(ScriptType.PRECHECK_HOST, 10))
                .thenReturn(new CommandResultDTO(0, "依赖检查通过", "", false));
        service = new PrecheckHostServiceImpl(scripts, properties);
    }

    /** 新机缺少待部署文件属于正常状态，检查本身不产生任何文件。 */
    @Test
    void shouldAllowFreshHostWithoutChangingFiles() throws Exception {
        List<Path> before;
        try (Stream<Path> paths = Files.walk(root)) {
            before = paths.sorted().collect(Collectors.toList());
        }
        assertTrue(service.precheckHost().isSuccess());
        try (Stream<Path> paths = Files.walk(root)) {
            assertEquals(before, paths.sorted().collect(Collectors.toList()));
        }
        assertFalse(Files.exists(root.resolve("current")));
        assertFalse(Files.exists(root.resolve("configs/redis.conf")));
        assertFalse(Files.exists(root.resolve("configs/sentinel.conf")));
        verify(scripts).executeScript(ScriptType.PRECHECK_HOST, 10);
        verifyNoMoreInteractions(scripts);
    }

    /** 基础目录缺失时返回失败，不自动补建。 */
    @Test
    void shouldRejectMissingDirectory() {
        properties.getOperation().setPackageDir(root.resolve("missing").toString());
        assertEquals(OperationErrorCode.CONFIG_INVALID, service.precheckHost().getErrorCode());
        assertFalse(Files.exists(root.resolve("missing")));
        verifyNoInteractions(scripts);
    }

    /** 相对路径不能被工作目录隐式改变含义。 */
    @Test
    void shouldRejectRelativePath() {
        properties.getRedis().setDataDir("data");
        assertEquals(OperationErrorCode.CONFIG_INVALID, service.precheckHost().getErrorCode());
        verifyNoInteractions(scripts);
    }

    /** 配置文件父目录需要预先准备。 */
    @Test
    void shouldRejectMissingConfigParent() {
        properties.getSentinel().setConfigFile(root.resolve("missing/sentinel.conf").toString());
        assertEquals(OperationErrorCode.CONFIG_INVALID, service.precheckHost().getErrorCode());
        assertFalse(Files.exists(root.resolve("missing")));
    }

    /** current不能是普通文件，也不能是真实目录。 */
    @Test
    void shouldRejectRegularCurrent() throws Exception {
        Path current = root.resolve("current");
        Files.write(current, "keep".getBytes(StandardCharsets.UTF_8));
        assertEquals(OperationErrorCode.CONFIG_INVALID, service.precheckHost().getErrorCode());
        assertEquals("keep", new String(Files.readAllBytes(current), StandardCharsets.UTF_8));
        Files.delete(current);
        Files.createDirectory(current);
        assertEquals(OperationErrorCode.CONFIG_INVALID, service.precheckHost().getErrorCode());
        assertTrue(Files.isDirectory(current));
    }

    /** 在支持符号链接的平台验证有效链接及越界链接。 */
    @Test
    void shouldCheckExistingVersionLink() throws Exception {
        Path version = Files.createDirectory(root.resolve("versions/6.2.19"));
        Path current = root.resolve("current");
        try {
            Files.createSymbolicLink(current, version);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "当前测试账户不具备创建原生符号链接的权限");
        }
        assertTrue(service.precheckHost().isSuccess());
        Files.delete(current);
        Files.createSymbolicLink(current, root.resolve("data"));
        assertEquals(OperationErrorCode.CONFIG_INVALID, service.precheckHost().getErrorCode());
    }

    /** 磁盘低于配置基线时不继续执行脚本。 */
    @Test
    void shouldRejectInsufficientDisk() {
        properties.getOperation().setMinFreeSpaceBytes(Long.MAX_VALUE);
        assertEquals(OperationErrorCode.RESOURCE_INSUFFICIENT, service.precheckHost().getErrorCode());
        verifyNoInteractions(scripts);
    }

    /** 真实监听测试临时端口，不连接任何Redis实例。 */
    @Test
    void shouldRejectOccupiedPort() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            properties.getRedis().setPort(listener.getLocalPort());
            assertEquals(OperationErrorCode.PORT_OCCUPIED, service.precheckHost().getErrorCode());
        }
        verifyNoInteractions(scripts);
    }

    /** 远程地址不能被误认为本机部署地址，也不发出远程连接。 */
    @Test
    void shouldRejectRemoteAddress() {
        properties.getRedis().setHost("203.0.113.1");
        assertEquals(OperationErrorCode.CONFIG_INVALID, service.precheckHost().getErrorCode());
        verifyNoInteractions(scripts);
    }

    /** 已实现操作的必要脚本缺失时只返回错误，不写入占位脚本。 */
    @ParameterizedTest
    @EnumSource(value = ScriptType.class, names = {
            "PRECHECK_HOST", "START_REDIS", "STOP_REDIS", "START_SENTINEL",
            "STOP_SENTINEL", "DEPLOY_VERSION", "SWITCH_VERSION"
    })
    void shouldRejectMissingScript(ScriptType type) throws Exception {
        Path missing = root.resolve("scripts").resolve(type.getFileName());
        Files.delete(missing);
        assertEquals(OperationErrorCode.COMMAND_NOT_FOUND, service.precheckHost().getErrorCode());
        assertFalse(Files.exists(missing));
        verifyNoInteractions(scripts);
    }

    /** 业务路径迁回Service后仍拒绝相对配置路径和相对current路径。 */
    @Test
    void shouldRejectRelativeBusinessPaths() {
        properties.getRedis().setConfigFile("configs/redis.conf");
        assertEquals(OperationErrorCode.CONFIG_INVALID, service.precheckHost().getErrorCode());
        properties.getRedis().setConfigFile(root.resolve("configs/redis.conf").toString());
        properties.getOperation().setCurrentDir("current");
        assertEquals(OperationErrorCode.CONFIG_INVALID, service.precheckHost().getErrorCode());
        verifyNoInteractions(scripts);
    }

    /** 已有配置文件不能是真实目录，检查不能改动目录内容。 */
    @Test
    void shouldRejectConfigDirectory() throws Exception {
        Path config = Files.createDirectory(root.resolve("configs/redis.conf"));
        assertEquals(OperationErrorCode.CONFIG_INVALID, service.precheckHost().getErrorCode());
        assertTrue(Files.isDirectory(config));
        verifyNoInteractions(scripts);
    }

    /** 依赖检查的超时与非零退出码均不能误报成功。 */
    @Test
    void shouldCheckScriptResults() throws Exception {
        when(scripts.executeScript(ScriptType.PRECHECK_HOST, 10))
                .thenReturn(new CommandResultDTO(-1, "", "", true))
                .thenReturn(new CommandResultDTO(1, "", "缺少系统命令", false));
        assertEquals(OperationErrorCode.COMMAND_TIMEOUT, service.precheckHost().getErrorCode());
        assertEquals(OperationErrorCode.PRECONDITION_FAILED, service.precheckHost().getErrorCode());
    }

    /** 脚本执行中断后返回失败并保留线程中断标记。 */
    @Test
    void shouldPreserveInterruption() throws Exception {
        when(scripts.executeScript(ScriptType.PRECHECK_HOST, 10)).thenThrow(new InterruptedException());
        try {
            OperationResultDTO result = service.precheckHost();
            assertEquals(OperationErrorCode.UNKNOWN_ERROR, result.getErrorCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
