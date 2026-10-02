package com.allmusic.network;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 简单的 HTTP 文件服务器，用于向客户端提供 B站转码后的 MP3 文件
 */
public class HttpFileServer {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-HTTP");

    private HttpServer server;
    private ExecutorService executor;
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
        executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
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
            server = null;
        }
        // 线程池不关的话 /reload 会一次泄漏 4 个线程（旧实现漏了这一步）
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        logger.info("HTTP 文件服务器已停止");
    }

    /**
     * 重启 HTTP 服务器（/mm admin reload 用）。
     * 端口/目录/对外主机都只在启动时读取，配置改了就靠重启让新值生效。
     */
    public void restart(int port, String cacheDir, String host) {
        stop();
        start(port, cacheDir, host);
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

                // 安全检查：解析到真实路径后按「路径段」比对是否仍在根目录内。
                // 旧实现用 String.startsWith 比前缀，/cache 与 /cache-evil 这种兄弟目录会被误判为合法；
                // Path.startsWith 是按路径段比的，不存在这个漏洞。
                Path root = rootDir.getCanonicalFile().toPath();
                Path target = new File(rootDir, path).getCanonicalFile().toPath();
                if (!target.startsWith(root)) {
                    exchange.sendResponseHeaders(403, -1);
                    exchange.close();
                    return;
                }

                if (Files.isRegularFile(target)) {
                    long length = Files.size(target);

                    // 支持 Range：客户端断线续传（Range: bytes=N-）不再只能拿到整个文件从头 skip
                    long start = 0;
                    long end = length - 1;
                    boolean partial = false;
                    String range = exchange.getRequestHeaders().getFirst("Range");
                    if (range != null && range.startsWith("bytes=") && length > 0) {
                        String spec = range.substring("bytes=".length()).trim();
                        int dash = spec.indexOf('-');
                        if (dash >= 0) {
                            try {
                                String from = spec.substring(0, dash).trim();
                                String to = spec.substring(dash + 1).trim();
                                if (from.isEmpty()) {
                                    // RFC 7233 后缀范围「bytes=-N」= 最后 N 个字节；
                                    // 旧实现把它当成 0~N（返回文件开头），会把数据发错
                                    long suffixLen = Long.parseLong(to);
                                    start = Math.max(0, length - suffixLen);
                                    end = length - 1;
                                } else {
                                    start = Long.parseLong(from);
                                    end = to.isEmpty() ? length - 1 : Math.min(Long.parseLong(to), length - 1);
                                }
                                partial = true;
                            } catch (NumberFormatException e) {
                                partial = false; // 解析不了就按普通请求整份下发
                                start = 0;
                                end = length - 1;
                            }
                        }
                    }
                    if (start < 0 || start >= length) {
                        exchange.getResponseHeaders().set("Content-Range", "bytes */" + length);
                        exchange.sendResponseHeaders(416, -1);
                        exchange.close();
                        return;
                    }
                    if (end < start) { // 区间非法（如 bytes=500-100）：退回整份下发
                        partial = false;
                        start = 0;
                        end = length - 1;
                    }

                    long responseLength = end - start + 1;
                    exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
                    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                    exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                    if (partial) {
                        exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + length);
                    }
                    exchange.sendResponseHeaders(partial ? 206 : 200, responseLength > 0 ? responseLength : -1);

                    // 流式发送：旧实现用 Files.readAllBytes 把整个文件读进内存，
                    // 一首歌几 MB、几个玩家同时拉就是几十 MB 的堆占用（还有 OOM 风险）
                    try (OutputStream os = exchange.getResponseBody();
                         InputStream in = Files.newInputStream(target)) {
                        copyRange(in, os, start, responseLength);
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

        /**
         * 从 offset 处开始，最多拷贝 length 字节（拷贝本身不整份读进内存）。
         */
        private static void copyRange(InputStream in, OutputStream out, long offset, long length) throws IOException {
            long skipped = 0;
            while (skipped < offset) {
                long n = in.skip(offset - skipped);
                if (n > 0) {
                    skipped += n;
                    continue;
                }
                if (in.read() < 0) {
                    return; // 文件比预期短，能发多少发多少
                }
                skipped++;
            }

            byte[] buf = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int read = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (read < 0) break;
                out.write(buf, 0, read);
                remaining -= read;
            }
        }
    }
}
