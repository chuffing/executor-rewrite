package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.service.StopRedisService;

import com.icbc.nbfh.common.exception.BusinessCode;
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

        long startTime = System.currentTimeMillis();

        // 1. Redis端口已经关闭时，目标状态已经满足，直接返回成功。
        try {
            if (isRedisStopped()) {
                return OperationResultDTO.success(
                        "Redis已经处于停止状态",
                        startTime,
                        System.currentTimeMillis()
                );
            }

        } catch (IOException e) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Redis停止前状态检查失败",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 脚本执行结果。
        CommandResultDTO commandResult;

        // 2. Redis仍在运行，执行停止脚本。
        try {
            commandResult = fixedScriptExecutor.executeScript(
                    ScriptType.STOP_REDIS,
                    agentProperties.getOperation().getScriptTimeoutSeconds()
            );

        } catch (FileNotFoundException e) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Redis停止脚本不存在",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (InterruptedException e) {
            return OperationResultDTO.failure(
                    BusinessCode.SYSTEM_ERROR,
                    "Redis停止操作被中断",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (IOException e) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Redis停止脚本无法执行",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 3. 脚本执行结束后，统一等待Redis达到停止状态。
        // 即使脚本超时或退出码异常，只要最终已经停止，也认为操作成功。
        OperationResultDTO stopResult;

        try {
            stopResult = waitForStopped(startTime);

        } catch (InterruptedException e) {
            return OperationResultDTO.failure(
                    BusinessCode.SYSTEM_ERROR,
                    "等待Redis停止时被中断",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (IOException e) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Redis停止状态检查失败",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 4. Redis已经达到停止状态，直接返回成功。
        if (stopResult.isSuccess()) {
            return stopResult;
        }

        // 5. Redis没有停止，再根据脚本执行结果返回具体失败原因。
        if (commandResult.isTimedOut()) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Redis停止脚本执行超时，并且Redis仍未停止",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        if (commandResult.getExitCode() != 0) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Redis停止脚本执行失败，并且Redis仍未停止",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 6. 脚本正常退出，但Redis最终没有停止，返回状态检查结果。
        return stopResult;

    }

    /**
     * 检查Redis端口是否已经关闭。
     */
    private boolean isRedisStopped() throws IOException {

        AgentProperties.RedisConfig redisConfig =
                agentProperties.getRedis();

        return !RedisCheckUtil.isLocalPortListening(
                redisConfig.getHost(),
                redisConfig.getPort(),
                redisConfig.getTimeout()
        );
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

            // 3. Redis不能PING并且端口已经关闭，说明Redis已停止。
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
                    BusinessCode.OPERATION_ERROR,
                    "Redis无法PING，但端口仍然开放",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        return OperationResultDTO.failure(
                BusinessCode.OPERATION_ERROR,
                "Redis未在规定时间内完全停止",
                startTime,
                System.currentTimeMillis()
        );
    }
}

