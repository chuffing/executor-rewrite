package com.icbc.nbfh.collector;

import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.SentinelNodeDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SentinelInfoCollector 真实环境测试（直连 192.168.230.11:26379）
 *
 * 运行前确认：
 *   1. 本机可访问 192.168.230.11:26379（telnet/redis-cli 验证）
 *   2. 若 Sentinel 配了 requirepass，填写 SENTINEL_PASSWORD
 *   3. master name 与服务器一致，默认 mymaster
 */
class SentinelInfoCollectorIT {

    private static final String SENTINEL_HOST = "192.168.230.11";
    private static final int SENTINEL_PORT = 26379;
    private static final String MASTER_NAME = "mymaster1";
    private static final String SENTINEL_PASSWORD = null; // 有密码时填写
    private static final int TIMEOUT_MS = 3000;

    // ---------------------------------------------------------------
    // 正常采集，直连真实服务器打印结果
    // ---------------------------------------------------------------
    @Test
    @DisplayName("直连 192.168.230.11 采集 Sentinel 节点并打印")
    void collectFromRealSentinel() {
        SentinelInfoCollector collector = new SentinelInfoCollector(buildProperties(SENTINEL_PORT));

        List<SentinelNodeDTO> nodes = collector.collect();
        printTable(nodes);

        // 断言
        assertFalse(nodes.isEmpty(), "应至少采集到一个有效 Sentinel 节点");
        for (SentinelNodeDTO n : nodes) {
            assertNotNull(n.getNodeIp(), "nodeIp 不应为空");
            assertNotEquals("localhost", n.getNodeIp(), "不应出现 localhost");
            assertFalse(n.getNodeIp().startsWith("127."),
                    "不应出现 loopback 地址: " + n.getNodeIp());
            assertNotNull(n.getNodePort(), "nodePort 不应为空");
        }
    }

    // ---------------------------------------------------------------
    // 错误端口 → 采集为空 → 抛 IllegalStateException
    // ---------------------------------------------------------------
    @Test
    @DisplayName("错误端口应抛出 IllegalStateException（未采集到任何有效节点）")
    void collectWithWrongPortShouldThrow() {
        SentinelInfoCollector collector = new SentinelInfoCollector(buildProperties(26380));

        IllegalStateException ex = assertThrows(IllegalStateException.class, collector::collect);
        System.out.println("[预期异常] " + ex.getMessage());
        assertTrue(ex.getMessage().contains("未采集到任何有效的 Sentinel 节点"));
    }

    // ---------------------------------------------------------------
    // main直接看结果
    // ---------------------------------------------------------------
    public static void main(String[] args) {
        SentinelInfoCollector collector =
                new SentinelInfoCollector(buildProperties(SENTINEL_PORT));
        try {
            printTable(collector.collect());
        } catch (Exception e) {
            System.err.println("采集失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ---------------------------------------------------------------
    // 辅助：构造 AgentProperties
    // ---------------------------------------------------------------
    private static AgentProperties buildProperties(int port) {
        AgentProperties.SentinelConfig sentinel = new AgentProperties.SentinelConfig();
        sentinel.setEnabled(true);
        sentinel.setHost(SENTINEL_HOST);
        sentinel.setPort(port);
        sentinel.setServiceName(MASTER_NAME);
        sentinel.setTimeout(TIMEOUT_MS);
        if (SENTINEL_PASSWORD != null) {
            sentinel.setPassword(SENTINEL_PASSWORD);
        }

        AgentProperties props = new AgentProperties();
        // 模拟 agent 部署在该机：让 resolveServerIp 优先返回该 IP，便于验证自身节点
        props.setHostIp(SENTINEL_HOST);
        props.setSentinel(sentinel);
        return props;
    }

    private static void printTable(List<SentinelNodeDTO> nodes) {
        System.out.println("==================== Sentinel 采集结果 ====================");
        System.out.printf("%-2s %-38s %-18s %-6s %s%n", "#", "nodeName", "nodeIp", "port", "DTO");
        int i = 0;
        for (SentinelNodeDTO n : nodes) {
            System.out.printf("%-2d %-38s %-18s %-6s %s%n",
                    ++i, safe(n.getNodeName()), n.getNodeIp(), n.getNodePort(), n);
        }
        System.out.println("total = " + nodes.size());
        System.out.println("============================================================");
    }

    private static String safe(String s) {
        return s == null ? "-" : s;
    }
}