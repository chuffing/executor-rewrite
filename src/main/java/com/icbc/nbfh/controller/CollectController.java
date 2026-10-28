package com.icbc.nbfh.controller;

import com.icbc.nbfh.common.ResultBean;
import com.icbc.nbfh.entity.DTO.MetricReportDTO;
import com.icbc.nbfh.service.CollectService;
import lombok.RequiredArgsConstructor;
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
@RestController
@RequestMapping("/executor")
@RequiredArgsConstructor
public class CollectController {

    private final CollectService collectService;

    @PostMapping("/collect")
    public ResultBean<List<MetricReportDTO>> collect() {
        MetricReportDTO metric = collectService.collectForManualCheck();
        return ResultBean.success(Collections.singletonList(metric));
    }
}
