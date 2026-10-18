package com.icbc.nbfh.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

/** 配置文件接收及任务暂存管理。 */
public interface ConfigFileService {

    /** 原样保存两份文件，摘要全部通过后返回任务独立目录。 */
    Path saveAndValidate(String taskNo, String redisSha256, String sentinelSha256,
                         MultipartFile redisFile, MultipartFile sentinelFile) throws IOException;

    /** 只清理当前配置任务的固定文件及空目录，失败时记录日志。 */
    void deleteStagedFiles(Path requestDirectory);
}
