package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.service.StopSentinelService;

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
public class StopSentinelServiceImpl implements StopSentinelService {

    private final FixedScriptExecutor fixedScriptExecutor;

    private final AgentProperties agentProperties;


    /**
     * 执行Sentinel停止操作并检查停止状态。
     */
    @Override
    public OperationResultDTO execute() {

        // 获取开始时间。
        long startTime = System.currentTimeMillis();

        // 脚本执行结果。
        CommandResultDTO commandResult;

        // 执行脚本。
        try {
            commandResult = fixedScriptExecutor.execute(
                    ScriptType.STOP_SENTINEL,
                    agentProperties.getOperation().getScriptTimeoutSeconds()
            );
        } catch (FileNotFoundException e) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_NOT_FOUND,
                    "Sentinel停止脚本不存在",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return OperationResultDTO.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Sentinel停止操作被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (IOException e) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Sentinel停止脚本无法执行",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 判断脚本是否执行超时。
        if (commandResult.isTimedOut()) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_TIMEOUT,
                    "Sentinel停止脚本执行超时",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 判断脚本退出码。
        if (commandResult.getExitCode() != 0) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Sentinel停止脚本执行失败",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 等待Sentinel完全停止。
        try {
            return waitForStopped(startTime);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return OperationResultDTO.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "等待Sentinel停止时被中断",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (IOException e) {
            return OperationResultDTO.failure(
                    OperationErrorCode.SENTINEL_FAILED,
                    "Sentinel停止状态检查失败",
                    startTime,
                    System.currentTimeMillis()
            );
        }
    }

    /**
     * 等待Sentinel停止，检查PING和端口状态。
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

        AgentProperties.SentinelConfig sentinelConfig =
                agentProperties.getSentinel();

        while (System.currentTimeMillis() - beginTime < timeoutMillis) {

            // 1. 检查Sentinel是否能够PING。
            pingSuccess = RedisCheckUtil.isPingSuccessful(
                    sentinelConfig.getHost(),
                    sentinelConfig.getPort(),
                    sentinelConfig.getPassword(),
                    sentinelConfig.getTimeout()
            );

            // 2. 检查Sentinel端口是否开放。
            portOpen = RedisCheckUtil.isPortOpen(
                    sentinelConfig.getHost(),
                    sentinelConfig.getPort()
            );

            // 3. 两项检查都不通过，说明Sentinel已经停止。
            if (!pingSuccess && !portOpen) {
                return OperationResultDTO.success(
                        "Sentinel已停止，端口已释放",
                        startTime,
                        System.currentTimeMillis()
                );
            }

            // 4. 等待下一次检查。
            Thread.sleep(intervalMillis);
        }

        // 5. Sentinel无法PING，但端口仍然被占用。
        if (!pingSuccess && portOpen) {
            return OperationResultDTO.failure(
                    OperationErrorCode.PORT_OCCUPIED,
                    "Sentinel无法PING，但端口仍然开放",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 6. 超时后仍未停止。
        return OperationResultDTO.failure(
                OperationErrorCode.SENTINEL_FAILED,
                "Sentinel未在规定时间内完全停止",
                startTime,
                System.currentTimeMillis()
        );
    }

}

