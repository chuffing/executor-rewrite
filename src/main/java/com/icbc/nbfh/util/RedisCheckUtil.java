package com.icbc.nbfh.util;

import com.icbc.nbfh.common.constant.RedisProtocolConstants;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
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
            return RedisProtocolConstants.PONG
                    .equalsIgnoreCase(jedis.ping());

        } catch (JedisException e) {
            return false;
        }
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
            if (!RedisProtocolConstants.PONG
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
     * 创建完成身份认证的Jedis客户端。
     */
    private static Jedis createAuthenticatedJedis(String host,
                                                  int port,
                                                  String password,
                                                  int timeout) {

        Jedis jedis = new Jedis(host, port, timeout);

        try {
            jedis.auth(password);
            return jedis;

        } catch (JedisException e) {
            jedis.close();
            throw e;
        }
    }

    /**
     * 工具类不允许实例化。
     */
    private RedisCheckUtil() {
    }
}
