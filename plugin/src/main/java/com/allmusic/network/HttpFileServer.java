package com.allmusic.network;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.concurrent.Executors;

/**
 * 简单的 HTTP 文件服务器，用于向客户端提供 B站转码后的 MP3 文件
 */
public class HttpFileServer {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-HTTP");

    private HttpServer server;
    private int port;
    private File rootDir;
    // 配置指定的对外主机(域名/IPv4/IPv6)，空串 = 自动检测公网 IPv6
    private String configuredHost = "";

    /**
     * 启动 HTTP 服务器
     * 如果指定端口被占用（例如被其它插件占用），自动回退到系统分配的随机空闲端口
     * @param port 期望端口号
     * @param cacheDir 缓存目录
     */
    public void start(int port, String cacheDir) {
        start(port, cacheDir, "");
    }

    /**
     * 启动 HTTP 服务器
     * 如果指定端口被占用（例如被其它插件占用），自动回退到系统分配的随机空闲端口
     * @param port 期望端口号
     * @param cacheDir 缓存目录
     * @param host 对外可达的主机(域名/IPv4/IPv6)；为空时自动检测本机公网 IPv6
     */
    public void start(int port, String cacheDir, String host) {
        this.rootDir = new File(cacheDir);
        this.configuredHost = (host == null) ? "" : host.trim();

        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            logger.warn("端口 {} 已被占用 ({}), 自动切换到随机空闲端口...", port, e.getMessage());
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
            } catch (IOException e2) {
                logger.error("HTTP 服务器启动失败: " + e2.getMessage(), e2);
                return;
            }
        }

        // 记录实际绑定的端口 (可能为随机端口)
        this.port = server.getAddress().getPort();
        server.createContext("/", new FileHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        logger.info("HTTP 文件服务器已启动: 端口={}, 目录={}, 对外分发地址=http://{}:{}/",
                this.port, cacheDir, resolveHost(), this.port);
    }

    /**
     * 停止 HTTP 服务器
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("HTTP 文件服务器已停止");
        }
    }

    /**
     * 获取服务器端口
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取文件的 HTTP URL。
     * 主机部分优先使用配置指定值，否则自动检测本机公网 IPv6（无公网 IPv4 时外地玩家只能走 IPv6），
     * 两者都不可用时退回 127.0.0.1（仅本机可访问）。
     */
    public String getFileUrl(File file) {
        if (file == null || !file.exists()) return null;
        String relativePath = rootDir.toURI().relativize(file.toURI()).getPath();
        return "http://" + resolveHost() + ":" + port + "/" + relativePath;
    }

    /**
     * 解析对外分发 URL 使用的主机
     */
    private String resolveHost() {
        if (!configuredHost.isEmpty()) {
            return formatHost(configuredHost);
        }
        String ipv6 = detectPublicIpv6();
        if (ipv6 != null) {
            return "[" + ipv6 + "]";
        }
        return "127.0.0.1";
    }

    /**
     * 整理用户填写的主机：去掉误填的协议前缀/末尾斜杠，IPv6 加方括号
     */
    private static String formatHost(String host) {
        host = host.trim();
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        // IPv6 字面量需要加方括号才能拼进 URL
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return host;
    }

    /**
     * 检测本机公网(全局作用域) IPv6 地址。
     * 遍历所有已启用且非虚拟的网卡，跳过回环/链路本地/站点本地/任播/组播地址；
     * Windows 上 VMware 等虚拟网卡虽也配有 IPv6，但通常只有 fe80 链路本地地址，会被自然过滤掉。
     */
    private static String detectPublicIpv6() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;

                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet6Address
                            && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()
                            && !addr.isSiteLocalAddress()
                            && !addr.isAnyLocalAddress()
                            && !addr.isMulticastAddress()) {
                        String s = addr.getHostAddress();
                        // 去掉 "%接口" 之类的 zone 后缀
                        int zone = s.indexOf('%');
                        return zone >= 0 ? s.substring(0, zone) : s;
                    }
                }
            }
        } catch (SocketException e) {
            logger.warn("检测公网IPv6地址失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 文件处理器
     */
    private class FileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                // 去掉开头的 /
                if (path.startsWith("/")) path = path.substring(1);

                File file = new File(rootDir, path);

                // 安全检查：确保文件在根目录内
                if (!file.getCanonicalPath().startsWith(rootDir.getCanonicalPath())) {
                    exchange.sendResponseHeaders(403, -1);
                    exchange.close();
                    return;
                }

                if (file.exists() && file.isFile()) {
                    byte[] data = Files.readAllBytes(file.toPath());
                    exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
                    exchange.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
                    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                    exchange.sendResponseHeaders(200, data.length);

                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(data);
                    }
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                }
            } catch (Exception e) {
                logger.debug("HTTP 请求处理失败: " + e.getMessage());
                try {
                    exchange.sendResponseHeaders(500, -1);
                } catch (IOException ignored) {}
                exchange.close();
            }
        }
    }
}
