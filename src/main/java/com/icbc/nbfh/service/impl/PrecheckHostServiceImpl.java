package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.common.exception.BusinessCode;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import com.icbc.nbfh.service.PrecheckHostService;
import com.icbc.nbfh.util.FileValidationUtil;
import com.icbc.nbfh.util.RedisCheckUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrecheckHostServiceImpl implements PrecheckHostService {

    private final AgentProperties agentProperties;

    /**
     * 检查本机是否具备Redis部署所需的基础条件。
     * 检查本机动态目录状态、磁盘和端口。
     */
    @Override
    public OperationResultDTO precheckHost() {

        long startTime = System.currentTimeMillis();

        try {
            AgentProperties.OperationConfig operation = agentProperties.getOperation();
            Path installed = Paths.get(operation.getInstallDir());
            // 检查Redis数据目录。
            FileValidationUtil.checkDirectory(agentProperties.getRedis().getDataDir(), "Redis数据目录", true);
            // 检查current软链接是否合法。
            checkCurrentLink(operation.getCurrentDir(), installed);
            // 获取版本部署开始前要求的最低可用空间。
            long minimumFreeSpace = operation.getMinFreeSpaceBytes();
            // 获取版本安装目录所在文件系统的可用空间。
            long availableSpace = FileValidationUtil.getUsableSpace(installed);
            log.info("版本部署磁盘检查，directory={}，availableBytes={}，minimumBytes={}",
                    installed, availableSpace, minimumFreeSpace);
            if (availableSpace < minimumFreeSpace) {
                return OperationResultDTO.failure(
                        BusinessCode.OPERATION_ERROR,
                        "版本安装目录所在文件系统可用空间不足",
                        startTime, System.currentTimeMillis());
            }

            // 检查Redis配置的本机端口。
            // 端口空闲可以继续部署；如果已经运行的是本机Redis，也允许重跑任务。
            if (RedisCheckUtil.isLocalPortListening(
                    agentProperties.getRedis().getHost(),
                    agentProperties.getRedis().getPort(),
                    agentProperties.getRedis().getTimeout())) {

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
            if (RedisCheckUtil.isLocalPortListening(
                    agentProperties.getSentinel().getHost(),
                    agentProperties.getSentinel().getPort(),
                    agentProperties.getSentinel().getTimeout())) {

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

            return OperationResultDTO.success(
                    "主机部署环境预检查通过",
                    startTime,
                    System.currentTimeMillis()
            );

        } catch (IllegalArgumentException e) {
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    e.getMessage(),
                    startTime,
                    System.currentTimeMillis());

        } catch (IOException e) {
            log.error("主机环境检查失败", e);
            return OperationResultDTO.failure(
                    BusinessCode.OPERATION_ERROR,
                    "主机环境检查失败：" + e.getMessage(),
                    startTime,
                    System.currentTimeMillis());
        }
    }

    /**
     * 检查Redis当前版本软链接是否合法。
     * 新服务器允许current尚未创建。
     * 如果current已经存在，则必须是软链接，
     * 且指向正式版本安装目录下的某个版本目录。
     */
    private void checkCurrentLink(String value, Path installed) throws IOException {

        Path current = Paths.get(value);

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
