package com.allmusic.client.audio;

import com.allmusic.client.AllMusicClient;
import com.allmusic.client.config.ClientConfig;
import com.allmusic.client.network.ChannelHandler;
import org.slf4j.Logger;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 音频播放器
 *
 * 两种音频来源统一成「PCM 帧」后走同一套播放逻辑（暂停/断点续播/音量/进度/播完切歌）：
 *  - MP3（JLayer）：酷狗/网易云，以及旧方案下服务端转码出来的 B站音频；
 *  - B站直链 .m4s（fMP4 里的 AAC，JAAD 解码）：见 {@link BilibiliM4sSource}。
 *
 * 拉流统一走 {@link PrefetchStream}：边下边播、边下边写缓存、断线 Range 续传。
 */
public class AudioPlayer {
    private static final Logger logger = AllMusicClient.getLogger();

    /** 预读缓冲上限：几 MB 的歌能在暂停期间整首读进内存，恢复播放不受 CDN 断连影响 */
    private static final int PREFETCH_BYTES = 32 * 1024 * 1024;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

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

    private String currentTitle;
    private String currentArtist;
    private String currentSource;
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
        play(url, title, artist, duration, "");
    }

    /**
     * 播放音频（切歌/新歌会自动取消暂停状态）
     *
     * @param source 音源标识（bilibili/kugou/netease），用于选择正确的请求头与缓存策略
     */
    public void play(String url, String title, String artist, long duration, String source) {
        stop(); // 内部会 generation++ 并使旧解码线程失效

        this.currentTitle = title;
        this.currentArtist = artist;
        this.currentSource = source == null ? "" : source;
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

        // 下载并播放（多候选直链：逐条尝试；断线时按已下载字节续传）
        logger.info("开始拉流: {}", firstLine(url));
        OpenedStream net = openFirstAvailable(url, 0);

        AudioCache.CacheWriter writer = cache.writer(url);
        PrefetchStream prefetch = new PrefetchStream(net.stream(), writer,
                offset -> openFirstAvailable(url, offset).stream(), PREFETCH_BYTES, net.totalLength());

        try (PushbackInputStream pinned = new PushbackInputStream(prefetch, 32)) {
            byte[] head = new byte[12];
            int n = readAtLeast(pinned, head);
            if (n <= 0) {
                logger.error("音频流为空: {}", firstLine(url));
                return;
            }
            pinned.unread(head, 0, n);

            PcmFrameSource source = createSource(pinned, head, n, url);
            playPcm(source, myEpoch);
        }
    }

    /**
     * 按音频容器选择解码器。B站直链下发的可能是 m4s(fMP4/AAC) 也可能是 mp3，
     * 所以按内容嗅探而不是按扩展名/音源猜。
     */
    private PcmFrameSource createSource(PushbackInputStream in, byte[] head, int length, String url) throws IOException {
        if (length >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            logger.info("音频格式: MP4/m4s（B站DASH音轨，AAC）");
            return new BilibiliM4sSource(in);
        }
        if (isMp3(head, length)) {
            logger.info("音频格式: MP3");
            return new Mp3FrameSource(in);
        }
        throw new IOException("无法识别的音频格式(前" + length + "字节 " + hex(head, length)
                + ")，地址=" + firstLine(url));
    }

    private static boolean isMp3(byte[] head, int length) {
        if (length >= 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') return true; // ID3v2 标签
        // MPEG 帧同步
        return length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0;
    }

    /** 打开的网络流 + 该音频的总字节数（-1 未知），总长度用于识别「CDN 悄悄掐断连接」造成的假 EOF */
    private record OpenedStream(InputStream stream, long totalLength) {}

    /**
     * 打开音频流：支持多候选直链（服务端用 '\n' 分隔主链与备用CDN）与断点续传。
     */
    private OpenedStream openFirstAvailable(String urlField, long offset) throws IOException {
        IOException last = null;
        for (String candidate : urlField.split("\n")) {
            String url = candidate.trim();
            if (url.isEmpty()) continue;
            try {
                return openStream(url, offset);
            } catch (IOException e) {
                last = e;
                logger.warn("音频地址不可用({}): {} ({})",
                        offset > 0 ? "续传@" + offset : "直连", e.getMessage(), firstLine(url));
            }
        }
        if (last != null) throw last;
        throw new IOException("没有可用的音频地址");
    }

    /**
     * 打开单个地址的网络流
     */
    private OpenedStream openStream(String url, long offset) throws IOException {
        URLConnection connection;
        try {
            connection = java.net.URI.create(url).toURL().openConnection();
        } catch (IllegalArgumentException e) {
            throw new IOException("音频地址非法: " + e.getMessage());
        }
        connection.setRequestProperty("User-Agent", USER_AGENT);
        // 根据音源/域名动态设置 Referer（B站CDN防盗链，不带会 403）
        String referer = getReferer(url);
        if (!referer.isEmpty()) {
            connection.setRequestProperty("Referer", referer);
            connection.setRequestProperty("Origin", referer.endsWith("/")
                    ? referer.substring(0, referer.length() - 1) : referer);
        }
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);

        if (offset > 0) {
            connection.setRequestProperty("Range", "bytes=" + offset + "-");
        }

        int code = 0;
        long totalLength = -1;
        if (connection instanceof HttpURLConnection http) {
            code = http.getResponseCode();
            if (code >= 400) {
                throw new IOException("HTTP " + code);
            }
            if (code == 206) {
                // Content-Range: bytes 100-999/1000 → 总长度 1000
                String range = http.getHeaderField("Content-Range");
                int slash = range == null ? -1 : range.lastIndexOf('/');
                if (slash > 0) {
                    try {
                        totalLength = Long.parseLong(range.substring(slash + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else {
                totalLength = http.getContentLengthLong(); // 200：整个文件的长度
            }
        }

        InputStream stream = connection.getInputStream();

        // 服务器忽略 Range 而返回 200 整个文件时，自行跳过前面已下载的部分
        if (offset > 0 && code != 206) {
            skipFully(stream, offset);
        }

        return new OpenedStream(new BufferedInputStream(stream, 64 * 1024), totalLength);
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (in.read() < 0) {
                throw new IOException("续传流过短，无法定位到 " + n);
            } else {
                remaining--;
            }
        }
    }

    /**
     * 根据音频 URL 判断来源，返回对应的 Referer
     */
    private String getReferer(String url) {
        if (url == null) return "";
        if ("bilibili".equals(currentSource) || url.contains("bilivideo") || url.contains("hdslb")
                || url.contains("/upgcxcode/")) {
            return "https://www.bilibili.com/";
        }
        if (url.contains("126.net") || url.contains("music.163")) {
            return "https://music.163.com/";
        }
        if (url.contains("kugou") || url.contains("kglink")) {
            return "https://www.kugou.com/";
        }
        return "";
    }

    /**
     * 播放本地文件（缓存命中）
     */
    private void playFile(File file, int myEpoch) throws Exception {
        try (InputStream stream = new FileInputStream(file)) {
            PushbackInputStream in = new PushbackInputStream(new BufferedInputStream(stream, 64 * 1024), 32);
            byte[] head = new byte[12];
            int n = readAtLeast(in, head);
            if (n <= 0) {
                logger.error("缓存文件为空: {}", file.getName());
                return;
            }
            in.unread(head, 0, n);
            PcmFrameSource source = createSource(in, head, n, file.getName());
            playPcm(source, myEpoch);
        }
    }

    /**
     * 统一的播放循环：两种解码器都按「PCM 帧」喂进来
     *
     * 暂停处理：暂停时不读取下一帧，仅阻塞等待，从而保持播放位置（不会静默快进或跑完触发切歌）。
     * 代际保护：只有本线程代际仍等于当前 generation 时才允许清理共享播放状态。
     */
    private void playPcm(PcmFrameSource source, int myEpoch) throws Exception {
        SourceDataLine line = null;
        int totalFrames = 0;

        if (myEpoch != generation) {
            source.close();
            return;
        }

        playing.set(true);
        playbackStartTime = System.currentTimeMillis();
        pauseTotalMs.set(0);

        try {
            while (playing.get() && myEpoch == generation) {
                // 暂停：阻塞等待恢复，期间不消费网络流
                while (paused.get() && playing.get() && myEpoch == generation) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        break; // stop()/切歌中断本线程 → 由外层条件退出
                    }
                }
                if (!playing.get() || myEpoch != generation) break;

                PcmFrame frame = source.next();
                if (frame == null) break; // 自然 EOF（暂停状态下到不了这里）

                // 延迟初始化音频输出线（需要知道采样率/声道数）
                if (line == null) {
                    AudioFormat format = new AudioFormat(frame.sampleRate(), 16, frame.channels(), true, false);
                    line = openOutputLine(format, frame.sampleRate(), frame.channels());
                    if (line == null) {
                        logger.error("无法打开任何音频输出设备: sampleRate={}, channels={} (请检查系统声音设备/默认播放设备)",
                                frame.sampleRate(), frame.channels());
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
                applyVolume(frame.samples());

                // 转换为字节数组并写入
                byte[] pcm = shortToBytes(frame.samples());
                line.write(pcm, 0, pcm.length);

                totalFrames++;
                playbackPosition = effectivePositionMs(System.currentTimeMillis());
            }

            long elapsedSec = (System.currentTimeMillis() - playbackStartTime - pauseTotalMs.get()) / 1000;

            if (myEpoch != generation) {
                // 已被切歌/停止：本线程属旧歌，不做清理（共享状态归新线程/已停止）
            } else if (!playing.get()) {
                // 用户主动停止/切歌
                logger.info("播放已停止: 已解码 {} 帧, 约 {} 秒", totalFrames, elapsedSec);
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
            try {
                source.close();
            } catch (Exception ignored) {}
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

    /** 读满或读到 EOF 为止 */
    private static int readAtLeast(InputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int r = in.read(buf, read, buf.length - read);
            if (r < 0) break;
            read += r;
        }
        return read;
    }

    private static String hex(byte[] data, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(length, data.length); i++) {
            sb.append(String.format("%02X ", data[i]));
        }
        return sb.toString().trim();
    }

    private static String firstLine(String url) {
        if (url == null) return "";
        int i = url.indexOf('\n');
        return i < 0 ? url : url.substring(0, i) + " (+备用链)";
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
