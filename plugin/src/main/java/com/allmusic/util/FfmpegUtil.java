package com.allmusic.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * FFmpeg 工具类
 */
public class FfmpegUtil {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-FFmpeg");

    /**
     * 检查 ffmpeg 是否可用
     */
    public static boolean isAvailable(String ffmpegPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-version");
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 提取视频中的音频为 MP3
     */
    public static boolean extractAudio(String ffmpegPath, String inputPath, String outputPath) {
        try {
            // 确保输出目录存在
            File outputFile = new File(outputPath);
            outputFile.getParentFile().mkdirs();

            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath,
                    "-i", inputPath,
                    "-vn",                    // 不包含视频
                    "-acodec", "libmp3lame",  // MP3 编码
                    "-q:a", "2",              // 音质 (2=高质量)
                    "-y",                     // 覆盖输出文件
                    outputPath
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(300, TimeUnit.SECONDS); // 5分钟超时

            if (finished && process.exitValue() == 0) {
                logger.info("FFmpeg 转码成功: {} -> {}", inputPath, outputPath);
                return true;
            } else {
                logger.error("FFmpeg 转码失败: {}", inputPath);
                return false;
            }
        } catch (Exception e) {
            logger.error("FFmpeg 转码异常: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取文件大小 (MB)
     */
    public static double getFileSizeMB(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) return 0;
        return file.length() / (1024.0 * 1024.0);
    }

    /**
     * 清理缓存目录
     */
    public static void cleanCache(String cacheDir, int maxSizeMB) {
        File dir = new File(cacheDir);
        if (!dir.exists()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        double totalSize = 0;
        for (File file : files) {
            totalSize += file.length() / (1024.0 * 1024.0);
        }

        if (totalSize > maxSizeMB) {
            // 按修改时间排序，删除最旧的文件
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

            for (File file : files) {
                if (totalSize <= maxSizeMB * 0.8) break; // 清理到80%
                double size = file.length() / (1024.0 * 1024.0);
                if (file.delete()) {
                    totalSize -= size;
                    logger.info("清理缓存文件: {} ({:.2f} MB)", file.getName(), size);
                }
            }
        }
    }
}
