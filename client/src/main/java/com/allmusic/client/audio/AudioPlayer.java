package com.allmusic.client.audio;

import com.allmusic.client.AllMusicClient;
import com.allmusic.client.config.ClientConfig;
import com.allmusic.client.network.ChannelHandler;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import org.slf4j.Logger;

import javax.sound.sampled.*;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 音频播放器 - 使用 JLayer 解码 MP3
 */
public class AudioPlayer {
    private static final Logger logger = AllMusicClient.getLogger();

    private final ClientConfig config;
    private final AudioCache cache;

    private SourceDataLine currentLine;
    private AtomicBoolean playing = new AtomicBoolean(false);
    private AtomicBoolean paused = new AtomicBoolean(false);
    private AtomicInteger volume = new AtomicInteger(80);
    private Thread playThread;

    /**
     * 播放代际号：每次 stop()/切歌自增，用于让上一首歌的残留解码线程失效，
     * 避免其清除新歌的 playing 状态或关闭新歌的音频输出线。
     */
    private volatile int generation = 0;
    /** 客户端本地暂停累计时长（毫秒），用于恢复后让进度/歌词对账，不因暂停凭空跳变 */
    private final AtomicLong pauseTotalMs = new AtomicLong(0);
    private volatile long pausedAtMs = -1;

    private String currentUrl;
    private String currentTitle;
    private String currentArtist;
    private long currentDuration;
    private long playbackStartTime;
    private long playbackPosition;

    public AudioPlayer(ClientConfig config) {
        this.config = config;
        // 默认 512MB，收到服务端 client-cache 配置后会覆盖 enabled/maxSize
        this.cache = new AudioCache(512);
        this.volume.set(config.getVolume());
    }

    /**
     * 应用服务端下发的客户端缓存设置（client-cache 配置）
     */
    public void applyCacheConfig(boolean enabled, int maxSizeMb) {
        if (cache == null) return;
        cache.setEnabled(enabled);
        cache.setMaxSizeMB(maxSizeMb);
        logger.info("客户端缓存设置已应用(来自服务端): enabled={}, maxSize={}MB", enabled, maxSizeMb);
    }

    /**
     * 播放音频（切歌/新歌会自动取消暂停状态）
     */
    public void play(String url, String title, String artist, long duration) {
        stop(); // 内部会 generation++ 并使旧解码线程失效

        this.currentUrl = url;
        this.currentTitle = title;
        this.currentArtist = artist;
        this.currentDuration = duration;
        this.playbackPosition = 0;
        paused.set(false);
        pausedAtMs = -1;
        pauseTotalMs.set(0);

        final int myEpoch = generation; // play() 已调 stop() 自增，取当前代际
        playThread = new Thread(() -> {
            try {
                playAudio(url, myEpoch);
            } catch (Exception e) {
                logger.error("播放失败: " + e.getMessage(), e);
            }
        }, "MygoMusic-Audio");
        playThread.setDaemon(true);
        playThread.start();
    }

    /**
     * 播放音频（携带本线程代际号，用于失效保护）
     */
    private void playAudio(String url, int myEpoch) throws Exception {
        if (myEpoch != generation) return;

        // 检查缓存（是否启用缓存由服务端 client-cache 配置决定）
        if (cache.isEnabled()) {
            File cachedFile = cache.get(url);
            if (cachedFile != null && cachedFile.exists()) {
                logger.info("使用缓存播放: {}", url);
                playFile(cachedFile, myEpoch);
                return;
            }
        }

        // 下载并播放
        logger.info("开始下载: {}", url);
        InputStream audioStream = downloadAudio(url);

        if (audioStream != null) {
            playStream(audioStream, myEpoch);
        } else {
            logger.error("下载失败: {}", url);
        }
    }

    /**
     * 下载音频
     */
    private InputStream downloadAudio(String url) {
        try {
            URLConnection connection = new URL(url).openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            // 根据 URL 主机动态设置 Referer
            String referer = getReferer(url);
            if (!referer.isEmpty()) {
                connection.setRequestProperty("Referer", referer);
            }
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            return connection.getInputStream();
        } catch (Exception e) {
            logger.error("下载音频失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 根据音频 URL 判断来源，返回对应的 Referer
     */
    private String getReferer(String url) {
        if (url == null) return "";
        if (url.contains("bilibili") || url.contains("bilivideo") || url.contains("hdslb")) {
            return "https://www.bilibili.com/";
        }
        if (url.contains("126.net") || url.contains("music.163")) {
            return "https://music.163.com/";
        }
        if (url.contains("kugou")) {
            return "https://www.kugou.com/";
        }
        return "";
    }

    /**
     * 播放文件（MP3）
     */
    private void playFile(File file, int myEpoch) throws Exception {
        try (InputStream stream = new FileInputStream(file)) {
            playMp3Stream(stream, myEpoch);
        }
    }

    /**
     * 播放流（MP3）
     */
    private void playStream(InputStream stream, int myEpoch) throws Exception {
        playMp3Stream(stream, myEpoch);
    }

    /**
     * 使用 JLayer 解码并播放 MP3 流
     *
     * 暂停处理：暂停时不读取/不关闭帧，仅阻塞等待，从而保持播放位置（不会静默快进或跑完触发切歌）。
     * 代际保护：只有本线程代际仍等于当前 generation 时才允许清理共享播放状态。
     */
    private void playMp3Stream(InputStream stream, int myEpoch) throws Exception {
        try (BufferedInputStream bufferedStream = new BufferedInputStream(stream)) {
            Bitstream bitstream = new Bitstream(bufferedStream);
            Decoder decoder = new Decoder();

            SourceDataLine line = null;
            if (myEpoch != generation) return; // 尚未开播就被切走（try-with 负责关闭流）

            playing.set(true);
            playbackStartTime = System.currentTimeMillis();
            pauseTotalMs.set(0);
            int totalFrames = 0;

            try {
                while (playing.get() && myEpoch == generation) {
                    // 暂停：阻塞等待恢复，期间完全不碰 bitstream（位置保持）
                    while (paused.get() && playing.get() && myEpoch == generation) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            break; // stop()/切歌中断本线程 → 由外层条件退出
                        }
                    }
                    if (!playing.get() || myEpoch != generation) break;

                    Header header = bitstream.readFrame();
                    if (header == null) break; // 自然 EOF（暂停状态下到不了这里）

                    SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    bitstream.closeFrame();
                    totalFrames++;

                    short[] samples = output.getBuffer();
                    int channels = output.getChannelCount();
                    int frequency = output.getSampleFrequency();

                    // 延迟初始化音频输出线（需要知道采样率/声道数）
                    if (line == null) {
                        AudioFormat format = new AudioFormat(frequency, 16, channels, true, false);
                        line = openOutputLine(format, frequency, channels);
                        if (line == null) {
                            logger.error("无法打开任何音频输出设备: sampleRate={}, channels={} (请检查系统声音设备/默认播放设备)",
                                    frequency, channels);
                            return;
                        }
                        line.start();
                        if (myEpoch != generation) {
                            // 等待输出线打开期间已被切歌，只关本地 line，不污染共享状态
                            try { line.stop(); line.close(); } catch (Exception ignored) {}
                            return;
                        }
                        currentLine = line;
                    }

                    // 应用音量（直接操作 PCM 样本）
                    applyVolume(samples);

                    // 转换为字节数组并写入
                    byte[] pcm = shortToBytes(samples);
                    line.write(pcm, 0, pcm.length);

                    playbackPosition = effectivePositionMs(System.currentTimeMillis());
                }

                long elapsedSec = (System.currentTimeMillis() - playbackStartTime - pauseTotalMs.get()) / 1000;

                if (myEpoch != generation) {
                    // 已被切歌/停止：本线程属旧歌，不做清理（共享状态归新线程/已停止）
                } else if (!playing.get()) {
                    // 用户主动停止/切歌
                    if (line != null) {
                        logger.info("播放已停止: 已解码 {} 帧, 约 {} 秒", totalFrames, elapsedSec);
                    } else {
                        logger.info("播放已停止 (尚未开始输出), 已解码 {} 帧", totalFrames);
                    }
                } else if (line != null) {
                    if (paused.get()) {
                        // 暂停广播恰好晚于 EOF 到达：不触发自动切歌（服务端也会忽略暂停中的完成信号）
                        logger.info("播放已到末尾但处于暂停，不自动切歌");
                    } else {
                        // 等待剩余缓冲播完
                        line.drain();
                        logger.info("播放结束: 共解码 {} 帧, 约 {} 秒", totalFrames, elapsedSec);
                        // 播放自然结束 → 通知服务端切下一首（服务端定时器作兜底）
                        if (playing.get() && myEpoch == generation && !paused.get()) {
                            ChannelHandler.notifySongFinished();
                        }
                    }
                } else {
                    logger.error("没有解码出任何有效音频帧 (totalFrames=0)，播放失败");
                }
            } finally {
                if (line != null) {
                    try {
                        line.stop();
                        line.close();
                    } catch (Exception ignored) {}
                }
                // 仅当前代际允许清共享状态；旧代际线程绝不动新歌的 playing/currentLine
                if (myEpoch == generation) {
                    playing.set(false);
                    currentLine = null;
                }
            }
        }
    }

    /**
     * 有效播放进度（扣除本地暂停时长，保证暂停期间进度不凭空前进）
     */
    private long effectivePositionMs(long now) {
        return Math.max(0, now - playbackStartTime - pauseTotalMs.get());
    }

    /**
     * 打开音频输出线。优先使用系统默认输出设备；若失败，遍历所有混音器尝试可用的输出设备。
     */
    private SourceDataLine openOutputLine(AudioFormat format, int frequency, int channels) {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

        // 1. 尝试默认输出设备
        try {
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format);
            logger.info("音频输出已打开: sampleRate={}, channels={}, 设备={}",
                    frequency, channels, getMixerName(AudioSystem.getMixer(null)));
            return line;
        } catch (Exception e) {
            logger.warn("默认音频设备打开失败: {}，尝试其他可用设备...", e.getMessage());
        }

        // 2. 遍历所有混音器，寻找支持该格式的输出设备
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (!mixer.isLineSupported(info)) continue;
                SourceDataLine line = (SourceDataLine) mixer.getLine(info);
                line.open(format);
                logger.info("音频输出已打开(备选设备): sampleRate={}, channels={}, 设备={}",
                        frequency, channels, getMixerName(mixerInfo));
                return line;
            } catch (Exception ignored) {
                // 尝试下一个设备
            }
        }

        return null;
    }

    private String getMixerName(Mixer mixer) {
        return mixer == null ? "未知" : (mixer.getMixerInfo() == null ? "未知" : mixer.getMixerInfo().getName());
    }

    private String getMixerName(Mixer.Info mixerInfo) {
        return mixerInfo == null ? "未知" : mixerInfo.getName();
    }

    /**
     * 应用音量（16-bit PCM）
     */
    private void applyVolume(short[] samples) {
        int vol = volume.get();
        if (vol >= 100) return;

        float factor = vol / 100.0f;
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) (samples[i] * factor);
        }
    }

    /**
     * short[] 转 byte[]（16-bit little-endian PCM）
     */
    private byte[] shortToBytes(short[] samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            short sample = samples[i];
            bytes[i * 2] = (byte) (sample & 0xFF);
            bytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return bytes;
    }

    /**
     * 停止播放（同时取消暂停并让旧解码线程失效）
     */
    public void stop() {
        playing.set(false);
        paused.set(false);
        pausedAtMs = -1;
        generation++; // 使旧解码线程失效：其 finally 不再清共享 playing/currentLine
        if (currentLine != null) {
            try {
                currentLine.stop();
                currentLine.flush();
                currentLine.close();
            } catch (Exception ignored) {}
            currentLine = null;
        }
        if (playThread != null) {
            playThread.interrupt();
        }
        playbackPosition = 0;
    }

    /**
     * 暂停（幂等）
     */
    public void pause() {
        if (paused.compareAndSet(false, true)) {
            pausedAtMs = System.currentTimeMillis();
        }
    }

    /**
     * 恢复（幂等；把本次暂停时长计入，避免恢复后进度凭空跳变）
     */
    public void resume() {
        if (paused.compareAndSet(true, false)) {
            long now = System.currentTimeMillis();
            if (pausedAtMs >= 0) {
                pauseTotalMs.addAndGet(now - pausedAtMs);
            }
            pausedAtMs = -1;
        }
    }

    /**
     * 是否正在播放
     */
    public boolean isPlaying() {
        return playing.get();
    }

    /**
     * 是否暂停
     */
    public boolean isPaused() {
        return paused.get();
    }

    /**
     * 获取播放进度
     */
    public long getPlaybackPosition() {
        return playbackPosition;
    }

    /**
     * 设置音量
     */
    public void setVolume(int vol) {
        this.volume.set(Math.max(0, Math.min(100, vol)));
        config.setVolume(vol);
        config.save();
    }

    /**
     * 获取音量
     */
    public int getVolume() {
        return volume.get();
    }

    /**
     * 获取当前歌曲标题
     */
    public String getCurrentTitle() {
        return currentTitle;
    }

    /**
     * 获取当前歌曲歌手
     */
    public String getCurrentArtist() {
        return currentArtist;
    }

    /**
     * 获取当前歌曲时长
     */
    public long getCurrentDuration() {
        return currentDuration;
    }
}
