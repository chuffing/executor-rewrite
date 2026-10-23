package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.command.FixedScriptExecutor;
import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.Enum.ScriptType;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.CommandResultDTO;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.service.SwitchVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SwitchVersionServiceImpl implements SwitchVersionService {

    private final FixedScriptExecutor fixedScriptExecutor;

    private final AgentProperties agentProperties;

    /**
     * 切换current软链接，不负责Redis或Sentinel的启停。
     */
    @Override
    public OperationResultDTO switchVersion(Map<String, String> params) {

        long startTime = System.currentTimeMillis();

        // 从任务参数中读取已校验的目标版本号。
        String targetVersion = params.get(TaskParamConstants.TARGET_VERSION);

        // 从本地配置中获取Redis版本安装目录和current软链接路径。
        String installDir = agentProperties.getOperation().getInstallDir();
        String currentDir = agentProperties.getOperation().getCurrentDir();

        // 检查安装目录是否配置。
        if (!StringUtils.hasText(installDir)) {
            return OperationResultDTO.failure(
                    OperationErrorCode.CONFIG_INVALID,
                    "Redis版本安装目录未配置",
                    startTime,
                    System.currentTimeMillis()
            );
        }
        // 检查current软链接路径是否配置。
        if (!StringUtils.hasText(currentDir)) {
            return OperationResultDTO.failure(
                    OperationErrorCode.CONFIG_INVALID,
                    "Redis当前版本软链接路径未配置",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        try {
            // 调用版本切换脚本。
            CommandResultDTO commandResult = fixedScriptExecutor.executeScript(
                    ScriptType.SWITCH_VERSION,
                    agentProperties.getOperation().getScriptTimeoutSeconds(),
                    targetVersion,
                    installDir,
                    currentDir
            );

            // 判断脚本是否超过配置的执行时间。
            if (commandResult.isTimedOut()) {
                log.error("Redis版本切换脚本执行超时，stdout={}，stderr={}",
                        commandResult.getStdout(), commandResult.getStderr());
                return OperationResultDTO.failure(
                        OperationErrorCode.COMMAND_TIMEOUT,
                        "Redis版本切换脚本执行超时，请核对current实际指向",
                        startTime,
                        System.currentTimeMillis()
                );
            }

            // 检查脚本执行退出码。
            if (commandResult.getExitCode() != 0) {
                log.error("Redis版本切换脚本执行失败，stdout={}，stderr={}",
                        commandResult.getStdout(), commandResult.getStderr());
                return OperationResultDTO.failure(
                        OperationErrorCode.COMMAND_EXECUTION_FAILED,
                        "Redis版本切换脚本执行失败，请查看执行器日志",
                        startTime,
                        System.currentTimeMillis()
                );
            }

            // 脚本返回成功，记录目标版本及脚本输出。
            log.info("Redis版本切换完成，targetVersion={}，detail={}",
                    targetVersion, commandResult.getStdout());
            // 返回版本切换成功结果。
            return OperationResultDTO.success(
                    "Redis当前版本软链接已切换至" + targetVersion,
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (FileNotFoundException e) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_NOT_FOUND,
                    "Redis版本切换脚本不存在",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperationResultDTO.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Redis版本切换被中断，请核对current实际指向",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (IOException e) {
            log.error("Redis版本切换脚本无法执行，targetVersion={}", targetVersion, e);
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Redis版本切换脚本无法执行",
                    startTime,
                    System.currentTimeMillis()
            );
        }
    }
}
