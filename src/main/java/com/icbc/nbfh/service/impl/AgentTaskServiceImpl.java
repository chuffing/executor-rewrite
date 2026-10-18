package com.icbc.nbfh.service.impl;

import com.alibaba.fastjson.JSON;
import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.Enum.TaskResultStatus;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;
import com.icbc.nbfh.service.*;
import com.icbc.nbfh.client.PlatformClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskServiceImpl implements AgentTaskService {

    // 一个工作线程串行执行，最多允许一个任务等待。
    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(1),
                    new ThreadPoolExecutor.AbortPolicy()
            );

    private final PrecheckHostService precheckHostService;

    private final StopRedisService stopRedisService;

    private final StartRedisService startRedisService;

    private final StopSentinelService stopSentinelService;

    private final StartSentinelService startSentinelService;

    private final RestoreConfigService restoreConfigService;

    private final DeployConfigService deployConfigService;

    private final DeployVersionService deployVersionService;

    private final SwitchVersionService switchVersionService;

    private final PlatformClient platformClient;

    private final RegisterService registerService;

    private final AgentProperties agentProperties;


    /**
     * 接收平台任务并提交到执行线程。
     */
    @Override
    public void submitTask(PlatformTaskDTO task) {
        executor.execute(() -> {
            executeTask(task);
        });
    }

    /**
     * 根据任务类型执行运维操作并上报结果。
     */
    private void executeTask(PlatformTaskDTO task) {

        // 获取开始时间。
        long startTime = System.currentTimeMillis();

        // 运维操作结果。
        OperationResultDTO result;

        try {
            // 获取操作类型。
            OperationType operationType =
                    OperationType.valueOf(task.getOperationType());

            switch (operationType) {

                case PRECHECK_HOST:
                    result = precheckHostService.precheckHost();
                    break;

                case STOP_REDIS:
                    result = stopRedisService.stopRedis();
                    break;

                case START_REDIS:
                    result = startRedisService.startRedis();
                    break;

                case STOP_SENTINEL:
                    result = stopSentinelService.stopSentinel();
                    break;

                case START_SENTINEL:
                    result = startSentinelService.startSentinel();
                    break;

                case RESTORE_CONFIG:
                    result = restoreConfigService.restoreConfig(task.getParams());
                    break;

                case DEPLOY_CONFIG:
                    result = deployConfigService.deployConfig(task.getParams());
                    break;

                case DEPLOY_VERSION:
                    result = deployVersionService.deployVersion(task.getParams());
                    break;

                case SWITCH_VERSION:
                    result = switchVersionService.switchVersion(task.getParams());
                    break;

                default:
                    throw new UnsupportedOperationException(
                            "暂未实现的操作类型：" + operationType
                    );
            }

        } catch (Exception e) {

            log.error("任务执行异常，taskNo={}", task.getTaskNo(), e);

            result = OperationResultDTO.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "任务执行异常：" + e.getMessage(),
                    startTime,
                    System.currentTimeMillis()
            );
        }

        log.info(
                "任务执行结束，taskNo={}，success={}",
                task.getTaskNo(),
                result.isSuccess()
        );

        // 上报任务执行结果。
        reportTaskResult(task, result);
    }

    /**
     * 组装任务结果报文并上报平台。
     */
    private void reportTaskResult(PlatformTaskDTO task,
                                  OperationResultDTO result) {

        // 1. 获取平台注册信息。
        String agentId = registerService.getAgentId();
        String token = registerService.getToken();
        String platformUrl = agentProperties.getPlatform().getUrl();

        // 2. 将本地操作结果转换为平台要求的报文。
        TaskResultReportDTO report = new TaskResultReportDTO(
                task.getTaskNo(),
                task.getStepNo(),
                agentId,
                task.getOperationType(),
                result.isSuccess()
                        ? TaskResultStatus.SUCCESS.name()
                        : TaskResultStatus.FAILED.name(),
                JSON.toJSONString(result)
        );

        // 3. 调用平台结果上报接口。
        try {
            platformClient.reportTaskResult(
                    report,
                    platformUrl,
                    token
            );

            log.info(
                    "任务结果上报成功，taskNo={}，stepNo={}",
                    task.getTaskNo(),
                    task.getStepNo()
            );

        } catch (Exception e) {
            log.error(
                    "任务结果上报失败，taskNo={}，stepNo={}",
                    task.getTaskNo(),
                    task.getStepNo(),
                    e
            );
        }
    }
}

