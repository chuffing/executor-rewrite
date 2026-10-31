package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.command.FixedScriptExecutor;
import com.icbc.nbfh.common.exception.BusinessCode;
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
     * 检查本机是否具备Redis部署所需的基础条件。
     * 检查本机目录、磁盘、端口及脚本。
     */
    @Override
    public OperationResultDTO precheckHost() {

        long startTime = System.currentTimeMillis();

        try {
            // 获取执行器的运维配置对象。
            AgentProperties.OperationConfig operation = agentProperties.getOperation();

            // 检查安装包暂存目录。true表示要求目录可写。
            Path packages = FileValidationUtil.checkDirectory(
                    operation.getPackageDir(), "安装包暂存目录", true);

            // 检查Redis版本安装目录。
            Path installed = FileValidationUtil.checkDirectory(
                    operation.getInstallDir(), "版本安装目录", true);

            // 检查固定Shell脚本目录。
            Path scripts = FileValidationUtil.checkDirectory(
                    operation.getScriptDir(), "脚本目录", false);

            // 检查Redis数据目录。
            FileValidationUtil.checkDirectory(
                    agentProperties.getRedis().getDataDir(), "Redis数据目录", true);

            // 检查Redis配置文件。新服务器允许配置文件尚未创建，但父目录必须满足要求。
            checkConfigTarget(agentProperties.getRedis().getConfigFile(), "Redis配置文件");

            // 对Sentinel配置文件进行相同检查。
            checkConfigTarget(agentProperties.getSentinel().getConfigFile(), "Sentinel配置文件");

            // 检查两份故障恢复备份路径。
            checkConfigTarget(agentProperties.getRedis().getBackupFile(), "Redis备份配置文件");
            checkConfigTarget(agentProperties.getSentinel().getBackupFile(), "Sentinel备份配置文件");

            // 检查正式配置与备份配置是否位于不同目录。
            checkSeparateBackupDirectory(
                    agentProperties.getRedis().getConfigFile(),
                    agentProperties.getRedis().getBackupFile(),
                    "Redis"
            );
            checkSeparateBackupDirectory(
                    agentProperties.getSentinel().getConfigFile(),
                    agentProperties.getSentinel().getBackupFile(),
                    "Sentinel"
            );

            // 检查current软链接是否合法。
            checkCurrentLink(operation.getCurrentDir(), installed);

            // 获取版本部署开始前要求的最低可用空间。
            long minimumFreeSpace = operation.getMinFreeSpaceBytes();

            // 获取版本安装目录所在文件系统的可用空间。
            long availableSpace = FileValidationUtil.getUsableSpace(installed);

            log.info("版本部署磁盘检查，directory={}，availableBytes={}，minimumBytes={}",
                    installed, availableSpace, minimumFreeSpace);

            // 这里只检查部署前最低空间，不保证后续磁盘空间不会发生变化。
            if (availableSpace < minimumFreeSpace) {
                return OperationResultDTO.failure(
                        BusinessCode.OPERATION_ERROR,
                        "版本安装目录所在文件系统可用空间不足",
                        startTime, System.currentTimeMillis());
            }

            // 检查Redis配置的本机端口。
            // 端口空闲可以继续部署；如果已经运行的是本机Redis，也允许重跑任务。
            if (RedisCheckUtil.isLocalPortListening(agentProperties.getRedis().getHost(),
                    agentProperties.getRedis().getPort(), agentProperties.getRedis().getTimeout())) {

                if (!RedisCheckUtil.isRedisServer(
                        agentProperties.getRedis().getHost(),
                        agentProperties.getRedis().getPort(),
                        agentProperties.getRedis().getPassword(),
                        agentProperties.getRedis().getTimeout())) {

                    return OperationResultDTO.failure(
                            BusinessCode.OPERATION_ERROR,
                            "Redis目标端口已被其他服务占用",
                            startTime, System.currentTimeMillis());
                }
            }

            // 检查Sentinel配置的本机端口，已经运行正确Sentinel时允许重跑任务。
            if (RedisCheckUtil.isLocalPortListening(agentProperties.getSentinel().getHost(),
                    agentProperties.getSentinel().getPort(), agentProperties.getSentinel().getTimeout())) {

                if (!RedisCheckUtil.isSentinelServer(
                        agentProperties.getSentinel().getHost(),
                        agentProperties.getSentinel().getPort(),
                        agentProperties.getSentinel().getPassword(),
                        agentProperties.getSentinel().getTimeout())) {
                    return OperationResultDTO.failure(
                            BusinessCode.OPERATION_ERROR,
                            "Sentinel目标端口已被其他服务占用",
                            startTime,
                            System.currentTimeMillis());
                }
            }

            // 遍历预检查要求存在的所有Shell脚本。
            for (ScriptType type : REQUIRED_SCRIPTS) {

                // 将脚本目录与脚本文件名组合，得到完整脚本路径。
                Path script = scripts.resolve(type.getFileName());

                // 检查脚本是否为普通文件，以及当前进程是否具有读取权限。
                if (!Files.isRegularFile(script) || !Files.isReadable(script)) {

                    // 如果脚本不存在、不是普通文件或不可读，返回失败。
                    return OperationResultDTO.failure(
                            BusinessCode.OPERATION_ERROR,
                            "固定脚本不存在或不可读：" + type.getFileName(),
                            startTime, System.currentTimeMillis());
                }
            }

            // 调用固定的主机环境预检查脚本。
            CommandResultDTO commandResult = fixedScriptExecutor.executeScript(
                    ScriptType.PRECHECK_HOST, operation.getScriptTimeoutSeconds());
            if (commandResult.isTimedOut()) {
                return OperationResultDTO.failure(BusinessCode.OPERATION_ERROR,
                        "主机依赖检查脚本执行超时", startTime, System.currentTimeMillis());
            }
            if (commandResult.getExitCode() != 0) {
                log.error("主机依赖检查失败，stdout={}，stderr={}",
                        commandResult.getStdout(), commandResult.getStderr());
                return OperationResultDTO.failure(BusinessCode.OPERATION_ERROR,
                        "主机运行依赖检查未通过，请查看执行器日志", startTime, System.currentTimeMillis());
            }


            return OperationResultDTO.success(
                    "主机部署环境预检查通过",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (IllegalArgumentException e) {
            return OperationResultDTO.failure(BusinessCode.OPERATION_ERROR,
                    e.getMessage(), startTime, System.currentTimeMillis());


        } catch (FileNotFoundException e) {
            return OperationResultDTO.failure(BusinessCode.OPERATION_ERROR,
                    "主机预检查脚本不存在", startTime, System.currentTimeMillis());


        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperationResultDTO.failure(BusinessCode.SYSTEM_ERROR,
                    "主机预检查被中断", startTime, System.currentTimeMillis());


        } catch (IOException e) {
            log.error("主机环境检查失败", e);
            return OperationResultDTO.failure(BusinessCode.OPERATION_ERROR,
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
     * 检查正式配置与备份配置是否位于不同目录。
     */
    private void checkSeparateBackupDirectory(
            String configFile,
            String backupFile,
            String name) {

        Path configDirectory = Paths.get(configFile)
                .toAbsolutePath()
                .normalize()
                .getParent();

        Path backupDirectory = Paths.get(backupFile)
                .toAbsolutePath()
                .normalize()
                .getParent();

        if (configDirectory.equals(backupDirectory)) {
            throw new IllegalArgumentException(
                    name + "正式配置与备份配置不能位于同一目录"
            );
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

        Path current = Paths.get(value);

        if (!current.isAbsolute()) {
            throw new IllegalArgumentException("current软链接路径必须使用绝对路径");
        }
        current = current.normalize();

        // 软链接必须具有父目录和文件名（根目录无法创建软链接。
        if (current.getParent() == null || current.getFileName() == null) {
            throw new IllegalArgumentException("current软链接路径不合法");
        }

        // 检查current所在的父目录是否存在且可读、可写、可访问。
        FileValidationUtil.checkDirectory(current.getParent().toString(), "current父目录", true);

        // 检查current软链接是否存在。（新服务器允许还没有创建current，因此不存在时直接结束检查。
        // LinkOption.NOFOLLOW_LINKS：如果遇到软链接，不要顺着软链接去检查它指向的目标，只检查软链接本身。
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // 如果current只是个普通文件夹也可以通过上一步的检查。

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
