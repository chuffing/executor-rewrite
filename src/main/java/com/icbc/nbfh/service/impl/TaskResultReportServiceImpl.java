package com.icbc.nbfh.service.impl;

import com.alibaba.fastjson.JSON;
import com.icbc.nbfh.client.PlatformClient;
import com.icbc.nbfh.common.constant.TaskResultConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;
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

        // 1. 生成结果文件路径。
        Path directory = Paths.get(
                agentProperties.getTaskResult().getDirectory()
        ).toAbsolutePath().normalize();

        String fileName = report.getTaskNo()
                + "-"
                + report.getStepNo()
                + TaskResultConstants.RESULT_FILE_SUFFIX;

        Path resultFile = directory.resolve(fileName);

        // 2. 先保存完整任务结果。
        try {
            saveResult(resultFile, report);

        } catch (IOException e) {

            log.error(
                    "任务结果本地保存失败，taskNo={}，stepNo={}",
                    report.getTaskNo(),
                    report.getStepNo(),
                    e
            );

            throw new IllegalStateException("任务结果本地保存失败", e);
        }

        // 3. 本地结果保存成功后，只尝试一次平台上报。
        reportResult(resultFile, report);
    }


    /**
     * 执行一次结果上报。
     *
     * 上报成功：删除本地文件。
     * 上报失败：保留本地结果文件并记录日志，供人工排查。
     */
    private void reportResult(
            Path resultFile,
            TaskResultReportDTO report) {

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

            // 上报成功，删除本地结果文件。
            try {
                Files.deleteIfExists(resultFile);

                log.info(
                        "任务结果上报成功，taskNo={}，stepNo={}",
                        report.getTaskNo(),
                        report.getStepNo()
                );

            } catch (IOException e) {
                // 平台已接收结果，删除失败不需要重新执行或上报任务。
                // 文件保留在本地，由人工确认平台记录后清理。
                log.error(
                        "任务结果已上报，但本地文件删除失败，file={}",
                        resultFile,
                        e
                );
            }

        } catch (Exception e) {
            log.error(
                    "任务结果首次上报失败，本地结果文件已保留，需人工处理，"
                            + "taskNo={}，stepNo={}",
                    report.getTaskNo(),
                    report.getStepNo(),
                    e
            );
        }
    }


    /**
     * 将任务结果保存到本地JSON文件。
     *
     * 先写临时文件，再替换正式文件。
     */
    private void saveResult(
            Path resultFile,
            TaskResultReportDTO report) throws IOException {

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
                JSON.toJSONString(report).getBytes(StandardCharsets.UTF_8),
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
}
