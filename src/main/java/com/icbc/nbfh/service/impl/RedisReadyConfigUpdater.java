package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 更新执行器外置配置中的redisReady字段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisReadyConfigUpdater {

    private final AgentProperties agentProperties;

    /**
     * 新服务器完成拓扑验收后，将redisReady持久化为true。
     */
    public void markReadyIfNecessary() {

        // 已经可以采集Redis数据时，不需要重复修改配置文件。
        if (agentProperties.isRedisReady()) {
            return;
        }

        Path configPath = Paths.get(agentProperties.getConfigFile())
                .toAbsolutePath()
                .normalize();
        Path parent = configPath.getParent();

        if (parent == null || !Files.isRegularFile(configPath)) {
            throw new IllegalStateException(
                    "执行器外置配置文件不存在：" + configPath
            );
        }

        Path temporaryFile = null;

        try {
            // 1. 读取配置文件并将redisReady修改为true。
            List<String> originalLines = Files.readAllLines(
                    configPath,
                    StandardCharsets.UTF_8
            );
            List<String> updatedLines = new ArrayList<>(originalLines);
            int matchedLineCount = 0;

            for (int index = 0; index < originalLines.size(); index++) {
                String line = originalLines.get(index);
                String trimmedLine = line.trim();

                if (!trimmedLine.startsWith("redisReady:")) {
                    continue;
                }

                int indentationLength = line.indexOf('r');
                String indentation = indentationLength <= 0
                        ? "" : line.substring(0, indentationLength);

                updatedLines.set(index, indentation + "redisReady: true");
                matchedLineCount++;
            }

            if (matchedLineCount != 1) {
                throw new IllegalStateException(
                        "执行器配置中必须且只能存在一个redisReady字段"
                );
            }

            // 2. 先写入临时文件，再替换原配置文件。
            temporaryFile = Files.createTempFile(
                    parent,
                    ".application-",
                    ".yml"
            );
            Files.write(
                    temporaryFile,
                    updatedLines,
                    StandardCharsets.UTF_8
            );

            try {
                Files.move(
                        temporaryFile,
                        configPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(
                        temporaryFile,
                        configPath,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            // 3. 同步更新当前进程中的配置值。
            agentProperties.setRedisReady(true);

        } catch (IOException e) {
            throw new IllegalStateException(
                    "更新执行器redisReady配置失败：" + e.getMessage(),
                    e
            );

        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException e) {
                    log.warn("清理redisReady临时配置文件失败：{}",
                            temporaryFile);
                }
            }
        }
    }
}
