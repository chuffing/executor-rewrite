package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.service.StartRedisService;

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
public class StartRedisServiceImpl implements StartRedisService {

    private final FixedScriptExecutor fixedScriptExecutor;

    private final AgentProperties agentProperties;

    /**
     * 执行Redis启动操作并检查运行状态。
     */
    @Override
    public OperationResultDTO startRedis() {

        // 获取开始时间。
        long startTime = System.currentTimeMillis();

        // 脚本执行结果。
        CommandResultDTO commandResult;

        // 将已有的current路径传给启动脚本。
        try {
            commandResult = fixedScriptExecutor.executeScript(
                    ScriptType.START_REDIS,
                    agentProperties.getOperation().getScriptTimeoutSeconds(),
                    agentProperties.getOperation().getCurrentDir()
            );
        } catch (FileNotFoundException e) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_NOT_FOUND,
                    "Redis启动脚本不存在",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return OperationResultDTO.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Redis启动操作被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (IOException e) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Redis启动脚本无法执行",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 判断脚本是否执行超时。
        if (commandResult.isTimedOut()) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_TIMEOUT,
                    "Redis启动脚本执行超时",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 判断脚本退出码。
        if (commandResult.getExitCode() != 0) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Redis启动脚本执行失败",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 等待Redis恢复服务。
        try {
            return waitForStarted(startTime);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return OperationResultDTO.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "等待Redis启动时被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        }
    }

    /**
     * 等待Redis启动并恢复服务。
     */
    private OperationResultDTO waitForStarted(long startTime)
            throws InterruptedException {

        long timeoutMillis = TimeUnit.SECONDS.toMillis(
                agentProperties.getOperation().getStartCheckTimeoutSeconds()
        );

        long intervalMillis = agentProperties.getOperation()
                .getStartCheckIntervalMillis();

        AgentProperties.RedisConfig redisConfig =
                agentProperties.getRedis();

        long beginTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - beginTime < timeoutMillis) {

            // 1. 检查Redis是否能够正常响应PING。
            if (RedisCheckUtil.isPingSuccessful(
                    redisConfig.getHost(),
                    redisConfig.getPort(),
                    redisConfig.getPassword(),
                    redisConfig.getTimeout()
            )) {
                return OperationResultDTO.success(
                        "Redis已启动并能够正常响应PING",
                        startTime,
                        System.currentTimeMillis()
                );
            }

            // 2. 等待下一次检查。
            Thread.sleep(intervalMillis);
        }

        // 3. 超时后仍未恢复。
        return OperationResultDTO.failure(
                OperationErrorCode.START_TIMEOUT,
                "Redis未在规定时间内恢复服务",
                startTime,
                System.currentTimeMillis()
        );
    }

}

