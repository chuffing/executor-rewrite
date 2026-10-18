package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.common.Enum.ParameterErrorCode;
import com.icbc.nbfh.common.constant.ConfigFileConstants;
import com.icbc.nbfh.common.constant.ValidationConstants;
import com.icbc.nbfh.common.exception.ParameterException;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.service.ConfigFileService;
import com.icbc.nbfh.util.FileChecksumUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 接收并暂存平台上传的Redis和Sentinel配置文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigFileServiceImpl implements ConfigFileService {

    private final AgentProperties agentProperties;

    /**
     * 保存并校验平台上传的两份配置文件。
     * 两份文件均保存成功且SHA256一致后，返回本次任务的暂存目录。
     */
    @Override
    public Path saveAndValidate(
            String taskNo,
            String redisSha256,
            String sentinelSha256,
            MultipartFile redisFile,
            MultipartFile sentinelFile) throws IOException {

        // 直接读取application.yml中的暂存根目录。
        Path staging = Paths.get(
                agentProperties.getOperation().getConfigFileDir(),
                ConfigFileConstants.STAGING_DIRECTORY_NAME);

        // 如果staging不存在，就创建。
        Files.createDirectories(staging);

        // 创建一个空的文件属性数组。
        FileAttribute<?>[] attributes = new FileAttribute<?>[0];
        if (Files.getFileStore(staging).supportsFileAttributeView("posix")) {
            attributes = new FileAttribute<?>[]{
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------"))
            };
        }

        // 创建独立暂存目录。
        Path requestDirectory = Files.createTempDirectory(
                staging,
                taskNo + ConfigFileConstants.DIRECTORY_MARKER,
                attributes);

        boolean completed = false;

        try {
            // 保存Redis配置文件。
            saveFile(
                    requestDirectory.resolve(ConfigFileConstants.REDIS_FILE_NAME),
                    redisFile,
                    redisSha256,
                    "Redis");

            // 保存Sentinel配置文件。
            saveFile(
                    requestDirectory.resolve(ConfigFileConstants.SENTINEL_FILE_NAME),
                    sentinelFile,
                    sentinelSha256,
                    "Sentinel");

            completed = true;

            return requestDirectory;

        } finally {
            // 任意一步失败，都清理本次上传产生的文件。
            if (!completed) {
                deleteStagedFiles(requestDirectory);
            }
        }
    }

    /**
     * 将平台上传的配置文件原样写入暂存目录，并校验文件大小和SHA256。
     */
    private void saveFile(
            Path target,
            MultipartFile file,
            String expectedSha256,
            String name) throws IOException {

        // ======== 1. 创建配置文件 ========
        FileAttribute<?>[] attributes = new FileAttribute<?>[0];

        // Linux下设置文件权限为600。
        if (Files.getFileStore(target.getParent()).supportsFileAttributeView("posix")) {
            attributes = new FileAttribute<?>[]{
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------"))
            };
        }

        Files.createFile(target, attributes);

        // ======== 2. 保存文件原始字节 ========
        long received = 0;

        try (InputStream input = file.getInputStream();
             OutputStream output = Files.newOutputStream(
                     target,
                     StandardOpenOption.WRITE)) {

            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                received += length;
                // 限制实际接收的数据量，最大1 MiB。
                if (received > ValidationConstants.MAX_CONFIG_FILE_SIZE_BYTES) {
                    throw new ParameterException(
                            ParameterErrorCode.CONFIG_FILE_INVALID,
                            name + "配置文件超过大小限制");
                }
                output.write(buffer, 0, length);
            }
        }

        // ======== 3. 检查文件大小 ========
        if (received == 0 || received != file.getSize()) {
            throw new ParameterException(
                    ParameterErrorCode.CONFIG_FILE_INVALID,
                    name + "配置文件为空或传输不完整");
        }

        // ======== 4. 校验SHA256 ========
        if (!expectedSha256.equalsIgnoreCase(
                FileChecksumUtil.calculateSha256(target))) {

            throw new ParameterException(
                    ParameterErrorCode.CONFIG_CHECKSUM_MISMATCH,
                    name + "配置文件SHA256与平台提供的摘要不一致");
        }
    }

    /**
     * 清理本次任务的暂存文件和目录。
     */
    @Override
    public void deleteStagedFiles(Path requestDirectory) {

        if (requestDirectory == null) {
            return;
        }
        // ======== 1. 限制清理范围 ========
        // 暂存根目录来自application.yml。
        Path staging = Paths.get(
                agentProperties.getOperation().getConfigFileDir(),
                ConfigFileConstants.STAGING_DIRECTORY_NAME);

        // 只允许清理staging下的直接子目录。
        if (!staging.equals(requestDirectory.getParent())) {
            log.warn("配置暂存清理目录不在允许范围内，path={}", requestDirectory);
            return;
        }

        // ======== 2. 删除两份暂存配置文件 ========
        deleteIfExists(
                requestDirectory.resolve(ConfigFileConstants.REDIS_FILE_NAME));

        deleteIfExists(
                requestDirectory.resolve(ConfigFileConstants.SENTINEL_FILE_NAME));

        // ======== 3. 删除本次任务目录 ========
        deleteIfExists(requestDirectory);
    }

    /**
     * 删除指定文件或空目录。
     * 清理失败时记录日志，不影响其他文件清理。
     */
    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);

        } catch (IOException | SecurityException e) {
            log.warn("配置暂存文件清理失败，path={}", path, e);
        }
    }
}
