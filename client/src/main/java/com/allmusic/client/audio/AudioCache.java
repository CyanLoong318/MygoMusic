package com.allmusic.client.audio;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 音频缓存
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
        String hash = hashUrl(url);
        File file = new File(cacheDir, hash + ".mp3");

        if (file.exists()) {
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
     * URL 哈希
     */
    private String hashUrl(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(url.hashCode());
        }
    }

    /**
     * 加载索引
     */
    private void loadIndex() {
        // 简单实现，不需要持久化索引
    }
}
