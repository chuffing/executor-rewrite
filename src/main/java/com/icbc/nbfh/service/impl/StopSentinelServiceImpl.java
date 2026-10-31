package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.service.StopSentinelService;

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
public class StopSentinelServiceImpl implements StopSentinelService {

    private final FixedScriptExecutor fixedScriptExecutor;

    private final AgentProperties agentProperties;


    /**
     * 执行Sentinel停止操作并检查停止状态。
     */
    @Override
    public OperationResultDTO stopSentinel() {

        long startTime = System.currentTimeMillis();

        // 1. Sentinel端口已经关闭时，目标状态已经满足，直接返回成功。
        try {
            if (isSentinelStopped()) {
                return OperationResultDTO.success(
                        "Sentinel已经处于停止状态",
                        startTime,
                        System.currentTimeMillis()
                );
            }

        } catch (IOException e) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Sentinel停止前状态检查失败",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 脚本执行结果。
        CommandResultDTO commandResult;

        // 2. Sentinel仍在运行，执行停止脚本。
        try {
            commandResult = fixedScriptExecutor.executeScript(
                    ScriptType.STOP_SENTINEL,
                    agentProperties.getOperation().getScriptTimeoutSeconds()
            );

        } catch (FileNotFoundException e) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Sentinel停止脚本不存在",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (InterruptedException e) {
            return OperationResultDTO.failure(
                    BusinessCode.SYSTEM_ERROR,
                    "Sentinel停止操作被中断",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (IOException e) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Sentinel停止脚本无法执行",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 3. 脚本执行结束后，统一等待Sentinel达到停止状态。
        // 即使脚本超时或退出码异常，只要最终已经停止，也认为操作成功。
        OperationResultDTO stopResult;

        try {
            stopResult = waitForStopped(startTime);

        } catch (InterruptedException e) {
            return OperationResultDTO.failure(
                    BusinessCode.SYSTEM_ERROR,
                    "等待Sentinel停止时被中断",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (IOException e) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Sentinel停止状态检查失败",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 4. Sentinel已经达到停止状态，直接返回成功。
        if (stopResult.isSuccess()) {
            return stopResult;
        }

        // 5. Sentinel没有停止，再根据脚本执行结果返回具体失败原因。
        if (commandResult.isTimedOut()) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Sentinel停止脚本执行超时，并且Sentinel仍未停止",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        if (commandResult.getExitCode() != 0) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Sentinel停止脚本执行失败，并且Sentinel仍未停止",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 6. 脚本正常退出，但Sentinel最终没有停止，返回状态检查结果。
        return stopResult;
    }

    /**
     * 检查Sentinel端口是否已经关闭。
     */
    private boolean isSentinelStopped() throws IOException {

        AgentProperties.SentinelConfig sentinelConfig =
                agentProperties.getSentinel();

        return !RedisCheckUtil.isLocalPortListening(
                sentinelConfig.getHost(),
                sentinelConfig.getPort(),
                sentinelConfig.getTimeout()
        );
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
                    BusinessCode.OPERATION_ERROR,
                    "Sentinel无法PING，但端口仍然开放",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 6. 超时后仍未停止。
        return OperationResultDTO.failure(
                BusinessCode.OPERATION_ERROR,
                "Sentinel未在规定时间内完全停止",
                startTime,
                System.currentTimeMillis()
        );
    }

}

