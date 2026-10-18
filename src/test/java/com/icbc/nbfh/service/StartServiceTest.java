package com.icbc.nbfh.service;

import com.icbc.nbfh.command.FixedScriptExecutor;
import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.Enum.ScriptType;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.CommandResultDTO;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.service.impl.StartRedisServiceImpl;
import com.icbc.nbfh.service.impl.StartSentinelServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 使用脚本替身验证current配置传递，不启动Redis或Sentinel。 */
class StartServiceTest {

    /** Redis启动使用执行器配置的current路径。 */
    @Test
    void shouldPassCurrentDirToRedisScript() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getOperation().setCurrentDir("/srv/custom redis/current");
        FixedScriptExecutor scripts = mock(FixedScriptExecutor.class);
        when(scripts.executeScript(ScriptType.START_REDIS, 10, "/srv/custom redis/current"))
                .thenReturn(new CommandResultDTO(1, "", "current无效", false));

        OperationResultDTO result = new StartRedisServiceImpl(scripts, properties).startRedis();

        verify(scripts).executeScript(ScriptType.START_REDIS, 10, "/srv/custom redis/current");
        assertEquals(OperationErrorCode.COMMAND_EXECUTION_FAILED, result.getErrorCode());
    }

    /** Sentinel启动使用同一份current配置，脚本失败后不进入健康检查。 */
    @Test
    void shouldPassCurrentDirToSentinelScript() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getOperation().setCurrentDir("/srv/custom redis/current");
        properties.getSentinel().setServiceName("test-master");
        FixedScriptExecutor scripts = mock(FixedScriptExecutor.class);
        when(scripts.executeScript(ScriptType.START_SENTINEL, 10, "/srv/custom redis/current"))
                .thenReturn(new CommandResultDTO(1, "", "current无效", false));

        OperationResultDTO result = new StartSentinelServiceImpl(scripts, properties).startSentinel();

        verify(scripts).executeScript(ScriptType.START_SENTINEL, 10, "/srv/custom redis/current");
        assertEquals(OperationErrorCode.COMMAND_EXECUTION_FAILED, result.getErrorCode());
    }
}
