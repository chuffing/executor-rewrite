package com.icbc.nbfh.entity.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 执行器上报的单个 Sentinel 节点，对应平台端 SentinelNodeReportDTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SentinelNodeDTO {

    /** 哨兵节点名，格式 sentinel-ip:port */
    private String nodeName;

    /** 哨兵节点 IP */
    private String nodeIp;

    /** 哨兵节点端口 */
    private String nodePort;

    /** 哨兵状态：0=离线 / 1=在线 */
    private String sentinelStatus;

    /** 该哨兵监听的 master 名称 */
    private String masterName;
}
