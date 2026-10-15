package com.icbc.nbfh.controller;

import com.icbc.nbfh.common.Enum.OperationType;
import com.icbc.nbfh.common.constant.TaskValidationConstants;
import com.icbc.nbfh.entity.DTO.PlatformTaskDTO;
import com.icbc.nbfh.service.AgentTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 接收平台下发的运维任务。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentTaskController {

    private final AgentTaskService agentTaskService;

    @PostMapping("/task")
    public ResponseEntity<Void> receiveTask(
            @RequestBody PlatformTaskDTO request) {

        // 1. 校验任务编号。
        if(request.getTaskNo() == null || !TaskValidationConstants.TASK_NO_PATTERN
                .matcher(request.getTaskNo()).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "任务编号格式不正确"
            );
        }

        // 2. 校验步骤编号。
        if(request.getStepNo() == null || request.getStepNo() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "步骤编号必须大于0"
            );
        }

        // 3. 校验任务类型
        String taskType = request.getTaskType();

        if(taskType == null || taskType.trim().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "任务类型不能为空"
            );
        }
        try {
            OperationType.valueOf(taskType);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "不支持的任务类型"
            );
        }

        // 4. 提交任务
        agentTaskService.submitTask(request);

        // 5. 返回HTTP 202
        return new ResponseEntity<Void>(HttpStatus.ACCEPTED);
    }
}

