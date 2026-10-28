package com.icbc.nbfh.collector;

import com.icbc.nbfh.common.constant.CommonConstant;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.SentinelNodeDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用本地假 Sentinel 验证：
 * 1. 自身节点必须返回；
 * 2. 其他 Sentinel 节点合并返回；
 * 3. flags 离线判定；
 * 4. 单哨兵时自身在线；
 * 5. 不可达时配置节点离线兜底
 */
class SentinelInfoCollectorTest {

    /** Sentinel 对外 IP */
    private static final String SENTINEL_IP = "172.20.10.3";
    private static final String MYMASTER = "senD";

    /** Sentinel 对外端口 */
    private static final int SENTINEL_PORT = 26379;

    private ServerSocket server;

    private Thread serverThread;

    /** 0=返回其他两个节点；1=返回空；2=返回一个“类似自身”的地址和一个其他节点 */
    private int replyMode;

    @AfterEach
    void close() throws Exception {
        if (server != null) {
            server.close();
        }
        if (serverThread != null) {
            serverThread.join(2000);
        }
    }

    @Test
    void shouldIncludeSelfAndOtherNodes() throws Exception {
        replyMode = 0;
        int port = startServer(SENTINEL_PORT);
        AgentProperties properties = properties(port);
        properties.getSentinel().setNodes(java.util.Collections.singletonList(
                "127.0.0.1:" + port));

        List<SentinelNodeDTO> nodes = new SentinelInfoCollector(properties).collect();
        printNodes("shouldIncludeSelfAndOtherNodes", nodes);

        assertEquals(3, nodes.size());
        Map<String, SentinelNodeDTO> byIp = new java.util.LinkedHashMap<>();
        for (SentinelNodeDTO node : nodes) {
            byIp.put(node.getNodeIp(), node);
        }
        assertTrue(byIp.containsKey(SENTINEL_IP));
        assertTrue(byIp.containsKey("10.10.10.31"));
        assertTrue(byIp.containsKey("10.10.10.32"));
        assertEquals("1", byIp.get(SENTINEL_IP).getSentinelStatus());
        assertEquals("1", byIp.get("10.10.10.31").getSentinelStatus());
        assertEquals("0", byIp.get("10.10.10.32").getSentinelStatus());
        assertEquals(MYMASTER, byIp.get(SENTINEL_IP).getMasterName());
        // 自身地址 = 当前服务器 IP + 该 Sentinel 配置端口
        assertEquals(String.valueOf(SENTINEL_PORT), byIp.get(SENTINEL_IP).getNodePort());
    }

    @Test
    void shouldMarkSingleSentinelOnlineWhenNoOtherSentinel() throws Exception {
        replyMode = 1;
        int port = startServer(SENTINEL_PORT);
        AgentProperties properties = properties(port);

        List<SentinelNodeDTO> nodes = new SentinelInfoCollector(properties).collect();
        printNodes("shouldMarkSingleSentinelOnlineWhenNoOtherSentinel", nodes);

        assertEquals(1, nodes.size());
        assertEquals(SENTINEL_IP, nodes.get(0).getNodeIp());
        assertEquals("1", nodes.get(0).getSentinelStatus());
        assertEquals(String.valueOf(SENTINEL_PORT), nodes.get(0).getNodePort());
    }

    @Test
    void shouldAlwaysUseServerIpAndConfiguredPortForSelf() throws Exception {
        replyMode = 2;
        int port = startServer(SENTINEL_PORT);
        AgentProperties properties = properties(port);

        List<SentinelNodeDTO> nodes = new SentinelInfoCollector(properties).collect();
        printNodes("shouldAlwaysUseServerIpAndConfiguredPortForSelf", nodes);

        assertEquals(3, nodes.size());
        Map<String, SentinelNodeDTO> byIp = new java.util.LinkedHashMap<>();
        for (SentinelNodeDTO node : nodes) {
            byIp.put(node.getNodeIp(), node);
        }
        // 自身仍使用“服务器 IP + 配置端口”，不采用返回列表中的其他地址
        assertTrue(byIp.containsKey(SENTINEL_IP));
        assertEquals(String.valueOf(SENTINEL_PORT), byIp.get(SENTINEL_IP).getNodePort());
        assertTrue(byIp.containsKey("10.10.10.40"));
        assertTrue(byIp.containsKey("10.10.10.31"));
    }

    @Test
    void shouldReportConfiguredNodeOfflineWhenSentinelUnreachable() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1,
                InetAddress.getByName(CommonConstant.DEFAULT_LOCALHOST_IP))) {
            port = probe.getLocalPort();
        }

        AgentProperties properties = properties(port);
        List<SentinelNodeDTO> nodes = new SentinelInfoCollector(properties).collect();
        printNodes("shouldReportConfiguredNodeOfflineWhenSentinelUnreachable", nodes);

        assertEquals(1, nodes.size());
        assertEquals("0", nodes.get(0).getSentinelStatus());
        assertEquals(String.valueOf(port), nodes.get(0).getNodePort());
    }

    private AgentProperties properties(int port) {
        AgentProperties properties = new AgentProperties();
        properties.setHostIp(SENTINEL_IP);
        properties.getSentinel().setHost(CommonConstant.DEFAULT_LOCALHOST_IP);
        properties.getSentinel().setPort(port);
        properties.getSentinel().setServiceName(MYMASTER);
        properties.getSentinel().setTimeout(1000);
        return properties;
    }

    /** 启动一次性的假 Sentinel，返回监听端口 */
    private int startServer(int port) throws Exception {
        server = new ServerSocket(port, 8, InetAddress.getByName(CommonConstant.DEFAULT_LOCALHOST_IP));
        int localPort = server.getLocalPort();
        serverThread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    handle(socket);
                } catch (Exception ignored) {
                    // server 关闭后退出
                }
            }
        }, "fake-sentinel");
        serverThread.setDaemon(true);
        serverThread.start();
        return localPort;
    }

    private void handle(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        while (!socket.isClosed()) {
            List<String> command = readCommand(in);
            if (command == null || command.isEmpty()) {
                return;
            }
            String name = command.get(0).toUpperCase();
            if ("PING".equals(name)) {
                out.write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
            } else if ("AUTH".equals(name) || "SELECT".equals(name)) {
                out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
            } else if ("SENTINEL".equals(name)) {
                if (command.size() > 1 && "myid".equalsIgnoreCase(command.get(1))) {
                    out.write(bulk("self-runid").getBytes(StandardCharsets.UTF_8));
                } else {
                    out.write(sentinelsReply().getBytes(StandardCharsets.UTF_8));
                }
            } else {
                out.write("-ERR unknown\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
        }
    }

    private List<String> readCommand(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || !line.startsWith("*")) {
            return null;
        }
        int count = Integer.parseInt(line.substring(1));
        List<String> args = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            String lengthLine = readLine(in);
            if (lengthLine == null) {
                return null;
            }
            int length = Integer.parseInt(lengthLine.substring(1));
            byte[] data = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = in.read(data, offset, length - offset);
                if (read < 0) {
                    return null;
                }
                offset += read;
            }
            in.read();
            in.read();
            args.add(new String(data, StandardCharsets.UTF_8));
        }
        return args;
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder();
        int previous = -1;
        int current;
        while ((current = in.read()) >= 0) {
            if (previous == '\r' && current == '\n') {
                builder.setLength(builder.length() - 1);
                return builder.toString();
            }
            builder.append((char) current);
            previous = current;
        }
        return null;
    }

    private String bulk(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return "$" + bytes.length + "\r\n" + text + "\r\n";
    }

    private String sentinelsReply() {
        if (replyMode == 1) {
            return "*0\r\n";
        }

        StringBuilder reply = new StringBuilder();
        if (replyMode == 2) {
            reply.append("*2\r\n");
            appendNode(reply, "10.10.10.40", "sentinel", "self-runid");
            appendNode(reply, "10.10.10.31", "sentinel", "other-runid-31");
        } else {
            reply.append("*2\r\n");
            appendNode(reply, "10.10.10.31", "sentinel", "other-runid-31");
            appendNode(reply, "10.10.10.32", "s_down,sentinel", "other-runid-32");
        }
        return reply.toString();
    }

    private void appendNode(StringBuilder reply, String ip, String flags, String runid) {
        String[][] fields = {
                {"name", "sentinel-" + ip + ":26379"},
                {"ip", ip},
                {"port", "26379"},
                {"runid", runid},
                {"flags", flags},
                {"master-name", MYMASTER}
        };
        reply.append("*").append(fields.length * 2).append("\r\n");
        for (String[] field : fields) {
            reply.append("$").append(field[0].length()).append("\r\n")
                    .append(field[0]).append("\r\n");
            reply.append("$").append(field[1].length()).append("\r\n")
                    .append(field[1]).append("\r\n");
        }
    }


    private void printNodes(String scene, List<SentinelNodeDTO> nodes) {
        System.out.println("==== " + scene + " ====");
        System.out.println("size=" + nodes.size());
        for (SentinelNodeDTO node : nodes) {
            System.out.println(String.format(
                    "  nodeIp=%-15s nodePort=%-6s sentinelStatus=%s masterName=%s nodeName=%s",
                    node.getNodeIp(),
                    node.getNodePort(),
                    node.getSentinelStatus(),
                    node.getMasterName(),
                    node.getNodeName()));
        }
    }
}