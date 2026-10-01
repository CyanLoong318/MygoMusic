package com.allmusic.client.audio;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 音频缓存
 *
 * 缓存键 = URL 的「稳定形式」（见 normalizeUrl）：
 * B站直链每次解析出来的域名镜像、时效参数(e=/deadline=)都会变，若直接拿整条 URL 做键，
 * 同一首歌每次播放都会重新下载。故对B站CDN链接只取路径做键。
 */
public class AudioCache {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-Cache");

    private final File cacheDir;
    private volatile int maxSizeMB;
    private volatile boolean enabled = true; // 由服务端 client-cache 配置下发
    private final Map<String, File> cacheIndex = new LinkedHashMap<>();

    public AudioCache(int maxSizeMB) {
        this.maxSizeMB = maxSizeMB;
        this.cacheDir = FabricLoader.getInstance().getGameDir().resolve("allmusic/cache").toFile();
        cacheDir.mkdirs();
        loadIndex();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setMaxSizeMB(int maxSizeMB) { this.maxSizeMB = Math.max(1, maxSizeMB); }

    /**
     * 获取缓存文件
     */
    public File get(String url) {
        if (url == null || url.isEmpty()) return null;
        File file = new File(cacheDir, hashUrl(url) + ".mp3");

        if (file.exists() && file.length() > 0) {
            // 更新访问时间
            file.setLastModified(System.currentTimeMillis());
            return file;
        }

        return null;
    }

    /**
     * 保存到缓存
     */
    public File save(String url, InputStream inputStream) throws IOException {
        String hash = hashUrl(url);
        File file = new File(cacheDir, hash + ".mp3");

        try (OutputStream outputStream = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }

        // 检查缓存大小
        cleanCache();

        return file;
    }

    /**
     * 创建「边下边写」的缓存写入器：下载的同时落盘，播放到自然结束才 commit。
     * 中途切歌/停止则 abort —— 这样缓存目录里不会留下半截的、放不出声的坏文件。
     */
    public CacheWriter writer(String url) {
        if (!enabled || url == null || url.isEmpty()) return null;
        try {
            return new CacheWriter(new File(cacheDir, hashUrl(url) + ".mp3"));
        } catch (IOException e) {
            logger.warn("创建缓存写入器失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 边下载边写缓存的写入器。先写 .part 临时文件，全部下完后原子改名成正式缓存文件。
     */
    public class CacheWriter {
        private final File partFile;
        private final File finalFile;
        private final OutputStream out;
        private boolean closed = false;

        private CacheWriter(File finalFile) throws IOException {
            this.finalFile = finalFile;
            this.partFile = new File(finalFile.getParentFile(), finalFile.getName() + ".part");
            this.out = new BufferedOutputStream(new FileOutputStream(partFile), 64 * 1024);
        }

        /** 写入一块刚下载到的数据（由下载线程调用） */
        public synchronized void write(byte[] buf, int off, int len) {
            if (closed || len <= 0) return;
            try {
                out.write(buf, off, len);
            } catch (IOException e) {
                logger.warn("写缓存失败: {}", e.getMessage());
            }
        }

        /** 下载完整：落盘并让缓存生效 */
        public synchronized void commit() {
            if (closed) return;
            closed = true;
            try {
                out.close();
                if (partFile.length() > 0) {
                    if (finalFile.exists()) finalFile.delete();
                    if (!partFile.renameTo(finalFile)) {
                        logger.warn("缓存改名失败: {}", finalFile.getName());
                        partFile.delete();
                    } else {
                        cleanCache();
                    }
                } else {
                    partFile.delete();
                }
            } catch (IOException e) {
                logger.warn("缓存落盘失败: {}", e.getMessage());
                partFile.delete();
            }
        }

        /** 中途停止：丢弃半截文件 */
        public synchronized void abort() {
            if (closed) return;
            closed = true;
            try {
                out.close();
            } catch (IOException ignored) {}
            partFile.delete();
        }
    }

    /**
     * 清理缓存
     */
    private void cleanCache() {
        File[] files = cacheDir.listFiles();
        if (files == null) return;

        long totalSize = 0;
        for (File file : files) {
            totalSize += file.length();
        }

        long maxSizeBytes = (long) maxSizeMB * 1024 * 1024;

        if (totalSize > maxSizeBytes) {
            // 按修改时间排序，删除最旧的文件
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

            for (File file : files) {
                if (totalSize <= maxSizeBytes * 0.8) break;

                long fileSize = file.length();
                if (file.delete()) {
                    totalSize -= fileSize;
                    logger.info("清理缓存文件: {}", file.getName());
                }
            }
        }
    }

    /**
     * 清空缓存
     */
    public void clear() {
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        cacheIndex.clear();
        logger.info("缓存已清空");
    }

    /**
     * 获取缓存大小 (MB)
     */
    public double getSizeMB() {
        File[] files = cacheDir.listFiles();
        if (files == null) return 0;

        long totalSize = 0;
        for (File file : files) {
            totalSize += file.length();
        }

        return totalSize / (1024.0 * 1024.0);
    }

    /**
     * URL 的稳定形式（缓存键）
     */
    private String normalizeUrl(String url) {
        // 直链字段可能是多候选（\n 分隔），第一条即主链
        int nl = url.indexOf('\n');
        if (nl >= 0) url = url.substring(0, nl);

        if (isBilibiliCdn(url)) {
            try {
                String path = new URI(url).getPath();
                if (path != null && !path.isEmpty()) {
                    return "bili:" + path;
                }
            } catch (Exception ignored) {
                // 解析失败就退回原串
            }
        }
        return url;
    }

    /**
     * 是否B站CDN直链（域名会轮换、带时效参数，必须用路径做键）
     */
    private static boolean isBilibiliCdn(String url) {
        try {
            URI u = new URI(url);
            String host = u.getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            if (host.endsWith("bilivideo.com") || host.endsWith("bilivideo.cn")
                    || host.endsWith("hdslb.com")) {
                return true;
            }
            // B站也会下发 akamaized.net 镜像，靠路径特征区分
            String path = u.getPath();
            return host.endsWith("akamaized.net") && path != null && path.startsWith("/upgcxcode/");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * URL 哈希
     */
    private String hashUrl(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(normalizeUrl(url).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(normalizeUrl(url).hashCode());
        }
    }

    /**
     * 加载索引
     */
    private void loadIndex() {
        // 简单实现，不需要持久化索引
    }
}
