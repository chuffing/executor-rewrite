package com.icbc.nbfh.controller;

import com.icbc.nbfh.common.ResultBean;
import com.icbc.nbfh.common.exception.BusinessCode;
import com.icbc.nbfh.common.exception.BusinessException;
import com.icbc.nbfh.entity.DTO.MetricReportDTO;
import com.icbc.nbfh.service.CollectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/**
 * 供平台端手动检测调用的即时采集接口
 *
 * 平台端调用 POST http://hostIp:executorPort/executor/collect，
 * 返回的数据由平台写库并做告警判定，执行器端不再重复上报
 */
@Slf4j
@RestController
@RequestMapping("/executor")
@RequiredArgsConstructor
public class CollectController {

    private final CollectService collectService;

    @PostMapping("/collect")
    public ResultBean<List<MetricReportDTO>> collect() {
        try {
            MetricReportDTO metric = collectService.collectForManualCheck();
            return ResultBean.success(Collections.singletonList(metric));
        } catch (BusinessException e) {
            int code = e.getCode() == null
                    ? BusinessCode.OPERATION_ERROR.getCode() : e.getCode();
            String message = e.getMsg() == null ? "Redis 采集失败" : e.getMsg();
            log.warn("平台手动采集失败: {}", message);
            return new ResultBean<>(code, message, null);
        } catch (Exception e) {
            String message = e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? "Redis 采集失败" : e.getMessage();
            log.warn("平台手动采集失败: {}", message);
            return new ResultBean<>(BusinessCode.SYSTEM_ERROR.getCode(), message, null);
        }
    }
}
