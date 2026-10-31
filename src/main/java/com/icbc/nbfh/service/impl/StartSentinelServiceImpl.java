package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.service.StartSentinelService;

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

        // 1. 检查Sentinel监控服务名称。
        String serviceName = agentProperties.getSentinel().getServiceName();

        if (serviceName == null || serviceName.trim().isEmpty()) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Sentinel监控服务名称未配置",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        AgentProperties.SentinelConfig sentinelConfig =
                agentProperties.getSentinel();

        serviceName = serviceName.trim();

        // 2. Sentinel已经正常运行并发现主节点时，直接返回成功。
        if (isSentinelStarted(sentinelConfig, serviceName)) {
            return OperationResultDTO.success(
                    "Sentinel已经处于正常运行状态",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 脚本执行结果。
        CommandResultDTO commandResult;

        // 3. Sentinel尚未正常运行，执行启动脚本。
        try {
            commandResult = fixedScriptExecutor.executeScript(
                    ScriptType.START_SENTINEL,
                    agentProperties.getOperation().getScriptTimeoutSeconds(),
                    agentProperties.getOperation().getCurrentDir()
            );

        } catch (FileNotFoundException e) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Sentinel启动脚本不存在",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (InterruptedException e) {
            return OperationResultDTO.failure(
                    BusinessCode.SYSTEM_ERROR,
                    "Sentinel启动操作被中断",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (IOException e) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Sentinel启动脚本无法执行",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 4. 脚本执行结束后，统一等待Sentinel达到正常运行状态。
        // 即使脚本超时或退出码异常，只要Sentinel最终正常运行，也认为操作成功。
        OperationResultDTO startResult;

        try {
            startResult = waitForStarted(startTime, serviceName);

        } catch (InterruptedException e) {
            return OperationResultDTO.failure(
                    BusinessCode.SYSTEM_ERROR,
                    "等待Sentinel启动时被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 5. Sentinel已经达到正常运行状态，直接返回成功。
        if (startResult.isSuccess()) {
            return startResult;
        }

        // 6. Sentinel没有正常运行，再根据脚本执行结果返回具体失败原因。
        if (commandResult.isTimedOut()) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Sentinel启动脚本执行超时，并且Sentinel仍未正常运行",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        if (commandResult.getExitCode() != 0) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "Sentinel启动脚本执行失败，并且Sentinel仍未正常运行",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 7. 脚本正常退出，但Sentinel最终没有正常运行，返回状态检查结果。
        return startResult;
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

            // 1. 检查Sentinel是否正常运行并发现被监控的Redis主节点。
            if (isSentinelStarted(sentinelConfig, serviceName)) {
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
                BusinessCode.OPERATION_ERROR,
                "Sentinel未在规定时间内恢复服务",
                startTime,
                System.currentTimeMillis()
        );
    }

    /**
     * 检查Sentinel是否已经正常运行并发现Redis主节点。
     */
    private boolean isSentinelStarted(
            AgentProperties.SentinelConfig sentinelConfig,
            String serviceName) {

        return RedisCheckUtil.isSentinelMasterDiscovered(
                sentinelConfig.getHost(),
                sentinelConfig.getPort(),
                sentinelConfig.getPassword(),
                sentinelConfig.getTimeout(),
                serviceName
        );
    }

}

