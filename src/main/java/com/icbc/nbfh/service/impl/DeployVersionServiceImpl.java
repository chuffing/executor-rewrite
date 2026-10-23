package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.command.FixedScriptExecutor;
import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.Enum.ScriptType;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.CommandResultDTO;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.service.DeployVersionService;
import com.icbc.nbfh.service.VersionPackageService;
import com.icbc.nbfh.util.FileChecksumUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/** 编译Redis源码并部署新版本，不改变正在运行的Redis。 */
@Service
@RequiredArgsConstructor
public class DeployVersionServiceImpl implements DeployVersionService {

    private final FixedScriptExecutor fixedScriptExecutor;

    private final AgentProperties agentProperties;

    private final VersionPackageService versionPackageService;

    /**
     * 部署前核对SHA256，再调用固定脚本编译源码并安装新版本。
     */
    @Override
    public OperationResultDTO deployVersion(Map<String, String> params) {

        // 记录当前任务开始执行的时间。
        long startTime = System.currentTimeMillis();

            // 声明源码包路径。
        Path packagePath = null;

        try {

            // 从入参中读取源码包本地路径。
            packagePath = Paths.get(params.get(TaskParamConstants.PACKAGE_LOCAL_PATH))
                    .toAbsolutePath()
                    .normalize();

            // ======== 1. 读取参数与配置 ========

            // 获取准备部署的Redis版本。
            String targetVersion = params.get(TaskParamConstants.TARGET_VERSION);

            // 获取平台提供的SHA256。后续与执行器本地源码包的SHA256比较。
            String expectedSha256 = params.get(TaskParamConstants.PACKAGE_SHA256);

            // 获取Redis各版本的正式安装目录。
            String installDir = agentProperties.getOperation().getInstallDir();

            // 检查正式安装目录是否配置。
            if (!StringUtils.hasText(installDir)) {
                return OperationResultDTO.failure(OperationErrorCode.CONFIG_INVALID,
                        "Redis版本安装目录未配置", startTime, System.currentTimeMillis());
            }

            Path installDirectory = Paths.get(installDir.trim()).toAbsolutePath().normalize();

            // ======== 2. 检查源码包文件 ========
            if (!Files.isRegularFile(packagePath)) {
                return OperationResultDTO.failure(OperationErrorCode.PRECONDITION_FAILED,
                        "Redis源码包不存在或不是普通文件", startTime, System.currentTimeMillis());
            }

            // ======== 3. 校验SHA256 ========

            // 计算执行器本地源码包的SHA256。
            String actualSha256 = FileChecksumUtil.calculateSha256(packagePath);
            // 将本地实际摘要与平台提供的摘要比较。
            if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                return OperationResultDTO.failure(OperationErrorCode.CHECKSUM_MISMATCH,
                        "Redis源码包摘要不一致", startTime, System.currentTimeMillis());
            }

            // ======== 4. 调用Shell部署脚本 ========
            // 三个业务参数依次为：源码包完整路径、目标版本号、正式安装目录。
            CommandResultDTO commandResult = fixedScriptExecutor.executeScript(
                    ScriptType.DEPLOY_VERSION,
                    agentProperties.getOperation().getVersionDeployTimeoutSeconds(),
                    packagePath.toString(),
                    targetVersion,
                    installDirectory.toString());

            // ======== 5. 检查脚本执行结果 ========

            // 检查Shell是否执行超时。
            if (commandResult.isTimedOut()) {
                return OperationResultDTO.failure(OperationErrorCode.COMMAND_TIMEOUT,
                        "Redis源码编译及版本部署超时", startTime, System.currentTimeMillis());
            }
            // 检查Shell退出码是否为0。
            if (commandResult.getExitCode() != 0) {
                return OperationResultDTO.failure(OperationErrorCode.COMMAND_EXECUTION_FAILED,
                        "Redis源码编译及版本部署失败", startTime, System.currentTimeMillis());
            }
            return OperationResultDTO.success("Redis源码编译及版本部署完成",
                    startTime, System.currentTimeMillis());

        } catch (FileNotFoundException e) {
            return OperationResultDTO.failure(OperationErrorCode.COMMAND_NOT_FOUND,
                    "Redis版本部署脚本不存在", startTime, System.currentTimeMillis());

        } catch (InvalidPathException e) {
            return OperationResultDTO.failure(OperationErrorCode.CONFIG_INVALID,
                    "Redis部署相关路径格式不正确", startTime, System.currentTimeMillis());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperationResultDTO.failure(OperationErrorCode.UNKNOWN_ERROR,
                    "Redis版本部署被中断", startTime, System.currentTimeMillis());

        } catch (IOException e) {
            return OperationResultDTO.failure(OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Redis目录检查、源码包读取、编译或部署失败", startTime, System.currentTimeMillis());

        } finally {
            // 清理只交给包服务，路径检查和删除规则只有一份。
            if (packagePath != null) {
                versionPackageService.deleteStagedPackage(packagePath);
            }
        }
    }
}
