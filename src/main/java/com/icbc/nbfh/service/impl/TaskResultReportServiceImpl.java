package com.icbc.nbfh.service.impl;

import com.alibaba.fastjson.JSON;
import com.icbc.nbfh.client.PlatformClient;
import com.icbc.nbfh.common.constant.TaskResultConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;
import com.icbc.nbfh.entity.DTO.TaskResultReportRecordDTO;
import com.icbc.nbfh.service.RegisterService;
import com.icbc.nbfh.service.TaskResultReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 任务执行结果本地保存与上报服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskResultReportServiceImpl
        implements TaskResultReportService {

    private final PlatformClient platformClient;

    private final RegisterService registerService;

    private final AgentProperties agentProperties;


    /**
     * 保存任务执行结果，并立即尝试第一次上报。
     */
    @Override
    public synchronized void saveAndReport(TaskResultReportDTO report) {

        // 1. 创建本地上报记录。
        TaskResultReportRecordDTO record = new TaskResultReportRecordDTO();

        record.setReport(report);
        record.setAttemptCount(0);
        record.setLastAttemptTime(null);
        record.setNextAttemptTime(System.currentTimeMillis());
        record.setLastError(null);

        // 2. 生成结果文件路径。
        Path directory = Paths.get(
                agentProperties.getTaskResult().getDirectory()
        ).toAbsolutePath().normalize();

        String fileName = report.getTaskNo()
                + "-"
                + report.getStepNo()
                + TaskResultConstants.RESULT_FILE_SUFFIX;

        Path resultFile = directory.resolve(fileName);

        // 3. 先保存本地，再向平台上报。
        try {
            saveRecord(resultFile, record);

            reportRecord(resultFile, record);

        } catch (IOException e) {

            log.error(
                    "任务结果本地保存或更新失败，taskNo={}，stepNo={}",
                    report.getTaskNo(),
                    report.getStepNo(),
                    e
            );

            throw new IllegalStateException("任务结果本地保存或更新失败", e);
        }
    }


    /**
     * 定时扫描本地待上报文件，补报执行结果。
     */
    @Override
    public synchronized void retryPendingResults() {

        // 1. 获取本地结果目录。
        Path directory = Paths.get(
                agentProperties.getTaskResult().getDirectory()
        ).toAbsolutePath().normalize();

        // 目录不存在，说明目前没有待上报文件。
        if (!Files.isDirectory(directory)) {
            return;
        }

        // 2. 查询所有待上报的JSON文件。
        List<Path> resultFiles;

        try (Stream<Path> stream = Files.list(directory)) {

            resultFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .endsWith(TaskResultConstants.RESULT_FILE_SUFFIX))
                    .sorted()
                    .collect(Collectors.toList());

        } catch (IOException e) {

            log.error("扫描本地待上报任务结果失败", e);
            return;
        }

        // 3. 获取当前时间和最大上报次数。
        long now = System.currentTimeMillis();

        int maxAttempts = agentProperties.getTaskResult()
                .getMaxAttempts();

        // 4. 遍历本地结果文件。
        for (Path resultFile : resultFiles) {

            try {

                // 读取JSON文件中的上报记录。
                TaskResultReportRecordDTO record = loadRecord(resultFile);

                // 上报次数达到上限，跳过。
                if (record.getAttemptCount() >= maxAttempts) {
                    continue;
                }

                // 尚未到达下一次重试时间，跳过。
                if (record.getNextAttemptTime() != null
                        && record.getNextAttemptTime() > now) {
                    continue;
                }

                // 满足条件，重新向平台上报结果。
                reportRecord(resultFile, record);

            } catch (Exception e) {

                // 单个文件发生异常，不影响其他文件继续处理。
                log.error(
                        "本地任务结果重试处理失败，file={}",
                        resultFile,
                        e
                );
            }
        }
    }


    /**
     * 执行一次结果上报。
     *
     * 上报成功：删除本地文件。
     * 上报失败：保存失败信息，等待后续重试。
     */
    private void reportRecord(
            Path resultFile,
            TaskResultReportRecordDTO record) throws IOException {

        TaskResultReportDTO report = record.getReport();

        // 1. 更新本次上报信息。
        int attemptCount = record.getAttemptCount() + 1;

        long now = System.currentTimeMillis();

        long retryInterval = agentProperties.getTaskResult()
                .getRetryIntervalMillis();

        int maxAttempts = agentProperties.getTaskResult()
                .getMaxAttempts();

        record.setAttemptCount(attemptCount);
        record.setLastAttemptTime(now);
        record.setNextAttemptTime(now + retryInterval);
        record.setLastError(null);

        // 2. 发送HTTP前先保存尝试次数。
        saveRecord(resultFile, record);

        // 3. 尝试向平台上报。
        try {

            // 确保执行器已经完成平台注册。
            if (!registerService.registerIfNeeded()) {
                throw new IllegalStateException("执行器尚未完成平台注册");
            }

            // 向平台发送任务执行结果。
            platformClient.reportTaskResult(
                    report,
                    agentProperties.getPlatform().getUrl(),
                    registerService.getToken()
            );

            // 4. 上报成功，删除本地结果文件。
            try {
                Files.deleteIfExists(resultFile);

            } catch (IOException e) {

                // 平台已接收结果，删除失败不需要重新执行任务。
                // 后续重复上报由平台进行幂等处理。
                log.error(
                        "任务结果已上报，但本地文件删除失败，file={}",
                        resultFile,
                        e
                );
            }

            log.info(
                    "任务结果上报成功，taskNo={}，stepNo={}，attempt={}",
                    report.getTaskNo(),
                    report.getStepNo(),
                    attemptCount
            );

        } catch (Exception e) {

            // 5. HTTP上报失败，记录错误信息。
            String errorMessage = e.getMessage();

            if (errorMessage == null || errorMessage.trim().isEmpty()) {
                errorMessage = e.getClass().getSimpleName();
            }

            // 限制错误信息长度。
            if (errorMessage.length()
                    > TaskResultConstants.MAX_ERROR_MESSAGE_LENGTH) {

                errorMessage = errorMessage.substring(
                        0,
                        TaskResultConstants.MAX_ERROR_MESSAGE_LENGTH
                );
            }

            record.setLastError(errorMessage);

            // 达到最大次数后，不再自动重试。
            if (attemptCount >= maxAttempts) {

                record.setNextAttemptTime(null);

            } else {

                record.setNextAttemptTime(
                        System.currentTimeMillis() + retryInterval
                );
            }

            // 将失败信息重新保存到本地文件。
            saveRecord(resultFile, record);

            if (attemptCount >= maxAttempts) {

                log.error(
                        "任务结果上报失败，已达到最大次数，"
                                + "taskNo={}，stepNo={}，attempts={}",
                        report.getTaskNo(),
                        report.getStepNo(),
                        attemptCount,
                        e
                );

            } else {

                log.warn(
                        "任务结果上报失败，等待下次重试，"
                                + "taskNo={}，stepNo={}，attempt={}/{}",
                        report.getTaskNo(),
                        report.getStepNo(),
                        attemptCount,
                        maxAttempts,
                        e
                );
            }
        }
    }


    /**
     * 将上报记录保存到本地JSON文件。
     *
     * 先写临时文件，再替换正式文件。
     */
    private void saveRecord(
            Path resultFile,
            TaskResultReportRecordDTO record) throws IOException {

        // 1. 确保结果目录存在。
        Files.createDirectories(resultFile.getParent());

        // 2. 生成临时文件路径。
        Path tempFile = resultFile.resolveSibling(
                resultFile.getFileName().toString()
                        + TaskResultConstants.TEMP_FILE_SUFFIX
        );

        // 3. 将对象转成JSON，并写入临时文件。
        Files.write(
                tempFile,
                JSON.toJSONString(record).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        // 4. 用临时文件替换正式文件。
        try {

            Files.move(
                    tempFile,
                    resultFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );

        } catch (AtomicMoveNotSupportedException e) {

            // 文件系统不支持原子移动时，使用普通替换。
            Files.move(
                    tempFile,
                    resultFile,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }


    /**
     * 从本地JSON文件读取上报记录。
     */
    private TaskResultReportRecordDTO loadRecord(
            Path resultFile) throws IOException {

        // 1. 读取文件内容。
        String content = new String(
                Files.readAllBytes(resultFile),
                StandardCharsets.UTF_8
        );

        // 2. 将JSON转换为Java对象。
        TaskResultReportRecordDTO record = JSON.parseObject(
                content,
                TaskResultReportRecordDTO.class
        );

        // 3. 检查文件是否包含实际任务结果。
        if (record == null || record.getReport() == null) {
            throw new IllegalStateException("本地任务结果文件内容不完整");
        }

        // 返回读取到的上报记录。
        return record;
    }
}