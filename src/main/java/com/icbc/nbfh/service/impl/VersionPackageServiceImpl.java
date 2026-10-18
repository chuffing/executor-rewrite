package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.service.VersionPackageService;
import com.icbc.nbfh.common.Enum.ParameterErrorCode;
import com.icbc.nbfh.common.constant.ValidationConstants;
import com.icbc.nbfh.common.constant.VersionPackageConstants;
import com.icbc.nbfh.common.exception.ParameterException;
import com.icbc.nbfh.config.AgentProperties;
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

/** 安装包接收、摘要校验及暂存清理服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionPackageServiceImpl implements VersionPackageService {

    private final AgentProperties agentProperties;

    /**
     * 流式保存安装包，摘要一致后返回正式暂存路径。
     */
    @Override
    public Path saveAndValidate(String taskNo, String expectedSha256,
                                MultipartFile file) throws IOException {

        // ======== 1. 读取安装包目录 ========

        // 获取安装包存放目录。
        String packageDir = agentProperties.getOperation().getPackageDir();
        if (packageDir == null || packageDir.trim().isEmpty()) {
            throw new IllegalStateException("Redis安装包目录未配置");
        }

        // 将字符串形式的目录转换为Path对象。
        Path packageDirectory = Paths.get(packageDir.trim()).toRealPath();

        // ======== 2. 创建staging暂存目录 ========

        // 在安装包根目录下面拼接staging子目录。
        Path stagingDirectory = packageDirectory.resolve(
                VersionPackageConstants.STAGING_DIRECTORY_NAME);

        // 如果staging目录不存在，就创建。
        Files.createDirectories(stagingDirectory);

        // ======== 3. 创建独立上传目录 ========

        // 在staging下面创建本次上传专用的临时目录。
        Path requestDirectory = Files.createTempDirectory(
                stagingDirectory, taskNo + "-");
        // 拼接正在上传时使用的临时文件目录。
        Path temporaryPath = requestDirectory.resolve(
                VersionPackageConstants.TEMP_PACKAGE_FILE_NAME);
        // 拼接校验通过后使用的正式暂存文件路径。
        Path packagePath = requestDirectory.resolve(
                VersionPackageConstants.STAGED_PACKAGE_FILE_NAME);

        // 记录本次上传流程是否成功。
        boolean completed = false;

        try {
            // 2. 流式写入，按实际收到的字节数限制50 MiB。
            long receivedSize = 0;
            try (InputStream input = file.getInputStream();
                 OutputStream output = Files.newOutputStream(temporaryPath,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    receivedSize += length;
                    if (receivedSize > ValidationConstants.MAX_UPGRADE_PACKAGE_SIZE_BYTES) {
                        throw new ParameterException(
                                ParameterErrorCode.PACKAGE_FILE_INVALID,
                                "Redis安装包不能超过50 MiB"
                        );
                    }
                    output.write(buffer, 0, length);
                }
            }

            // ======== 5. 检查文件大小 ========

            // 如果实际没有读取到任何字节，说明收到的是空文件。
            if (receivedSize == 0) {
                throw new ParameterException(
                        ParameterErrorCode.PACKAGE_FILE_INVALID,
                        "Redis安装包不能为空"
                );
            }

            // 比较实际读取字节数与MultipartFile报告的大小。
            if (receivedSize != file.getSize()) {
                throw new ParameterException(
                        ParameterErrorCode.PACKAGE_FILE_INVALID,
                        "Redis安装包传输不完整"
                );
            }

            // ======== 6. 校验SHA256 ========
            String actualSha256 = FileChecksumUtil.calculateSha256(temporaryPath);
            if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                throw new ParameterException(
                        ParameterErrorCode.PACKAGE_CHECKSUM_MISMATCH,
                        "Redis安装包SHA256与平台提供的摘要不一致"
                );
            }

            // ======== 7. 生成正式暂存文件 ========

            // 将文件从临时名称移动到正式暂存名称。
            Files.move(temporaryPath, packagePath);

            // 标记本次上传成功。
            completed = true;

            // 返回校验完成的安装包路径。
            return packagePath;
        } finally {

            // ======== 8. 失败清理 ========
            if (!completed) {
                // 同时清理两种文件名，覆盖异常发生在移动期间的情况。
                deleteStagedPackage(packagePath);
            }
        }
    }

    /**
     * 清理本次上传目录内的临时文件、正式暂存文件和空目录。
     */
    @Override
    public void deleteStagedPackage(Path packagePath) {

        Path requestDirectory = packagePath.toAbsolutePath().normalize().getParent();
        try {
            // 清理只允许发生在配置的暂存目录下，不接受任意服务器路径。
            Path stagingDirectory = Paths.get(
                    agentProperties.getOperation().getPackageDir().trim())
                    .toRealPath().resolve(
                            VersionPackageConstants.STAGING_DIRECTORY_NAME);
            if (requestDirectory == null) {
                throw new IOException("缺少安装包暂存目录");
            }
            if (!stagingDirectory.equals(requestDirectory.getParent())) {
                throw new IOException("安装包清理路径不在暂存目录内");
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Redis安装包清理路径检查失败，path={}", packagePath, e);
            return;
        }

        // 分别尝试删除，单个文件删除失败不影响另一个文件的清理。
        deleteIfExists(requestDirectory.resolve(
                VersionPackageConstants.TEMP_PACKAGE_FILE_NAME));
        deleteIfExists(requestDirectory.resolve(
                VersionPackageConstants.STAGED_PACKAGE_FILE_NAME));
        deleteIfExists(requestDirectory);
    }

    /**
     * 尝试删除指定文件或空目录，失败时记录日志。
     */
    private void deleteIfExists(Path path) {

        try {
            Files.deleteIfExists(path);
        } catch (IOException | SecurityException e) {
            log.warn("Redis安装包暂存文件清理失败，path={}", path, e);
        }
    }
}
