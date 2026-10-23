package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.OperationResultDTO;
import java.util.Map;

/** Redis源码编译及版本部署服务。 */
public interface DeployVersionService {

    /** 校验暂存源码包摘要，编译并部署指定版本，不切换当前版本。 */
    OperationResultDTO deployVersion(Map<String, String> params);
}
