package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.service.StartSentinelService;

import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.Enum.ScriptType;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.CommandResultDTO;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.command.FixedScriptExecutor;
import com.icbc.nbfh.util.RedisCheckUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class StartSentinelServiceImpl implements StartSentinelService {

    private final FixedScriptExecutor fixedScriptExecutor;

    private final AgentProperties agentProperties;

    /**
     * 执行Sentinel启动操作并检查运行状态。
     */
    @Override
    public OperationResultDTO startSentinel() {

        // 获取开始时间。
        long startTime = System.currentTimeMillis();

        // 检查Sentinel监控服务名称。
        String serviceName = agentProperties.getSentinel().getServiceName();

        if (serviceName == null || serviceName.trim().isEmpty()) {
            return OperationResultDTO.failure(
                    OperationErrorCode.CONFIG_INVALID,
                    "Sentinel监控服务名称未配置",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        AgentProperties.SentinelConfig sentinelConfig =
                agentProperties.getSentinel();

        // 脚本执行结果。
        CommandResultDTO commandResult;

        // 1. 启动脚本会先判断Sentinel是否已经处于运行状态。
        try {
            commandResult = fixedScriptExecutor.executeScript(
                    ScriptType.START_SENTINEL,
                    agentProperties.getOperation().getScriptTimeoutSeconds(),
                    agentProperties.getOperation().getCurrentDir()
            );

        } catch (FileNotFoundException e) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_NOT_FOUND,
                    "Sentinel启动脚本不存在",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return OperationResultDTO.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Sentinel启动操作被中断",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (IOException e) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Sentinel启动脚本无法执行",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 判断脚本是否执行超时。
        if (commandResult.isTimedOut()) {
            if (isSentinelRunning(sentinelConfig)) {
                return waitForSentinelAfterScript(startTime, serviceName.trim());
            }
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_TIMEOUT,
                    "Sentinel启动脚本执行超时",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 判断脚本退出码。
        if (commandResult.getExitCode() != 0) {
            if (isSentinelRunning(sentinelConfig)) {
                return waitForSentinelAfterScript(startTime, serviceName.trim());
            }
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Sentinel启动脚本执行失败",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 等待Sentinel恢复服务。
        try {
            return waitForStarted(
                    startTime,
                    serviceName.trim()
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return OperationResultDTO.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "等待Sentinel启动时被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        }
    }

    /**
     * 等待Sentinel启动并发现被监控的Redis主节点。
     */
    private OperationResultDTO waitForStarted(long startTime,
                                              String serviceName)
            throws InterruptedException {

        long timeoutMillis = TimeUnit.SECONDS.toMillis(
                agentProperties.getOperation().getStartCheckTimeoutSeconds()
        );

        long intervalMillis = agentProperties.getOperation()
                .getStartCheckIntervalMillis();

        AgentProperties.SentinelConfig sentinelConfig =
                agentProperties.getSentinel();

        long beginTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - beginTime < timeoutMillis) {

            // 1. 检查Sentinel是否发现被监控的Redis主节点。
            if (RedisCheckUtil.isSentinelMasterDiscovered(
                    sentinelConfig.getHost(),
                    sentinelConfig.getPort(),
                    sentinelConfig.getPassword(),
                    sentinelConfig.getTimeout(),
                    serviceName
            )) {
                return OperationResultDTO.success(
                        "Sentinel已启动并发现Redis主节点",
                        startTime,
                        System.currentTimeMillis()
                );
            }

            // 2. 等待下一次检查。
            Thread.sleep(intervalMillis);
        }

        // 3. 超时后仍未恢复。
        return OperationResultDTO.failure(
                OperationErrorCode.SENTINEL_FAILED,
                "Sentinel未在规定时间内恢复服务",
                startTime,
                System.currentTimeMillis()
        );
    }

    /**
     * 检查Sentinel进程是否已经正常运行。
     */
    private boolean isSentinelRunning(
            AgentProperties.SentinelConfig sentinelConfig) {

        return RedisCheckUtil.isSentinelServer(
                sentinelConfig.getHost(),
                sentinelConfig.getPort(),
                sentinelConfig.getPassword(),
                sentinelConfig.getTimeout()
        );
    }

    /**
     * 脚本虽然返回异常，但Sentinel已经运行时继续检查最终状态。
     */
    private OperationResultDTO waitForSentinelAfterScript(
            long startTime,
            String serviceName) {

        try {
            return waitForStarted(startTime, serviceName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperationResultDTO.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "等待Sentinel启动时被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        }
    }

}

