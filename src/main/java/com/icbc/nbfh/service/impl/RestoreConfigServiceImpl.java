package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.service.RestoreConfigService;

import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.common.constant.ValidationConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.util.FileChecksumUtil;
import com.icbc.nbfh.util.FileValidationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RestoreConfigServiceImpl implements RestoreConfigService {

    private final AgentProperties agentProperties;

    /**
     * 使用执行器本地备份恢复Redis和Sentinel配置。
     */
    @Override
    public OperationResultDTO restoreConfig(Map<String, String> params) {

        // 获取开始时间。
        long startTime = System.currentTimeMillis();

        // 1. 读取平台提供的两份备份配置SHA256。
        String redisConfigSha256 = getConfigSha256(
                params,
                TaskParamConstants.REDIS_CONFIG_SHA256
        );
        String sentinelConfigSha256 = getConfigSha256(
                params,
                TaskParamConstants.SENTINEL_CONFIG_SHA256
        );

        if (redisConfigSha256 == null
                || sentinelConfigSha256 == null) {
            return OperationResultDTO.failure(
                    OperationErrorCode.CONFIG_INVALID,
                    "平台下发的Redis或Sentinel配置SHA256不正确",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 2. 读取本地配置文件路径。
        String redisBackupFile = agentProperties.getRedis().getBackupFile();
        String redisConfigFile = agentProperties.getRedis().getConfigFile();
        String sentinelBackupFile =
                agentProperties.getSentinel().getBackupFile();
        String sentinelConfigFile =
                agentProperties.getSentinel().getConfigFile();

        // 3. 检查执行器本地配置是否完整。
        if (!StringUtils.hasText(redisBackupFile)
                || !StringUtils.hasText(redisConfigFile)
                || !StringUtils.hasText(sentinelBackupFile)
                || !StringUtils.hasText(sentinelConfigFile)) {
            return OperationResultDTO.failure(
                    OperationErrorCode.CONFIG_INVALID,
                    "Redis或Sentinel配置文件路径未配置完整",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        Path redisBackupPath = Paths.get(redisBackupFile.trim());
        Path redisConfigPath = Paths.get(redisConfigFile.trim());
        Path sentinelBackupPath = Paths.get(sentinelBackupFile.trim());
        Path sentinelConfigPath = Paths.get(sentinelConfigFile.trim());

        // 4. 检查两份备份文件是否存在并且可读。
        if (!FileValidationUtil.isReadableFile(redisBackupPath)
                || !FileValidationUtil.isReadableFile(
                sentinelBackupPath)) {
            return OperationResultDTO.failure(
                    OperationErrorCode.PRECONDITION_FAILED,
                    "Redis或Sentinel备份配置文件不存在或不可读",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 5. 检查目标配置文件所在目录是否可写。
        if (!FileValidationUtil.isWritableTarget(redisConfigPath)
                || !FileValidationUtil.isWritableTarget(
                sentinelConfigPath)) {
            return OperationResultDTO.failure(
                    OperationErrorCode.CONFIG_INVALID,
                    "Redis或Sentinel目标配置目录不可写",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        try {
            // 6. 校验本地备份与平台保存的SHA256是否一致。
            if (!redisConfigSha256.equalsIgnoreCase(
                    FileChecksumUtil.calculateSha256(redisBackupPath))
                    || !sentinelConfigSha256.equalsIgnoreCase(
                    FileChecksumUtil.calculateSha256(
                            sentinelBackupPath))) {
                return OperationResultDTO.failure(
                        OperationErrorCode.CHECKSUM_MISMATCH,
                        "Redis或Sentinel备份配置文件SHA256不一致",
                        startTime,
                        System.currentTimeMillis()
                );
            }

            // 7. 备份校验通过后，覆盖两份正式配置文件。
            Files.copy(
                    redisBackupPath,
                    redisConfigPath,
                    StandardCopyOption.REPLACE_EXISTING
            );
            Files.copy(
                    sentinelBackupPath,
                    sentinelConfigPath,
                    StandardCopyOption.REPLACE_EXISTING
            );

            return OperationResultDTO.success(
                    "Redis和Sentinel配置文件已恢复",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (IOException e) {
            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "配置文件恢复失败",
                    startTime,
                    System.currentTimeMillis()
            );
        }
    }

    /**
     * 获取并校验平台下发的配置文件SHA256。
     */
    private String getConfigSha256(
            Map<String, String> params,
            String paramName) {

        if (params == null) {
            return null;
        }

        String value = params.get(paramName);
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String sha256 = value.trim();
        if (!ValidationConstants.SHA256_PATTERN
                .matcher(sha256)
                .matches()) {
            return null;
        }
        return sha256;
    }

}
