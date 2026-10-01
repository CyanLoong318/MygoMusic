package com.allmusic.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * FFmpeg 工具类
 */
public class FfmpegUtil {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-FFmpeg");

    /** 默认转码超时（秒） */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;
    /** 失败时日志里保留的 ffmpeg 输出末尾长度（字符） */
    private static final int OUTPUT_TAIL_CHARS = 500;

    /**
     * 检查 ffmpeg 是否可用
     */
    public static boolean isAvailable(String ffmpegPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // 同样要排空输出：写满管道缓冲区一样会把子进程卡死
            drainQuietly(process.getInputStream(), null);
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 提取视频中的音频为 MP3
     *
     * @param timeoutSeconds 转码超时(秒)，&lt;=0 时用默认值
     */
    public static boolean extractAudio(String ffmpegPath, String inputPath, String outputPath, int timeoutSeconds) {
        int timeout = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        try {
            // 确保输出目录存在
            File outputFile = new File(outputPath);
            outputFile.getParentFile().mkdirs();

            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath,
                    "-hide_banner",           // 少写点日志
                    "-nostdin",               // 别把服务器控制台当成 ffmpeg 的 stdin
                    "-i", inputPath,
                    "-vn",                    // 不包含视频
                    "-acodec", "libmp3lame",  // MP3 编码
                    "-q:a", "2",              // 音质 (2=高质量)
                    "-y",                     // 覆盖输出文件
                    outputPath
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 必须边跑边读：合并后的输出写满管道缓冲区（Windows 上约 4KB）后，
            // ffmpeg 会永久阻塞在 write 上、进程永不退出。长音频的输出必然写满（1 小时约 8.6KB），
            // 短音频侥幸不触发——这正是「长视频转码卡满超时」的根因。
            StringBuilder tail = new StringBuilder();
            Thread drain = drainQuietly(process.getInputStream(), tail);

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly(); // 不留孤儿进程：否则它继续占着临时文件，后面删都删不掉
                logger.error("FFmpeg 转码超时({}秒)，已强制结束: {}", timeout, inputPath);
                return false;
            }
            drain.join(1000); // 等排空线程收尾，失败时日志里才有 ffmpeg 的原话

            if (process.exitValue() == 0) {
                logger.info("FFmpeg 转码成功: {} -> {}", inputPath, outputPath);
                return true;
            }
            logger.error("FFmpeg 转码失败(退出码={}): {}{}", process.exitValue(), inputPath, tailOf(tail));
            return false;
        } catch (Exception e) {
            logger.error("FFmpeg 转码异常: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 起一个守护线程把子进程输出读干净（防止管道写满卡死）。
     * tail 非 null 时保留末尾若干字符，失败时打进日志。
     */
    private static Thread drainQuietly(InputStream in, StringBuilder tail) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            try (InputStream stream = in) {
                int n;
                while ((n = stream.read(buf)) != -1) {
                    if (tail != null) {
                        synchronized (tail) {
                            tail.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                            int extra = tail.length() - OUTPUT_TAIL_CHARS * 2;
                            if (extra > 0) {
                                tail.delete(0, extra);
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
                // 进程退出/被强杀时读取中断，属正常情况
            }
        }, "MygoMusic-ffmpeg-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String tailOf(StringBuilder tail) {
        synchronized (tail) {
            String s = tail.toString().replace('\r', '\n').trim();
            if (s.isEmpty()) {
                return "";
            }
            return " | ffmpeg 输出末尾: " + (s.length() <= OUTPUT_TAIL_CHARS ? s : s.substring(s.length() - OUTPUT_TAIL_CHARS));
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
