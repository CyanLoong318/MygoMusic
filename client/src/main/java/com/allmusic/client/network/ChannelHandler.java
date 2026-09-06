package com.allmusic.client.network;

import com.allmusic.client.AllMusicClient;
import com.allmusic.client.audio.AudioPlayer;
import com.allmusic.client.gui.QueueState;
import com.allmusic.client.gui.SearchScreen;
import com.allmusic.client.lyrics.LyricsRenderer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 网络通道处理器
 */
public class ChannelHandler {
    private static final Logger logger = AllMusicClient.getLogger();
    // 插件消息通道固定为 allmusic:main（与服务端一致，勿改）
    private static final Identifier CHANNEL_ID = Identifier.of("allmusic", "main");

    // 包类型
    private static final byte PACKET_PLAY = 0x01;
    private static final byte PACKET_STOP = 0x02;
    private static final byte PACKET_PAUSE = 0x03;
    private static final byte PACKET_RESUME = 0x04;
    private static final byte PACKET_VOLUME = 0x05;
    private static final byte PACKET_SYNC = 0x06;
    private static final byte PACKET_QUEUE_SYNC = 0x08;
    private static final byte PACKET_SEARCH_RESULT = 0x09;
    // 客户端 → 服务端：当前歌曲已播放完毕（自动请求下一首）
    public static final byte PACKET_SONGFINISHED = 0x0C;
    // 服务端 → 客户端：客户端缓存设置（config.yml client-cache 段统一下发）
    private static final byte PACKET_CACHE_CONFIG = 0x0D;
    // 客户端 → 服务端：请求一次队列状态同步（GUI 打开时主动拉取播放/暂停状态）
    public static final byte PACKET_REQUEST_SYNC = 0x0E;

    private final AllMusicClient clientMod;

    public ChannelHandler(AllMusicClient clientMod) {
        this.clientMod = clientMod;
    }

    /**
     * 注册通道
     */
    public void register() {
        PayloadTypeRegistry.playS2C().register(AllMusicPayload.ID, AllMusicPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AllMusicPayload.ID, AllMusicPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(AllMusicPayload.ID, (payload, context) -> {
            byte[] data = payload.data();
            handlePacket(data, context.client());
        });
    }

    /**
     * 客户端 → 服务端 发送数据包
     */
    public static void sendToServer(byte[] data) {
        try {
            if (MinecraftClient.getInstance().getNetworkHandler() == null) {
                return;
            }
            ClientPlayNetworking.send(new AllMusicPayload(data));
        } catch (Exception e) {
            logger.warn("发送数据到服务端失败: " + e.getMessage());
        }
    }

    /**
     * 通知服务端当前歌曲已播放完（自动请求下一首）
     */
    public static void notifySongFinished() {
        sendToServer(new byte[]{PACKET_SONGFINISHED});
    }

    /**
     * 请求一次最新的队列状态同步（播放/暂停标志 + 队列/历史）。服务端收到后回发 QUEUE_SYNC。
     */
    public static void requestQueueSync() {
        sendToServer(new byte[]{PACKET_REQUEST_SYNC});
    }

    /**
     * 处理数据包
     */
    private void handlePacket(byte[] data, MinecraftClient client) {
        try {
            if (data.length < 1) return;

            byte packetType = data[0];

            switch (packetType) {
                case PACKET_PLAY:
                    handlePlay(data, client);
                    // #5 单P BV搜索自动播放时，关闭搜索界面
                    client.execute(() -> {
                        if (client.currentScreen instanceof SearchScreen) {
                            client.setScreen(null);
                        }
                    });
                    break;
                case PACKET_STOP:
                    handleStop(client);
                    break;
                case PACKET_PAUSE:
                    handlePause(client);
                    break;
                case PACKET_RESUME:
                    handleResume(client);
                    break;
                case PACKET_QUEUE_SYNC:
                    handleQueueSync(data, client);
                    break;
                case PACKET_SEARCH_RESULT:
                    handleSearchResult(data, client);
                    break;
                case PACKET_CACHE_CONFIG:
                    handleCacheConfig(data, client);
                    break;
                default:
                    logger.warn("未知的包类型: {}", packetType);
            }
        } catch (Exception e) {
            logger.error("处理数据包失败: " + e.getMessage(), e);
        }
    }

    /**
     * 处理播放包
     */
    private void handlePlay(byte[] data, MinecraftClient client) {
        try {
            int offset = 1;

            // 读取歌曲信息
            String title = readString(data, offset);
            offset += 2 + title.getBytes(StandardCharsets.UTF_8).length;

            String artist = readString(data, offset);
            offset += 2 + artist.getBytes(StandardCharsets.UTF_8).length;

            long duration = readLong(data, offset);
            offset += 8;

            String source = readString(data, offset);
            offset += 2 + source.getBytes(StandardCharsets.UTF_8).length;

            String audioUrl = readString(data, offset);
            offset += 2 + audioUrl.getBytes(StandardCharsets.UTF_8).length;

            String localPath = readString(data, offset);
            offset += 2 + localPath.getBytes(StandardCharsets.UTF_8).length;

            // 读取歌词
            int lyricsCount = readInt(data, offset);
            offset += 4;

            List<LyricsRenderer.LyricsLine> lyrics = new ArrayList<>();
            boolean hasTranslation = false;

            for (int i = 0; i < lyricsCount; i++) {
                long timestamp = readLong(data, offset);
                offset += 8;

                String text = readString(data, offset);
                offset += 2 + text.getBytes(StandardCharsets.UTF_8).length;

                String translation = readString(data, offset);
                offset += 2 + translation.getBytes(StandardCharsets.UTF_8).length;

                if (translation != null && !translation.isEmpty()) {
                    hasTranslation = true;
                }

                lyrics.add(new LyricsRenderer.LyricsLine(timestamp, text, translation));
            }

            // 确定播放URL
            String playUrl = (localPath != null && !localPath.isEmpty()) ? localPath : audioUrl;
            final boolean finalHasTranslation = hasTranslation;

            // 在主线程中播放
            client.execute(() -> {
                AudioPlayer audioPlayer = clientMod.getAudioPlayer();
                LyricsRenderer lyricsRenderer = clientMod.getLyricsRenderer();

                // 设置歌词
                lyricsRenderer.setLyrics(title, artist, lyrics, finalHasTranslation);

                // 播放音频
                if (playUrl != null && !playUrl.isEmpty()) {
                    audioPlayer.play(playUrl, title, artist, duration);
                    logger.info("开始播放: {} - {}", title, artist);
                } else {
                    logger.warn("没有可用的播放URL");
                }
            });
        } catch (Exception e) {
            logger.error("处理播放包失败: " + e.getMessage(), e);
        }
    }

    /**
     * 处理停止包
     */
    private void handleStop(MinecraftClient client) {
        client.execute(() -> {
            AudioPlayer audioPlayer = clientMod.getAudioPlayer();
            LyricsRenderer lyricsRenderer = clientMod.getLyricsRenderer();

            audioPlayer.stop();
            lyricsRenderer.clear();
            logger.info("播放已停止");
        });
    }

    /**
     * 处理搜索结果包 (0x09)：解析后交给搜索界面展示
     */
    private void handleSearchResult(byte[] data, MinecraftClient client) {
        try {
            int offset = 1;
            int count = readInt(data, offset);
            offset += 4;

            List<SearchScreen.SearchEntry> list = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String source = readString(data, offset);
                offset += 2 + source.getBytes(StandardCharsets.UTF_8).length;
                String songId = readString(data, offset);
                offset += 2 + songId.getBytes(StandardCharsets.UTF_8).length;
                String title = readString(data, offset);
                offset += 2 + title.getBytes(StandardCharsets.UTF_8).length;
                String artist = readString(data, offset);
                offset += 2 + artist.getBytes(StandardCharsets.UTF_8).length;
                long duration = readLong(data, offset);
                offset += 8;
                int pages = readInt(data, offset);
                offset += 4;
                list.add(new SearchScreen.SearchEntry(source, songId, title, artist, duration, pages));
            }
            List<SearchScreen.SearchEntry> finalList = list;
            client.execute(() -> SearchScreen.onResults(finalList));
        } catch (Exception e) {
            logger.warn("解析搜索结果失败: " + e.getMessage());
        }
    }

    /**
     * 处理暂停包
     */
    private void handlePause(MinecraftClient client) {
        client.execute(() -> {
            clientMod.getAudioPlayer().pause();
            logger.info("播放已暂停");
        });
    }

    /**
     * 处理恢复包
     */
    private void handleResume(MinecraftClient client) {
        client.execute(() -> {
            clientMod.getAudioPlayer().resume();
            logger.info("播放已恢复");
        });
    }

    /**
     * 处理队列同步包 (0x08)
     * 格式: hasCurrent(1) + playing(1) + [current: songId/title/artist/source/requesterName]
     *       + historySize(4) + N * [songId/title/artist/source/requesterName]   (已播放, 最近优先)
     *       + queueSize(4) + M * [songId/title/artist/source/requesterName]     (等待队列)
     */
    private void handleQueueSync(byte[] data, MinecraftClient client) {
        try {
            int offset = 1;

            boolean hasCurrent = readBoolean(data, offset);
            offset += 1;
            boolean playing = readBoolean(data, offset);
            offset += 1;
            // 注意：paused 与服务端 PluginChannel.buildQueueSyncData() 同步新增，两端需一起部署
            boolean paused = readBoolean(data, offset);
            offset += 1;

            QueueState.Song nowPlaying = null;
            if (hasCurrent) {
                String songId = readString(data, offset);
                offset += 2 + songId.getBytes(StandardCharsets.UTF_8).length;
                String title = readString(data, offset);
                offset += 2 + title.getBytes(StandardCharsets.UTF_8).length;
                String artist = readString(data, offset);
                offset += 2 + artist.getBytes(StandardCharsets.UTF_8).length;
                String source = readString(data, offset);
                offset += 2 + source.getBytes(StandardCharsets.UTF_8).length;
                String requester = readString(data, offset);
                offset += 2 + requester.getBytes(StandardCharsets.UTF_8).length;
                nowPlaying = new QueueState.Song(songId, title, artist, source, requester);
            }

            int historySize = readInt(data, offset);
            offset += 4;
            List<QueueState.Song> history = new ArrayList<>(Math.max(0, historySize));
            for (int i = 0; i < historySize; i++) {
                String songId = readString(data, offset);
                offset += 2 + songId.getBytes(StandardCharsets.UTF_8).length;
                String title = readString(data, offset);
                offset += 2 + title.getBytes(StandardCharsets.UTF_8).length;
                String artist = readString(data, offset);
                offset += 2 + artist.getBytes(StandardCharsets.UTF_8).length;
                String source = readString(data, offset);
                offset += 2 + source.getBytes(StandardCharsets.UTF_8).length;
                String requester = readString(data, offset);
                offset += 2 + requester.getBytes(StandardCharsets.UTF_8).length;
                history.add(new QueueState.Song(songId, title, artist, source, requester));
            }

            int queueSize = readInt(data, offset);
            offset += 4;
            List<QueueState.Song> queue = new ArrayList<>(Math.max(0, queueSize));
            for (int i = 0; i < queueSize; i++) {
                String songId = readString(data, offset);
                offset += 2 + songId.getBytes(StandardCharsets.UTF_8).length;
                String title = readString(data, offset);
                offset += 2 + title.getBytes(StandardCharsets.UTF_8).length;
                String artist = readString(data, offset);
                offset += 2 + artist.getBytes(StandardCharsets.UTF_8).length;
                String source = readString(data, offset);
                offset += 2 + source.getBytes(StandardCharsets.UTF_8).length;
                String requester = readString(data, offset);
                offset += 2 + requester.getBytes(StandardCharsets.UTF_8).length;
                queue.add(new QueueState.Song(songId, title, artist, source, requester));
            }

            final QueueState.Song finalNowPlaying = nowPlaying;
            final boolean finalPaused = paused;
            final List<QueueState.Song> finalHistory = history;
            final List<QueueState.Song> finalQueue = queue;

            // 在主线程中更新快照
            client.execute(() -> {
                QueueState.update(finalNowPlaying, playing, finalPaused, finalHistory, finalQueue);
                logger.info("队列同步: 当前播放={}, 已播放 {} 首, 等待队列 {} 首",
                        finalNowPlaying == null ? "无" : finalNowPlaying.title(), finalHistory.size(), finalQueue.size());
            });
        } catch (Exception e) {
            logger.error("处理队列同步包失败: " + e.getMessage(), e);
        }
    }

    /**
     * 处理缓存设置包 (0x0D)
     * 格式: enabled(1) + maxSizeMb(4)；来自服务端 config.yml 的 client-cache 段
     */
    private void handleCacheConfig(byte[] data, MinecraftClient client) {
        try {
            boolean enabled = readBoolean(data, 1);
            int maxSizeMb = readInt(data, 2);
            client.execute(() -> {
                AudioPlayer audioPlayer = clientMod.getAudioPlayer();
                if (audioPlayer != null) {
                    audioPlayer.applyCacheConfig(enabled, maxSizeMb);
                }
            });
        } catch (Exception e) {
            logger.error("处理缓存设置包失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取布尔值
     */
    private boolean readBoolean(byte[] data, int offset) {
        if (offset >= data.length) return false;
        return data[offset] != 0;
    }

    /**
     * 读取字符串
     */
    private String readString(byte[] data, int offset) {
        if (offset + 2 > data.length) return "";

        int length = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        if (offset + 2 + length > data.length) return "";

        return new String(data, offset + 2, length, StandardCharsets.UTF_8);
    }

    /**
     * 读取长整型
     */
    private long readLong(byte[] data, int offset) {
        if (offset + 8 > data.length) return 0;

        return ((long) (data[offset] & 0xFF) << 56) |
                ((long) (data[offset + 1] & 0xFF) << 48) |
                ((long) (data[offset + 2] & 0xFF) << 40) |
                ((long) (data[offset + 3] & 0xFF) << 32) |
                ((long) (data[offset + 4] & 0xFF) << 24) |
                ((long) (data[offset + 5] & 0xFF) << 16) |
                ((long) (data[offset + 6] & 0xFF) << 8) |
                ((long) (data[offset + 7] & 0xFF));
    }

    /**
     * 读取整型
     */
    private int readInt(byte[] data, int offset) {
        if (offset + 4 > data.length) return 0;

        return ((data[offset] & 0xFF) << 24) |
                ((data[offset + 1] & 0xFF) << 16) |
                ((data[offset + 2] & 0xFF) << 8) |
                ((data[offset + 3] & 0xFF));
    }

    /**
     * 自定义Payload
     */
    public record AllMusicPayload(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<AllMusicPayload> ID = new CustomPayload.Id<>(CHANNEL_ID);
        public static final PacketCodec<PacketByteBuf, AllMusicPayload> CODEC = PacketCodec.of(
                (value, buf) -> {
                    // 直接写入原始字节（Bukkit发送格式）
                    buf.writeBytes(value.data());
                },
                (buf) -> {
                    // 直接读取所有剩余字节（Bukkit发送格式，无长度前缀）
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    return new AllMusicPayload(data);
                }
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
