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

        long startTime = System.currentTimeMillis();

        AgentProperties.RedisConfig redisConfig =
                agentProperties.getRedis();

        // 1. Redis已经正常运行时，目标状态已经满足，直接返回成功。
        if (isRedisRunning(redisConfig)) {
            return OperationResultDTO.success(
                    "Redis已经处于运行状态",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 脚本执行结果。
        CommandResultDTO commandResult;

        // 2. Redis尚未正常运行，执行启动脚本。
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

        // 3. 脚本执行结束后，统一等待Redis达到正常运行状态。
        // 即使脚本超时或退出码异常，只要Redis最终正常运行，也认为操作成功。
        OperationResultDTO startResult;

        try {
            startResult = waitForStarted(startTime);

        } catch (InterruptedException e) {
            return OperationResultDTO.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "等待Redis启动时被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 4. Redis已经达到正常运行状态，直接返回成功。
        if (startResult.isSuccess()) {
            return startResult;
        }

        // 5. Redis没有正常运行，再根据脚本执行结果返回具体失败原因。
        if (commandResult.isTimedOut()) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_TIMEOUT,
                    "Redis启动脚本执行超时，并且Redis仍未正常运行",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        if (commandResult.getExitCode() != 0) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Redis启动脚本执行失败，并且Redis仍未正常运行",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 6. 脚本正常退出，但Redis最终没有正常运行，返回状态检查结果。
        return startResult;
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

            // 1. 检查端口上是否为能够正常响应的普通Redis服务。
            if (isRedisRunning(redisConfig)) {
                return OperationResultDTO.success(
                        "Redis已启动并能够正常提供服务",
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

    /**
     * 检查Redis是否已经达到正常运行状态。
     */
    private boolean isRedisRunning(
            AgentProperties.RedisConfig redisConfig) {

        return RedisCheckUtil.isRedisServer(
                redisConfig.getHost(),
                redisConfig.getPort(),
                redisConfig.getPassword(),
                redisConfig.getTimeout()
        );
    }

}

