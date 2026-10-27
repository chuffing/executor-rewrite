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
 * 使用本地假 Sentinel 验证节点发现、flags 离线判定与不可达兜底
 */
class SentinelInfoCollectorTest {

    private ServerSocket server;

    private Thread serverThread;

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
    void shouldDiscoverNodesAndMarkDownFlagsOffline() throws Exception {
        int port = startServer();
        AgentProperties properties = properties(port);
        properties.getSentinel().setNodes(java.util.Collections.singletonList(
                "127.0.0.1:" + port));

        List<SentinelNodeDTO> nodes = new SentinelInfoCollector(properties).collect();

        assertEquals(2, nodes.size());
        Map<String, SentinelNodeDTO> byIp = new java.util.LinkedHashMap<>();
        for (SentinelNodeDTO node : nodes) {
            byIp.put(node.getNodeIp(), node);
        }
        assertTrue(byIp.containsKey("10.10.10.31"));
        assertTrue(byIp.containsKey("10.10.10.32"));
        assertEquals("1", byIp.get("10.10.10.31").getSentinelStatus());
        assertEquals("0", byIp.get("10.10.10.32").getSentinelStatus());
        assertEquals("mymaster", byIp.get("10.10.10.31").getMasterName());
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

        assertEquals(1, nodes.size());
        assertEquals("0", nodes.get(0).getSentinelStatus());
        assertEquals(String.valueOf(port), nodes.get(0).getNodePort());
    }

    private AgentProperties properties(int port) {
        AgentProperties properties = new AgentProperties();
        properties.getSentinel().setHost(CommonConstant.DEFAULT_LOCALHOST_IP);
        properties.getSentinel().setPort(port);
        properties.getSentinel().setServiceName("mymaster");
        properties.getSentinel().setTimeout(1000);
        return properties;
    }

    /** 启动一次性的假 Sentinel，返回监听端口 */
    private int startServer() throws Exception {
        server = new ServerSocket(0, 8, InetAddress.getByName(CommonConstant.DEFAULT_LOCALHOST_IP));
        int port = server.getLocalPort();
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
        return port;
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
                out.write(sentinelsReply().getBytes(StandardCharsets.UTF_8));
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
        java.util.List<String> args = new java.util.ArrayList<>();
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
            // 读取 CRLF
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

    private String sentinelsReply() {
        StringBuilder reply = new StringBuilder();
        reply.append("*2\r\n");
        appendNode(reply, "10.10.10.31", "sentinel");
        appendNode(reply, "10.10.10.32", "s_down,sentinel");
        return reply.toString();
    }

    private void appendNode(StringBuilder reply, String ip, String flags) {
        String[][] fields = {
                {"name", "sentinel-" + ip + ":26379"},
                {"ip", ip},
                {"port", "26379"},
                {"flags", flags},
                {"master-name", "mymaster"}
        };
        reply.append("*").append(fields.length * 2).append("\r\n");
        for (String[] field : fields) {
            reply.append("$").append(field[0].length()).append("\r\n")
                    .append(field[0]).append("\r\n");
            reply.append("$").append(field[1].length()).append("\r\n")
                    .append(field[1]).append("\r\n");
        }
    }
}
