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
     * 播放音频
     */
    public void play(String url, String title, String artist, long duration) {
        stop();

        this.currentUrl = url;
        this.currentTitle = title;
        this.currentArtist = artist;
        this.currentDuration = duration;
        this.playbackPosition = 0;

        playThread = new Thread(() -> {
            try {
                playAudio(url);
            } catch (Exception e) {
                logger.error("播放失败: " + e.getMessage(), e);
            }
        }, "MygoMusic-Audio");
        playThread.setDaemon(true);
        playThread.start();
    }

    /**
     * 播放音频文件
     */
    private void playAudio(String url) throws Exception {
        // 检查缓存（是否启用缓存由服务端 client-cache 配置决定）
        if (cache.isEnabled()) {
            File cachedFile = cache.get(url);
            if (cachedFile != null && cachedFile.exists()) {
                logger.info("使用缓存播放: {}", url);
                playFile(cachedFile);
                return;
            }
        }

        // 下载并播放
        logger.info("开始下载: {}", url);
        InputStream audioStream = downloadAudio(url);

        if (audioStream != null) {
            playStream(audioStream);
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
    private void playFile(File file) throws Exception {
        try (InputStream stream = new FileInputStream(file)) {
            playMp3Stream(stream);
        }
    }

    /**
     * 播放流（MP3）
     */
    private void playStream(InputStream stream) throws Exception {
        playMp3Stream(stream);
    }

    /**
     * 使用 JLayer 解码并播放 MP3 流
     */
    private void playMp3Stream(InputStream stream) throws Exception {
        try (BufferedInputStream bufferedStream = new BufferedInputStream(stream)) {
            Bitstream bitstream = new Bitstream(bufferedStream);
            Decoder decoder = new Decoder();

            SourceDataLine line = null;
            playing.set(true);
            playbackStartTime = System.currentTimeMillis();
            int totalFrames = 0;

            try {
                Header header;
                while (playing.get() && (header = bitstream.readFrame()) != null) {
                    // 暂停处理
                    if (paused.get()) {
                        Thread.sleep(50);
                        bitstream.closeFrame();
                        continue;
                    }

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
                        currentLine = line;
                    }

                    // 应用音量（直接操作 PCM 样本）
                    applyVolume(samples);

                    // 转换为字节数组并写入
                    byte[] pcm = shortToBytes(samples);
                    line.write(pcm, 0, pcm.length);

                    playbackPosition = System.currentTimeMillis() - playbackStartTime;
                }

                // 等待播放完成
                if (line != null && playing.get()) {
                    line.drain();
                }

                long elapsedSec = (System.currentTimeMillis() - playbackStartTime) / 1000;
                if (!playing.get()) {
                    // 用户主动停止/切歌
                    if (line != null) {
                        logger.info("播放已停止: 已解码 {} 帧, 约 {} 秒", totalFrames, elapsedSec);
                    } else {
                        logger.info("播放已停止 (尚未开始输出), 已解码 {} 帧", totalFrames);
                    }
                } else if (line != null) {
                    logger.info("播放结束: 共解码 {} 帧, 约 {} 秒", totalFrames, elapsedSec);
                    // 播放自然结束 → 通知服务端切下一首（服务端定时器作兜底）
                    ChannelHandler.notifySongFinished();
                } else {
                    logger.error("没有解码出任何有效音频帧 (totalFrames=0)，播放失败");
                }
            } finally {
                playing.set(false);
                if (line != null) {
                    try {
                        line.stop();
                        line.close();
                    } catch (Exception ignored) {}
                }
                currentLine = null;
            }
        }
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
     * 停止播放
     */
    public void stop() {
        playing.set(false);
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
     * 暂停
     */
    public void pause() {
        paused.set(true);
    }

    /**
     * 恢复
     */
    public void resume() {
        paused.set(false);
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
