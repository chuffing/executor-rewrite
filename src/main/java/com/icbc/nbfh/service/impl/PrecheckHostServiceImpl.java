package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.command.FixedScriptExecutor;
import com.icbc.nbfh.common.Enum.OperationErrorCode;
import com.icbc.nbfh.common.Enum.ScriptType;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.CommandResultDTO;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.service.PrecheckHostService;
import com.icbc.nbfh.util.FileValidationUtil;
import com.icbc.nbfh.util.RedisCheckUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrecheckHostServiceImpl implements PrecheckHostService {

    // 预检查要求必须存在的Shell脚本。
    private static final ScriptType[] REQUIRED_SCRIPTS = {
            ScriptType.PRECHECK_HOST,
            ScriptType.STOP_REDIS,
            ScriptType.START_REDIS,
            ScriptType.STOP_SENTINEL,
            ScriptType.START_SENTINEL,
            ScriptType.DEPLOY_VERSION,
            ScriptType.SWITCH_VERSION
    };

    private final FixedScriptExecutor fixedScriptExecutor;

    private final AgentProperties agentProperties;

    /**
     * 只读检查本机目录、磁盘、端口及脚本。
     */
    @Override
    public OperationResultDTO precheckHost() {

        // 记录开始时间。
        long startTime = System.currentTimeMillis();

        try {
            // 获取执行器的运维配置对象。
            AgentProperties.OperationConfig operation = agentProperties.getOperation();

            // 检查安装包暂存目录。
            // true表示要求目录可写，因为后续需要保存平台发送的Redis安装包。
            Path packages = FileValidationUtil.checkDirectory(
                    operation.getPackageDir(), "安装包暂存目录", true);

            // 检查Redis版本安装目录。
            // true表示要求目录可写。
            Path installed = FileValidationUtil.checkDirectory(
                    operation.getInstallDir(), "版本安装目录", true);

            // 检查固定Shell脚本目录。
            // false表示不要求目录可写，只需要能够读取和访问。
            Path scripts = FileValidationUtil.checkDirectory(
                    operation.getScriptDir(), "脚本目录", false);

            // 检查Redis数据目录。Redis需要能够在该目录中写入数据文件。
            FileValidationUtil.checkDirectory(
                    agentProperties.getRedis().getDataDir(), "Redis数据目录", true);

            // 检查Redis配置文件。
            // 新服务器允许配置文件尚未创建，但父目录必须满足要求
            checkConfigTarget(agentProperties.getRedis().getConfigFile(), "Redis配置文件");

            // 对Sentinel配置文件进行相同检查。
            checkConfigTarget(agentProperties.getSentinel().getConfigFile(), "Sentinel配置文件");


            // 检查current软链接是否合法。
            // 新服务器允许尚未创建current。
            // 如果current已经存在，则要求它指向正式版本安装目录中的有效版本目录。
            checkCurrentLink(operation.getCurrentDir(), installed);

            // 从配置读取最低磁盘可用空间要求。
            long minimum = operation.getMinFreeSpaceBytes();
            // 最低可用空间必须大于0，否则说明配置不合理。
            if (minimum <= 0) {
                throw new IllegalArgumentException("磁盘最低可用空间必须大于0");
            }

            // 获取安装包暂存目录所在文件系统的可用空间。
            long packageAvailable = FileValidationUtil.getUsableSpace(packages);

            log.info("主机磁盘检查，directory={}，availableBytes={}，minimumBytes={}",
                    packages, packageAvailable, minimum);

            // 如果实际空间小于最低要求，则返回失败。
            if (packageAvailable < minimum) {
                return OperationResultDTO.failure(
                        OperationErrorCode.RESOURCE_INSUFFICIENT,
                        "安装包暂存目录所在磁盘可用空间不足",
                        startTime,
                        System.currentTimeMillis());
            }

            // 获取正式版本安装目录所在文件系统的可用空间。
            long installAvailable = FileValidationUtil.getUsableSpace(installed);

            log.info("主机磁盘检查，directory={}，availableBytes={}，minimumBytes={}",
                    installed, installAvailable, minimum);

            // 如果实际空间小于最低要求，则返回失败。
            if (installAvailable < minimum) {
                return OperationResultDTO.failure(
                        OperationErrorCode.RESOURCE_INSUFFICIENT,
                        "版本安装目录所在磁盘可用空间不足",
                        startTime, System.currentTimeMillis());
            }

            // 检查Redis配置的本机端口是否已有服务监听。
            if (RedisCheckUtil.isLocalPortListening(agentProperties.getRedis().getHost(),
                    agentProperties.getRedis().getPort(), agentProperties.getRedis().getTimeout())) {

                // 如果端口已有服务监听，说明新机环境不满足部署要求。
                return OperationResultDTO.failure(
                        OperationErrorCode.PORT_OCCUPIED,
                        "Redis目标端口已有服务监听",
                        startTime, System.currentTimeMillis());
            }

            // 检查Sentinel配置的本机端口是否已有服务监听。
            if (RedisCheckUtil.isLocalPortListening(agentProperties.getSentinel().getHost(),
                    agentProperties.getSentinel().getPort(), agentProperties.getSentinel().getTimeout())) {

                // 如果端口已有服务监听，说明新机环境不满足部署要求。
                return OperationResultDTO.failure(
                        OperationErrorCode.PORT_OCCUPIED,
                        "Sentinel目标端口已有服务监听",
                        startTime,
                        System.currentTimeMillis());
            }

            // 遍历预检查要求存在的所有Shell脚本。
            for (ScriptType type : REQUIRED_SCRIPTS) {

                // 将脚本目录与脚本文件名组合，得到完整脚本路径。
                Path script = scripts.resolve(type.getFileName());

                // 检查脚本是否为普通文件，以及当前进程是否具有读取权限。
                if (!Files.isRegularFile(script) || !Files.isReadable(script)) {

                    // 如果脚本不存在、不是普通文件或不可读，返回失败。
                    return OperationResultDTO.failure(
                            OperationErrorCode.COMMAND_NOT_FOUND,
                            "固定脚本不存在或不可读：" + type.getFileName(),
                            startTime, System.currentTimeMillis());
                }
            }

            // 调用固定的主机环境预检查脚本。
            CommandResultDTO commandResult = fixedScriptExecutor.executeScript(
                    ScriptType.PRECHECK_HOST, operation.getScriptTimeoutSeconds());
            if (commandResult.isTimedOut()) {
                return OperationResultDTO.failure(OperationErrorCode.COMMAND_TIMEOUT,
                        "主机依赖检查脚本执行超时", startTime, System.currentTimeMillis());
            }
            if (commandResult.getExitCode() != 0) {
                log.error("主机依赖检查失败，stdout={}，stderr={}",
                        commandResult.getStdout(), commandResult.getStderr());
                return OperationResultDTO.failure(OperationErrorCode.PRECONDITION_FAILED,
                        "主机运行依赖检查未通过，请查看执行器日志", startTime, System.currentTimeMillis());
            }
            // 7. 返回预检查结果。
            return OperationResultDTO.success("主机部署环境预检查通过", startTime, System.currentTimeMillis());

        } catch (IllegalArgumentException e) {
            return OperationResultDTO.failure(OperationErrorCode.CONFIG_INVALID,
                    e.getMessage(), startTime, System.currentTimeMillis());

        } catch (FileNotFoundException e) {
            return OperationResultDTO.failure(OperationErrorCode.COMMAND_NOT_FOUND,
                    "主机预检查脚本不存在", startTime, System.currentTimeMillis());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperationResultDTO.failure(OperationErrorCode.UNKNOWN_ERROR,
                    "主机预检查被中断", startTime, System.currentTimeMillis());

        } catch (IOException e) {
            log.error("主机环境检查失败", e);
            return OperationResultDTO.failure(OperationErrorCode.PRECONDITION_FAILED,
                    "主机环境检查失败：" + e.getMessage(), startTime, System.currentTimeMillis());
        }
    }

    /**
     * 检查Redis或Sentinel配置文件的路径及访问权限。
     * 新服务器允许配置文件尚未创建，但要求父目录存在且可写。
     * 如果配置文件已经存在，则要求它是可读写的普通文件。
     */
    private void checkConfigTarget(String value, String name) throws IOException {

        // 检查配置文件路径是否已配置。
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + "未配置");
        }

        // 将配置文件路径从String转换为Path对象。
        Path path = Paths.get(value);

        // 检查是否为绝对路径。
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(name + "必须使用绝对路径");
        }
        // 规范化路径。
        path = path.normalize();

        // 检查路径是否已包含父目录和文件名。
        if (path.getParent() == null || path.getFileName() == null) {
            throw new IllegalArgumentException(name + "路径不合法");
        }

        // 检查配置文件的父目录是否存在且具有读、写、访问权限。
        FileValidationUtil.checkDirectory(path.getParent().toString(), name + "父目录", true);

        if (Files.exists(path)) {

            // 如果文件已存在，则要求它是普通文件，并且可读、可写。
            if (!Files.isRegularFile(path)
                    || !Files.isReadable(path) || !Files.isWritable(path)) {

                throw new IllegalArgumentException(name + "不是可读写的普通文件");
            }
        }
    }

    /**
     * 检查Redis当前版本软链接是否合法。
     * 新服务器允许current尚未创建。
     * 如果current已经存在，则必须是软链接，
     * 且指向正式版本安装目录下的某个版本目录。
     */
    private void checkCurrentLink(String value, Path installed) throws IOException {

        // 检查current软链接路径是否已配置。
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("current软链接路径未配置");
        }

        // 将软链接路径转换为Path对象。
        Path current = Paths.get(value);

        // 要求使用绝对路径。
        if (!current.isAbsolute()) {
            throw new IllegalArgumentException("current软链接路径必须使用绝对路径");
        }
        // 规范化路径。
        current = current.normalize();

        // 检查current路径是否具有父目录和文件名。
        if (current.getParent() == null || current.getFileName() == null) {
            throw new IllegalArgumentException("current软链接路径不合法");
        }

        // 检查current所在的父目录是否存在且可读、可写、可访问。
        FileValidationUtil.checkDirectory(current.getParent().toString(), "current父目录", true);

        // 检查current软链接是否存在。
        // 新服务器允许还没有创建current，因此不存在时直接结束检查。
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        // 如果current已经存在，要求它必须是符号链接。
        if (!Files.isSymbolicLink(current)) {
            throw new IllegalArgumentException("current已存在但不是软链接");
        }

        // 获取current软链接实际指向的目录。
        Path target = current.toRealPath();

        // 检查软链接目标是否为目录，
        // 并且该目录的直接父目录必须是正式版本安装目录。
        if (!Files.isDirectory(target) || !installed.equals(target.getParent())) {
            throw new IllegalArgumentException("current未指向正式安装目录内的版本目录");
        }
    }
}
