package com.icbc.nbfh.util;

import org.springframework.util.StringUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;

/**
 * Redis和Sentinel运行状态检查工具。
 */
public final class RedisCheckUtil {

    private static final int PORT_CHECK_TIMEOUT_MILLIS = 500;

    /**
     * 检查Redis或Sentinel是否能够正常响应PING。
     */
    public static boolean isPingSuccessful(String host,
                                           int port,
                                           String password,
                                           int timeout) {

        try (Jedis jedis = createAuthenticatedJedis(
                host,
                port,
                password,
                timeout
        )) {
            return "PONG"
                    .equalsIgnoreCase(jedis.ping());

        } catch (JedisException e) {
            return false;
        }
    }

    /**
     * 检查端口上运行的是否为普通Redis服务。
     */
    public static boolean isRedisServer(String host,
                                        int port,
                                        String password,
                                        int timeout) {

        return hasRedisMode(
                host,
                port,
                password,
                timeout,
                "standalone"
        );
    }

    /**
     * 检查端口上运行的是否为Sentinel服务。
     */
    public static boolean isSentinelServer(String host,
                                           int port,
                                           String password,
                                           int timeout) {

        return hasRedisMode(
                host,
                port,
                password,
                timeout,
                "sentinel"
        );
    }

    /**
     * 检查Sentinel是否能够发现指定的Redis主节点。
     */
    public static boolean isSentinelMasterDiscovered(
            String host,
            int port,
            String password,
            int timeout,
            String serviceName) {

        try (Jedis jedis = createAuthenticatedJedis(
                host,
                port,
                password,
                timeout
        )) {
            if (!"PONG"
                    .equalsIgnoreCase(jedis.ping())) {
                return false;
            }

            List<String> masterAddress =
                    jedis.sentinelGetMasterAddrByName(serviceName);

            // 1. 检查Sentinel是否返回主节点地址。
            if (masterAddress == null || masterAddress.size() != 2) {
                return false;
            }

            // 2. 获取主节点IP和端口。
            String masterHost = masterAddress.get(0);
            String masterPort = masterAddress.get(1);

            // 3. 检查主节点IP是否为空。
            if (masterHost == null || masterHost.trim().isEmpty()) {
                return false;
            }

            // 4. 检查主节点端口是否为空。
            if (masterPort == null || masterPort.trim().isEmpty()) {
                return false;
            }

            // 5. 校验通过。
            return true;

        } catch (JedisException e) {
            return false;
        }
    }

    /**
     * 检查指定TCP端口是否开放。
     */
    public static boolean isPortOpen(String host,
                                     int port) throws IOException {

        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(host, port),
                    PORT_CHECK_TIMEOUT_MILLIS
            );

            return true;

        } catch (ConnectException e) {
            return false;

        } catch (SocketTimeoutException e) {
            return true;
        }
    }

    /**
     * 只探测本机配置地址的TCP端口，拒绝连接才按未监听处理。
     */
    public static boolean isLocalPortListening(String host, int port, int timeout) throws IOException {

        if (!StringUtils.hasText(host) || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Redis或Sentinel连接地址、端口配置不正确");
        }
        if (timeout <= 0) {
            throw new IllegalArgumentException("Redis或Sentinel连接超时必须大于0");
        }

        // 确保Agent只检查自己所在的Linux服务器。
        InetAddress address = InetAddress.getByName(host);
        if (!address.isLoopbackAddress() && NetworkInterface.getByInetAddress(address) == null) {
            throw new IllegalArgumentException("主机预检查只允许探测本机地址");
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeout);
            return true;
        } catch (ConnectException e) {
            return false;
        }
    }

    /**
     * 创建完成身份认证的Jedis客户端。
     */
    private static Jedis createAuthenticatedJedis(String host,
                                                  int port,
                                                  String password,
                                                  int timeout) {

        Jedis jedis = new Jedis(host, port, timeout);

        try {
            // 未配置密码时跳过 AUTH，兼容无密码 Redis/Sentinel。
            if (StringUtils.hasText(password)) {
                jedis.auth(password);
            }
            return jedis;

        } catch (JedisException e) {
            jedis.close();
            throw e;
        }
    }

    /**
     * 读取INFO server中的redis_mode，确认端口上的服务类型。
     */
    private static boolean hasRedisMode(String host,
                                        int port,
                                        String password,
                                        int timeout,
                                        String... expectedModes) {

        try (Jedis jedis = createAuthenticatedJedis(
                host,
                port,
                password,
                timeout
        )) {
            if (!"PONG"
                    .equalsIgnoreCase(jedis.ping())) {
                return false;
            }

            String serverInfo = jedis.info("server");
            if (!StringUtils.hasText(serverInfo)) {
                return false;
            }

            String[] lines = serverInfo.replace("\r", "").split("\n");
            for (String line : lines) {
                if (!line.startsWith("redis_mode:")) {
                    continue;
                }

                String actualMode = line.substring("redis_mode:".length()).trim();
                for (String expectedMode : expectedModes) {
                    if (expectedMode.equalsIgnoreCase(actualMode)) {
                        return true;
                    }
                }
                return false;
            }
            return false;

        } catch (JedisException e) {
            return false;
        }
    }

    /**
     * 工具类不允许实例化。
     */
    private RedisCheckUtil() {
    }
}
