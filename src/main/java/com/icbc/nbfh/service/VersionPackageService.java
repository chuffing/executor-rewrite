package com.icbc.nbfh.service;

import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Path;

/** Redis源码包接收及暂存清理服务。 */
public interface VersionPackageService {

    /**
     * 流式保存源码包，摘要一致后返回正式暂存路径。
     */
    Path saveAndValidate(String taskNo, String expectedSha256,
                         MultipartFile file) throws IOException;

    /**
     * 清理本次上传目录内的临时文件、正式暂存文件和空目录。
     */
    void deleteStagedPackage(Path packagePath);
}
