package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.common.constant.ValidationConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.service.DeployConfigService;
import com.icbc.nbfh.service.ConfigFileService;
import com.icbc.nbfh.util.FileChecksumUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * 将平台上传的Redis和Sentinel配置文件部署到正式目录。
 * 只负责配置覆盖，不负责服务启停、备份或回滚。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeployConfigServiceImpl implements DeployConfigService {

    private static final String TEMP_FILE_PREFIX = ".config-deploy-";

    private static final String TEMP_FILE_SUFFIX = ".tmp";

    private final AgentProperties agentProperties;

    private final ConfigFileService configFileService;

    /**
     * 使用平台上传并已暂存的配置文件覆盖正式配置。
     */
    @Override
    public OperationResultDTO deployConfig(Map<String, String> params) {

        long startTime = System.currentTimeMillis();

        Path requestDirectory = null;
        Path redisTemporary = null;
        Path sentinelTemporary = null;

        try {

            // ======== 1. 获取配置文件路径 ========
            // 暂存路径由执行器在saveAndValidate()成功后生成。
            Path redisStaged = Paths.get(
                    params.get(TaskParamConstants.REDIS_CONFIG_LOCAL_PATH));

            Path sentinelStaged = Paths.get(
                    params.get(TaskParamConstants.SENTINEL_CONFIG_LOCAL_PATH));

            // 两份文件位于同一个任务暂存目录。
            // 直接获取父目录，供任务结束后清理使用。
            requestDirectory = redisStaged.getParent();

            // 正式配置路径来自本地application.yml。
            Path redisTarget = Paths.get(
                    agentProperties.getRedis().getConfigFile());

            Path sentinelTarget = Paths.get(
                    agentProperties.getSentinel().getConfigFile());

            // 获取平台传入的SHA256。
            String redisSha256 = params.get(
                    TaskParamConstants.REDIS_CONFIG_SHA256);

            String sentinelSha256 = params.get(
                    TaskParamConstants.SENTINEL_CONFIG_SHA256);


            // ======== 2. 创建正式发布前的临时副本 ========
            // 在Redis正式配置所在目录创建临时文件。
            redisTemporary = createTemporaryConfig(redisTarget);

            // 原样复制Redis配置。
            copyStagedFile(redisStaged, redisTemporary);

            // 在Sentinel正式配置所在目录创建临时文件。
            sentinelTemporary = createTemporaryConfig(sentinelTarget);

            // 原样复制Sentinel配置。
            copyStagedFile(sentinelStaged, sentinelTemporary);

            // ======== 3. 校验两份临时文件的SHA256 ========
            if (!redisSha256.equalsIgnoreCase(
                    FileChecksumUtil.calculateSha256(redisTemporary))) {

                return OperationResultDTO.failure(
                        OperationErrorCode.CHECKSUM_MISMATCH,
                        "Redis配置文件SHA256不一致",
                        startTime,
                        System.currentTimeMillis());
            }

            if (!sentinelSha256.equalsIgnoreCase(
                    FileChecksumUtil.calculateSha256(sentinelTemporary))) {

                return OperationResultDTO.failure(
                        OperationErrorCode.CHECKSUM_MISMATCH,
                        "Sentinel配置文件SHA256不一致",
                        startTime,
                        System.currentTimeMillis());
            }


            // ======== 4. 覆盖正式配置文件 ========
            // 使用原子移动替换Redis正式配置。
            // 不比较旧配置内容，不备份。
            Files.move(
                    redisTemporary,
                    redisTarget,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);

            // 移动成功后，临时文件路径已不存在。
            redisTemporary = null;

            // 使用原子移动替换Sentinel正式配置。
            Files.move(
                    sentinelTemporary,
                    sentinelTarget,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);

            sentinelTemporary = null;


            // ======== 5. 返回部署成功 ========
            return OperationResultDTO.success(
                    "Redis和Sentinel配置文件已覆盖部署",
                    startTime,
                    System.currentTimeMillis());

        } catch (AtomicMoveNotSupportedException e) {

            log.error("配置文件部署失败：文件系统不支持原子替换", e);

            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "文件系统不支持配置文件原子替换",
                    startTime,
                    System.currentTimeMillis());

        } catch (IOException | UnsupportedOperationException e) {

            // 不记录配置文件正文，避免泄露其中的密码。
            log.error("配置文件部署失败", e);

            return OperationResultDTO.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "配置文件部署失败，可能已有部分配置更新，请检查后重新部署",
                    startTime,
                    System.currentTimeMillis());

        } finally {

            // ======== 6. 清理本次任务的临时文件 ========
            deleteTemporaryConfig(redisTemporary);
            deleteTemporaryConfig(sentinelTemporary);

            configFileService.deleteStagedFiles(requestDirectory);
        }
    }

    /**
     * 将暂存配置文件原样复制到正式目录的临时文件。
     */
    private void copyStagedFile(Path staged, Path temporary) throws IOException {

        try (InputStream input = Files.newInputStream(staged);
             OutputStream output = Files.newOutputStream(
                     temporary,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {

            byte[] buffer = new byte[8192];
            long copied = 0;
            int length;
            while ((length = input.read(buffer)) != -1) {
                copied += length;
                // 限制实际复制的数据量，最大1 MiB。
                if (copied > ValidationConstants.MAX_CONFIG_FILE_SIZE_BYTES) {
                    throw new IOException("暂存配置文件超过1 MiB");
                }
                output.write(buffer, 0, length);
            }
        }
    }

    /**
     * 在正式配置所在目录创建临时文件。
     * Linux下设置文件权限为600，避免配置中的密码被其他用户读取。
     */
    private Path createTemporaryConfig(Path target) throws IOException {

        Path parent = target.getParent();
        if (Files.getFileStore(parent).supportsFileAttributeView("posix")) {
            return Files.createTempFile(
                    parent,
                    TEMP_FILE_PREFIX,
                    TEMP_FILE_SUFFIX,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
        }

        return Files.createTempFile(
                parent,
                TEMP_FILE_PREFIX,
                TEMP_FILE_SUFFIX);
    }

    /**
     * 删除正式发布时创建的临时文件。
     * 已成功移动到正式路径的文件不会被删除。
     */
    private void deleteTemporaryConfig(Path temporary) {

        if (temporary == null) {
            return;
        }

        try {
            Files.deleteIfExists(temporary);

        } catch (IOException | SecurityException e) {
            log.warn("配置部署临时文件清理失败，path={}", temporary, e);
        }
    }
}