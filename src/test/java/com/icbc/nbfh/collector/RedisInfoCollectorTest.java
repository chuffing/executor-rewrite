package com.icbc.nbfh.collector;

import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.MetricDataDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用本地假 Redis 验证 INFO 解析、单位换算和注册身份信息采集
 */
class RedisInfoCollectorTest {

    private ServerSocket server;

    private Thread serverThread;

    private RedisInfoCollector collector;

    @BeforeEach
    void prepare() throws Exception {
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        serverThread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    handle(socket);
                } catch (Exception ignored) {
                    // server 关闭后退出
                }
            }
        }, "fake-redis");
        serverThread.setDaemon(true);
        serverThread.start();

        AgentProperties properties = new AgentProperties();
        properties.setHostIp("10.0.0.21");
        properties.getRedis().setHost("127.0.0.1");
        properties.getRedis().setPort(server.getLocalPort());
        properties.getRedis().setTimeout(1000);
        collector = new RedisInfoCollector(properties);
    }

    @AfterEach
    void close() throws Exception {
        collector.destroy();
        if (server != null) {
            server.close();
        }
        if (serverThread != null) {
            serverThread.join(2000);
        }
    }

    @Test
    void shouldProbeRedisReady() {
        assertTrue(collector.isRedisReady());
    }

    @Test
    void shouldCollectMasterMetricsWithPlatformUnits() {
        MetricDataDTO metric = collector.collect();

        assertEquals("up", metric.getStatus());
        assertEquals("", metric.getErrorMsg());
        assertEquals("MASTER", metric.getRole());
        assertEquals("7.2.4", metric.getRedisVersion());
        assertEquals("0123456789abcdef0123456789abcdef01234567", metric.getRunId());

        // 1073741824 字节 = 1 GB
        assertEquals("1.000", metric.getMemUs());
        // 9000 usec / 1500 calls = 6 usec/call = 0.006 ms
        assertEquals("0.006", metric.getLatency());
        assertEquals("12.5", metric.getIoIik());
        assertEquals("8.0", metric.getIoIok());
        assertEquals("100", metric.getOps());

        // 本机 Redis 回填执行器对外 IP，满足平台 masterHost 必填校验
        assertEquals("10.0.0.21", metric.getMasterHost());
        assertEquals(String.valueOf(server.getLocalPort()), metric.getMasterPort());
        assertEquals("0", metric.getReplDelay());
        assertNotNull(metric.getCpuPer());
        assertNotNull(metric.getMemServerPer());
        assertTrue(Double.parseDouble(metric.getCpuPer()) >= 0);
        assertTrue(Double.parseDouble(metric.getMemServerPer()) <= 100);
        assertNotNull(metric.getCollectTime());
    }

    @Test
    void shouldCollectRegistrationInfo() {
        Map<String, String> info = collector.collectRegistrationInfo();

        assertEquals("127.0.0.1", info.get("host"));
        assertEquals(String.valueOf(server.getLocalPort()), info.get("tcp_port"));
        assertEquals("MASTER", info.get("role"));
        assertEquals("7.2.4", info.get("version"));
        assertEquals("0123456789abcdef0123456789abcdef01234567", info.get("run_id"));
        assertFalse(info.containsKey("master_host"));
    }

    @Test
    void shouldReturnDownWhenRedisUnavailable() throws Exception {
        server.close();
        MetricDataDTO metric = collector.collect();

        assertEquals("down", metric.getStatus());
        assertNotNull(metric.getErrorMsg());
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
            } else if ("AUTH".equals(name) || "SELECT".equals(name)
                    || "CLIENT".equals(name)) {
                out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
            } else if ("INFO".equals(name)) {
                String section = command.size() > 1 ? command.get(1) : "all";
                out.write(bulk(info(section)).getBytes(StandardCharsets.UTF_8));
            } else {
                out.write("-ERR unknown\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
        }
    }

    private String info(String section) {
        if ("server".equalsIgnoreCase(section)) {
            return "# Server\r\n"
                    + "redis_version:7.2.4\r\n"
                    + "redis_mode:standalone\r\n"
                    + "run_id:0123456789abcdef0123456789abcdef01234567\r\n"
                    + "tcp_port:" + server.getLocalPort() + "\r\n";
        }
        if ("replication".equalsIgnoreCase(section)) {
            return "# Replication\r\n"
                    + "role:master\r\n"
                    + "connected_slaves:0\r\n";
        }
        if ("cpu".equalsIgnoreCase(section)) {
            return "# CPU\r\n"
                    + "used_cpu_sys:10.1\r\n"
                    + "used_cpu_user:5.1\r\n";
        }
        return "# Server\r\n"
                + "redis_version:7.2.4\r\n"
                + "redis_mode:standalone\r\n"
                + "run_id:0123456789abcdef0123456789abcdef01234567\r\n"
                + "tcp_port:" + server.getLocalPort() + "\r\n"
                + "\r\n# Replication\r\n"
                + "role:master\r\n"
                + "connected_slaves:0\r\n"
                + "\r\n# Memory\r\n"
                + "used_memory:1073741824\r\n"
                + "used_memory_rss:1073741824\r\n"
                + "total_system_memory:8589934592\r\n"
                + "\r\n# Stats\r\n"
                + "instantaneous_input_kbps:12.5\r\n"
                + "instantaneous_output_kbps:8.0\r\n"
                + "instantaneous_ops_per_sec:100\r\n"
                + "\r\n# CPU\r\n"
                + "used_cpu_sys:10.0\r\n"
                + "used_cpu_user:5.0\r\n"
                + "\r\n# Commandstats\r\n"
                + "cmdstat_get:calls=1000,usec=5000,usec_per_call=5.00\r\n"
                + "cmdstat_set:calls=500,usec=4000,usec_per_call=8.00\r\n";
    }

    private String bulk(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return "$" + bytes.length + "\r\n" + text + "\r\n";
    }

    private List<String> readCommand(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || !line.startsWith("*")) {
            return null;
        }
        int count = Integer.parseInt(line.substring(1));
        List<String> args = new ArrayList<>();
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
}
