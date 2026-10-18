package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.service.StopRedisService;

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
public class StopRedisServiceImpl implements StopRedisService {

    private final FixedScriptExecutor fixedScriptExecutor;

    private final AgentProperties agentProperties;

    /**
     * 执行Redis停止操作并检查停止状态。
     */
    @Override
    public OperationResultDTO stopRedis() {

        // 获取开始时间。
        long startTime = System.currentTimeMillis();

        // 脚本执行结果。
        CommandResultDTO commandResult;

        // 执行脚本。
        try {
            commandResult = fixedScriptExecutor.executeScript(
                    ScriptType.STOP_REDIS,
                    agentProperties.getOperation().getScriptTimeoutSeconds()
            );
        } catch (FileNotFoundException e) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_NOT_FOUND,
                    "Redis停止脚本不存在",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return OperationResultDTO.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Redis停止操作被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (IOException e) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Redis停止脚本无法执行",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 判断脚本是否执行超时
        if (commandResult.isTimedOut()) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_TIMEOUT,
                    "Redis停止脚本执行超时",
                    startTime,
                    System.currentTimeMillis()
            );
        }
        // 判断脚本退出码
        if (commandResult.getExitCode() != 0) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Redis停止脚本执行失败",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 等待Redis完全停止。
        try {
            return waitForStopped(startTime);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return OperationResultDTO.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "等待Redis停止时被中断",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (IOException e) {
            return OperationResultDTO.failure(
                    OperationErrorCode.HEALTH_CHECK_FAILED,
                    "Redis停止状态检查失败",
                    startTime,
                    System.currentTimeMillis()
            );
        }

    }

    /**
     * 等待Redis停止，检查PING和端口状态。
     */
    private OperationResultDTO waitForStopped(long startTime)
            throws IOException, InterruptedException {

        long timeoutMillis = TimeUnit.SECONDS.toMillis(
                agentProperties.getOperation().getStopCheckTimeoutSeconds()
        );

        long intervalMillis = agentProperties.getOperation()
                .getStopCheckIntervalMillis();

        long beginTime = System.currentTimeMillis();

        boolean pingSuccess = true;
        boolean portOpen = true;

        AgentProperties.RedisConfig redisConfig =
                agentProperties.getRedis();

        while (System.currentTimeMillis() - beginTime < timeoutMillis) {

            // 1. 检查Redis是否能够PING
            pingSuccess = RedisCheckUtil.isPingSuccessful(
                    redisConfig.getHost(),
                    redisConfig.getPort(),
                    redisConfig.getPassword(),
                    redisConfig.getTimeout()
            );

            // 2. 检查Redis端口是否开放
            portOpen = RedisCheckUtil.isPortOpen(
                    redisConfig.getHost(),
                    redisConfig.getPort()
            );

            // 3. 两项检查都通过，说明Redis已停止
            if (!pingSuccess && !portOpen) {
                return OperationResultDTO.success(
                        "Redis已停止，端口已释放",
                        startTime,
                        System.currentTimeMillis()
                );
            }

            // 4. 等待下一次检查
            Thread.sleep(intervalMillis);
        }

        // 5. 超时后仍未停止
        if (!pingSuccess && portOpen) {
            return OperationResultDTO.failure(
                    OperationErrorCode.PORT_OCCUPIED,
                    "Redis无法PING，但端口仍然开放",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        return OperationResultDTO.failure(
                OperationErrorCode.HEALTH_CHECK_FAILED,
                "Redis未在规定时间内完全停止",
                startTime,
                System.currentTimeMillis()
        );
    }
}

