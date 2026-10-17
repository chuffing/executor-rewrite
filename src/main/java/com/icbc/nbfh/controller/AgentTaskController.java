package com.icbc.nbfh.controller;

import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.Enum.ParameterErrorCode;
import com.icbc.nbfh.common.Enum.TaskSubmitStatus;
import com.icbc.nbfh.common.constant.TaskParamConstants;
import com.icbc.nbfh.common.constant.ValidationConstants;
import com.icbc.nbfh.common.constant.VersionPackageConstants;
import com.icbc.nbfh.common.exception.ParameterException;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.entity.VO.TaskSubmitResultVO;
import com.icbc.nbfh.service.AgentTaskService;
import com.icbc.nbfh.service.VersionPackageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;

/**
 * 接收平台下发的运维任务。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentTaskController {

    private final AgentTaskService agentTaskService;

    private final VersionPackageService versionPackageService;

    /**
     * 接收不携带文件的普通运维任务。
     *
     * @param request 平台任务报文
     * @return 任务接收结果
     */
    @PostMapping("/task")
    public ResponseEntity<TaskSubmitResultVO> receiveTask(
            @RequestBody PlatformTaskDTO request) {

        // 1. 校验任务参数。
        OperationType operationType = validateTask(request);

        // 2. 上传版本部署任务不能使用该接口。
        if (OperationType.DEPLOY_VERSION == operationType) {
            throw new ParameterException(
                    ParameterErrorCode.TASK_PARAM_INVALID,
                    "版本部署任务必须通过安装包上传接口提交"
            );
        }

        // 3. 提交任务。
        agentTaskService.submitTask(request);

        // 4. 返回任务接收结果。
        TaskSubmitResultVO result = new TaskSubmitResultVO(
                request.getTaskNo(),
                TaskSubmitStatus.ACCEPTED.name(),
                "任务已接收"
        );

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(result);
    }

    /**
     * 接收携带Redis安装包的版本部署任务。
     *
     * @param request 平台任务报文
     * @param file Redis运行包
     * @return 任务接收结果
     */
    @PostMapping(
            value = "/version/deploy",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<TaskSubmitResultVO> receiveVersionDeployTask(
            @RequestPart("task") PlatformTaskDTO request,
            @RequestPart("file") MultipartFile file) throws IOException {

        // 1. 校验任务参数和任务类型。
        OperationType operationType = validateTask(request);

        // 2. 确认任务类型必须是DEPLOY_VERSION
        if (OperationType.DEPLOY_VERSION != operationType) {
            throw new ParameterException(
                    ParameterErrorCode.TASK_TYPE_UNSUPPORTED,
                    "安装包上传接口只接收版本部署任务"
            );
        }

        // 3. 校验上传文件的基础信息。
        validateVersionPackage(file, request.getParams().get(TaskParamConstants.TARGET_VERSION));

        // 4. 创建一个新的hashMap，后面需要增加packageLocalPath。
        Map<String, String> params = new HashMap<>(request.getParams());
        // 将复制后的Map重新设置到任务DTO中。
        request.setParams(params);

        // 5. 调用安装包服务。
        // 这个方法会：
        // 1. 创建独立暂存目录；
        // 2. 保存上传的文件；
        // 3. 校验SHA256；
        // 4. 返回安装包本地路径。
        Path packagePath = versionPackageService.saveAndValidate(
                request.getTaskNo(),
                params.get(TaskParamConstants.PACKAGE_SHA256),
                file
        );

        // 6. 把执行器生成的本地安装包路径加入任务参数。
        params.put(
                TaskParamConstants.PACKAGE_LOCAL_PATH,
                packagePath.toString()
        );

        // 7. 提交部署任务。
        try {

            // 把任务交给后台异步执行。
            agentTaskService.submitTask(request);
        } catch (RuntimeException e) {
            versionPackageService.deleteStagedPackage(packagePath);
            throw e;
        }

        // 8. 创建任务提交成功的响应对象。
        TaskSubmitResultVO result = new TaskSubmitResultVO(
                request.getTaskNo(),
                TaskSubmitStatus.ACCEPTED.name(),
                "任务已接收"
        );

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(result);
    }

    /**
     * 校验平台下发的任务参数。
     *
     * @param request 平台任务报文
     * @return 已校验的操作类型
     */
    private OperationType validateTask(PlatformTaskDTO request) {

        if (request == null) {
            throw new ParameterException(
                    ParameterErrorCode.TASK_PARAM_INVALID,
                    "任务报文不能为空"
            );
        }

        // 1. 校验任务编号。
        if (request.getTaskNo() == null
                || !ValidationConstants.TASK_NO_PATTERN
                .matcher(request.getTaskNo()).matches()) {
            throw new ParameterException(
                    ParameterErrorCode.TASK_NO_INVALID,
                    "taskNo必须符合TASK加12位数字的格式"
            );
        }

        // 2. 校验步骤编号。
        if (request.getStepNo() == null || request.getStepNo() <= 0) {
            throw new ParameterException(
                    ParameterErrorCode.STEP_NO_INVALID,
                    "stepNo必须大于0"
            );
        }

        // 3. 校验任务类型。
        String taskType = request.getOperationType();

        if (taskType == null || taskType.trim().isEmpty()) {
            throw new ParameterException(
                    ParameterErrorCode.TASK_TYPE_EMPTY,
                    "任务类型不能为空"
            );
        }

        OperationType operationType;

        try {
            operationType = OperationType.valueOf(taskType);
        } catch (IllegalArgumentException e) {
            throw new ParameterException(
                    ParameterErrorCode.TASK_TYPE_UNSUPPORTED,
                    "不支持的任务类型：" + taskType
            );
        }

        // 4. 校验不同操作需要的参数。
        switch (operationType) {
            case START_REDIS:
            case STOP_REDIS:
            case START_SENTINEL:
            case STOP_SENTINEL:
                // 以上操作不接收任务参数。
                break;

            case RESTORE_CONFIG:
                validateRestoreConfigParams(request.getParams());
                break;

            case DEPLOY_VERSION:
                validateDeployVersionParams(request.getParams());
                break;

            default:
                break;
        }

        return operationType;
    }

    /**
     * 校验配置恢复操作需要的文件摘要参数。
     *
     * @param params 任务参数
     */
    private void validateRestoreConfigParams(Map<String, String> params) {

        if (params == null) {
            throw new ParameterException(
                    ParameterErrorCode.TASK_PARAM_INVALID,
                    "配置恢复参数不能为空"
            );
        }

        String redisConfigSha256 = params.get(
                TaskParamConstants.REDIS_CONFIG_SHA256
        );

        if (redisConfigSha256 == null
                || !ValidationConstants.SHA256_PATTERN
                .matcher(redisConfigSha256).matches()) {
            throw new ParameterException(
                    ParameterErrorCode.TASK_PARAM_INVALID,
                    "Redis配置文件摘要格式不正确"
            );
        }

        String sentinelConfigSha256 = params.get(
                TaskParamConstants.SENTINEL_CONFIG_SHA256
        );

        if (sentinelConfigSha256 == null
                || !ValidationConstants.SHA256_PATTERN
                .matcher(sentinelConfigSha256).matches()) {
            throw new ParameterException(
                    ParameterErrorCode.TASK_PARAM_INVALID,
                    "Sentinel配置文件摘要格式不正确"
            );
        }
    }

    /**
     * 校验版本部署操作需要的安装包参数。
     *
     * @param params 任务参数
     */
    private void validateDeployVersionParams(Map<String, String> params) {

        if (params == null) {
            throw new ParameterException(
                    ParameterErrorCode.TASK_PARAM_INVALID,
                    "版本部署参数不能为空"
            );
        }

        // 1. 校验平台安装包记录编号。
        String packageId = params.get(TaskParamConstants.PACKAGE_ID);

        if (packageId == null || packageId.trim().isEmpty()) {
            throw new ParameterException(
                    ParameterErrorCode.TASK_PARAM_INVALID,
                    "安装包编号不能为空"
            );
        }

        try {
            if (Long.parseLong(packageId) <= 0) {
                throw new ParameterException(
                        ParameterErrorCode.TASK_PARAM_INVALID,
                        "安装包编号必须大于0"
                );
            }
        } catch (NumberFormatException e) {
            throw new ParameterException(
                    ParameterErrorCode.TASK_PARAM_INVALID,
                    "安装包编号格式不正确"
            );
        }

        // 2. 校验目标版本号。
        String targetVersion = params.get(
                TaskParamConstants.TARGET_VERSION
        );

        if (targetVersion == null
                || !ValidationConstants.VERSION_PATTERN
                .matcher(targetVersion).matches()) {
            throw new ParameterException(
                    ParameterErrorCode.TASK_PARAM_INVALID,
                    "目标版本号格式不正确"
            );
        }

        // 3. 校验平台提供的安装包摘要。
        String packageSha256 = params.get(
                TaskParamConstants.PACKAGE_SHA256
        );

        if (packageSha256 == null
                || !ValidationConstants.SHA256_PATTERN
                .matcher(packageSha256).matches()) {
            throw new ParameterException(
                    ParameterErrorCode.TASK_PARAM_INVALID,
                    "安装包摘要格式不正确"
            );
        }
    }

    /**
     * 校验平台传入的Redis运行包基础信息。
     *
     * @param file Redis运行包
     */
    private void validateVersionPackage(MultipartFile file, String targetVersion) {

        // 1. 检查文件是否为空及大小是否超限。
        if (file == null || file.isEmpty()) {
            throw new ParameterException(
                    ParameterErrorCode.PACKAGE_FILE_INVALID,
                    "Redis安装包不能为空"
            );
        }

        if (file.getSize()
                > ValidationConstants.MAX_UPGRADE_PACKAGE_SIZE_BYTES) {
            throw new ParameterException(
                    ParameterErrorCode.PACKAGE_FILE_INVALID,
                    "Redis安装包不能超过50 MiB"
            );
        }

        // 2. 原始文件名只用于格式判断，不参与本地路径拼接。
        String originalFileName = file.getOriginalFilename();

        if (originalFileName == null
                || originalFileName.trim().isEmpty()) {
            throw new ParameterException(
                    ParameterErrorCode.PACKAGE_FILE_INVALID,
                    "Redis安装包文件名不能为空"
            );
        }

        if (!originalFileName.equals(originalFileName.trim())) {
            throw new ParameterException(
                    ParameterErrorCode.PACKAGE_FILE_INVALID,
                    "Redis安装包文件名不能包含首尾空格"
            );
        }
        if (originalFileName.length()
                > ValidationConstants.MAX_PACKAGE_FILE_NAME_LENGTH) {
            throw new ParameterException(
                    ParameterErrorCode.PACKAGE_FILE_INVALID,
                    "Redis安装包文件名过长"
            );
        }
        if (originalFileName.contains("/") || originalFileName.contains("\\")) {
            throw new ParameterException(
                    ParameterErrorCode.PACKAGE_FILE_INVALID,
                    "Redis安装包文件名不能包含路径"
            );
        }
        if (originalFileName.contains("..")) {
            throw new ParameterException(
                    ParameterErrorCode.PACKAGE_FILE_INVALID,
                    "Redis安装包文件名不能包含父目录标记"
            );
        }
        String expectedFileName = VersionPackageConstants.RUNTIME_PACKAGE_PREFIX
                + targetVersion + VersionPackageConstants.RUNTIME_PACKAGE_SUFFIX;
        if (!expectedFileName.equals(originalFileName)) {
            throw new ParameterException(
                    ParameterErrorCode.PACKAGE_FILE_INVALID,
                    "Redis安装包必须命名为redis-{version}-runtime.tar.gz"
            );
        }
    }
}
