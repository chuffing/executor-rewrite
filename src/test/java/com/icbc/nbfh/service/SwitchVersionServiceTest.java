package com.icbc.nbfh.service;

import com.icbc.nbfh.command.FixedScriptExecutor;
import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.Enum.ScriptType;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.CommandResultDTO;
import com.icbc.nbfh.service.impl.SwitchVersionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只使用脚本替身验证切换结果，不操作实际current。 */
class SwitchVersionServiceTest {

    private FixedScriptExecutor scripts;
    private AgentProperties properties;
    private SwitchVersionService service;
    private Map<String, String> params;

    /** 使用自定义目录，确认服务没有写死安装路径。 */
    @BeforeEach
    void prepare() {
        scripts = mock(FixedScriptExecutor.class);
        properties = new AgentProperties();
        properties.getOperation().setInstallDir("/srv/test redis/versions");
        properties.getOperation().setCurrentDir("/srv/test redis/current");
        service = new SwitchVersionServiceImpl(scripts, properties);
        params = Collections.singletonMap(TaskParamConstants.TARGET_VERSION, "6.2.19");
    }

    /** 三个参数保持既有顺序，成功结果能够交给任务服务上报。 */
    @Test
    void shouldPassArgumentsAndReturnSuccess() throws Exception {
        when(scripts.executeScript(ScriptType.SWITCH_VERSION, 10,
                "6.2.19", "/srv/test redis/versions", "/srv/test redis/current"))
                .thenReturn(new CommandResultDTO(0, "版本软链接切换完成", "", false));
        assertTrue(service.switchVersion(params).isSuccess());
        verify(scripts).executeScript(ScriptType.SWITCH_VERSION, 10,
                "6.2.19", "/srv/test redis/versions", "/srv/test redis/current");
    }

    /** 缺少本机配置时不执行脚本。 */
    @Test
    void shouldRejectMissingLocalConfiguration() {
        properties.getOperation().setInstallDir(" ");
        assertEquals(OperationErrorCode.CONFIG_INVALID, service.switchVersion(params).getErrorCode());
        properties.getOperation().setInstallDir("/srv/test redis/versions");
        properties.getOperation().setCurrentDir(null);
        assertEquals(OperationErrorCode.CONFIG_INVALID, service.switchVersion(params).getErrorCode());
        verifyNoInteractions(scripts);
    }

    /** 超时优先于退出码处理，不能误报切换成功。 */
    @Test
    void shouldReturnTimeout() throws Exception {
        when(scripts.executeScript(eq(ScriptType.SWITCH_VERSION), anyLong(),
                anyString(), anyString(), anyString()))
                .thenReturn(new CommandResultDTO(0, "", "", true));
        assertEquals(OperationErrorCode.COMMAND_TIMEOUT, service.switchVersion(params).getErrorCode());
    }

    /** 非零退出码转换为操作失败。 */
    @Test
    void shouldReturnScriptFailure() throws Exception {
        when(scripts.executeScript(eq(ScriptType.SWITCH_VERSION), anyLong(),
                anyString(), anyString(), anyString()))
                .thenReturn(new CommandResultDTO(1, "", "目标版本未部署", false));
        assertEquals(OperationErrorCode.COMMAND_EXECUTION_FAILED,
                service.switchVersion(params).getErrorCode());
    }

    /** 区分脚本缺失与其他IO异常。 */
    @Test
    void shouldReturnExceptionResults() throws Exception {
        when(scripts.executeScript(eq(ScriptType.SWITCH_VERSION), anyLong(),
                anyString(), anyString(), anyString()))
                .thenThrow(new FileNotFoundException("missing"))
                .thenThrow(new IOException("failed"));
        assertEquals(OperationErrorCode.COMMAND_NOT_FOUND, service.switchVersion(params).getErrorCode());
        assertEquals(OperationErrorCode.COMMAND_EXECUTION_FAILED,
                service.switchVersion(params).getErrorCode());
    }

    /** 中断后保留线程标记，并返回可上报的结果。 */
    @Test
    void shouldPreserveInterruptedState() throws Exception {
        when(scripts.executeScript(eq(ScriptType.SWITCH_VERSION), anyLong(),
                anyString(), anyString(), anyString())).thenThrow(new InterruptedException());
        try {
            assertEquals(OperationErrorCode.UNKNOWN_ERROR, service.switchVersion(params).getErrorCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
