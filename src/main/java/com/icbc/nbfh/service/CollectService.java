package com.icbc.nbfh.service;

import com.icbc.nbfh.entity.DTO.MetricReportDTO;

/**
 * 采集服务
 */
public interface CollectService {

    /**
     * 执行一次采集并上报（Redis 指标 + ping 平台延迟 + Sentinel 节点）
     */
    void collectAndReport();

    /**
     * 供平台手动检测调用：只采集不上报，由平台写库
     * @return
     */
    MetricReportDTO collectForManualCheck();
}
