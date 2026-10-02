package com.allmusic.network;

import com.allmusic.AllMusicPlugin;
import com.allmusic.config.ConfigManager;
import com.allmusic.model.Lyrics;
import com.allmusic.model.LyricsLine;
import com.allmusic.model.QueueItem;
import com.allmusic.model.SongDetail;
import com.allmusic.model.SongInfo;
import com.allmusic.queue.PlayQueue;
import com.allmusic.queue.QueueScheduler;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Plugin Messaging 通道
 */
public class PluginChannel implements PluginMessageListener {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-Channel");

    // 包类型
    public static final byte PACKET_PLAY = 0x01;
    public static final byte PACKET_STOP = 0x02;
    public static final byte PACKET_PAUSE = 0x03;
    public static final byte PACKET_RESUME = 0x04;
    public static final byte PACKET_VOLUME = 0x05;
    public static final byte PACKET_SYNC = 0x06;
    public static final byte PACKET_LYRICS = 0x07;
    public static final byte PACKET_QUEUE_SYNC = 0x08;
    public static final byte PACKET_SEARCH_RESULT = 0x09;
    public static final byte PACKET_LOGIN_URL = 0x0A;
    public static final byte PACKET_CONFIG_SYNC = 0x0B;
    // 客户端 → 服务端：当前歌曲已播放完毕（自动请求下一首）
    public static final byte PACKET_SONGFINISHED = 0x0C;
    // 服务端 → 客户端：客户端音频缓存设置（enabled + max-size-mb，由服务端统一控制）
    public static final byte PACKET_CACHE_CONFIG = 0x0D;
    // 客户端 → 服务端：请求一次队列状态同步（GUI 打开时主动拉取最新播放/暂停状态）
    public static final byte PACKET_REQUEST_SYNC = 0x0E;
    // 服务端 → 客户端：设置客户端音量（0-100）。音频在客户端播放，只能靠这个包改音量
    public static final byte PACKET_SET_VOLUME = 0x0F;

    private final AllMusicPlugin plugin;
    private final QueueScheduler queueScheduler;

    public PluginChannel(AllMusicPlugin plugin, QueueScheduler queueScheduler) {
        this.plugin = plugin;
        this.queueScheduler = queueScheduler;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte[] message) {
        if (!channel.equals("allmusic:main")) {
            return;
        }

        try {
            ByteArrayDataInput input = ByteStreams.newDataInput(message);
            byte packetType = input.readByte();

            switch (packetType) {
                case PACKET_VOLUME:
                    handleVolume(player, input);
                    break;
                case PACKET_SYNC:
                    handleSync(player, input);
                    break;
                case PACKET_SONGFINISHED:
                    // 新客户端在包类型后带 8 字节 playbackId（共 9 字节）；旧客户端只有 1 字节
                    handleSongFinished(player, message.length >= 9 ? input.readLong() : -1L);
                    break;
                case PACKET_REQUEST_SYNC:
                    // GUI 打开时主动请求一次最新队列状态（含播放/暂停标志）
                    sendQueueSync(player);
                    break;
                default:
                    logger.warn("未知的包类型: {}", packetType);
            }
        } catch (Exception e) {
            logger.error("处理Plugin消息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 处理音量调整
     */
    private void handleVolume(Player player, ByteArrayDataInput input) {
        int volume = input.readInt();
        // 客户端自行处理音量，这里可以记录日志
        logger.debug("玩家 {} 调整音量为: {}", player.getName(), volume);
    }

    /**
     * 处理同步请求
     */
    private void handleSync(Player player, ByteArrayDataInput input) {
        // 玩家切服时，发送当前播放状态
        sendCurrentPlay(player);
        // 同步客户端缓存设置（加入时客户端也会收到一次，这里兜底再推一次）
        sendCacheConfig(player);
    }

    /**
     * 发送客户端音频缓存设置给指定玩家（0x0D: enabled(1) + maxSizeMb(4)）
     */
    public void sendCacheConfig(Player player) {
        if (player == null || !player.isOnline()) return;
        try {
            ConfigManager configManager = plugin.getConfigManager();
            ByteArrayDataOutput output = ByteStreams.newDataOutput();
            output.writeByte(PACKET_CACHE_CONFIG);
            output.writeBoolean(configManager.isClientCacheEnabled());
            output.writeInt(configManager.getClientCacheMaxSizeMb());
            sendPluginMessage(player, output.toByteArray());
        } catch (Exception e) {
            logger.error("发送缓存设置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送音量设置给指定玩家（0x0F: volume(4)）
     */
    public void sendVolume(Player player, int volume) {
        if (player == null || !player.isOnline()) return;
        try {
            ByteArrayDataOutput output = ByteStreams.newDataOutput();
            output.writeByte(PACKET_SET_VOLUME);
            output.writeInt(Math.max(0, Math.min(100, volume)));
            sendPluginMessage(player, output.toByteArray());
        } catch (Exception e) {
            logger.error("发送音量设置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送歌词显示开关给指定玩家（0x07: mode(1)，0=切换 1=显示 2=隐藏）
     */
    public void sendLyricsToggle(Player player, int mode) {
        if (player == null || !player.isOnline()) return;
        try {
            ByteArrayDataOutput output = ByteStreams.newDataOutput();
            output.writeByte(PACKET_LYRICS);
            output.writeByte(mode);
            sendPluginMessage(player, output.toByteArray());
        } catch (Exception e) {
            logger.error("发送歌词开关失败: " + e.getMessage(), e);
        }
    }

    /**
     * 广播客户端音频缓存设置到所有在线玩家（配置重载后调用）
     */
    public void broadcastCacheConfig() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline()) {
                sendCacheConfig(player);
            }
        }
    }

    /**
     * 客户端确认当前歌曲已播放完毕 → 切下一首（服务端定时器作兜底）
     *
     * @param playbackId 客户端回带的播放代次（-1 = 旧客户端未带），用于丢弃迟到的旧信号
     */
    private void handleSongFinished(Player player, long playbackId) {
        try {
            if (queueScheduler == null) return;
            PlayQueue playQueue = queueScheduler.getPlayQueue();
            if (playQueue == null || !playQueue.isPlaying()) return;
            // 全服暂停期间忽略“已播完”信号，避免暂停时迟到 EOF 跳歌
            if (playQueue.isPaused()) return;
            logger.info("客户端确认歌曲播放完毕(来自 {})，自动切下一首", player.getName());
            // 标记当前结束，触发下一首（picking 原子锁防止与定时器重复）
            queueScheduler.onSongFinishedByClient(playbackId);
        } catch (Exception e) {
            logger.error("处理客户端播放完成信号失败: " + e.getMessage(), e);
        }
    }

    /**
     * 广播播放歌曲（新歌开始，各客户端都从头正常播放）
     */
    public void broadcastPlay(SongDetail detail) {
        byte[] data = buildPlayData(detail, false);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline()) {
                sendPluginMessage(player, data);
            }
        }
    }

    /**
     * 把「当前正在播放的歌」补发给某个玩家：玩家中途进服、或从别的子服切过来时，
     * 他错过了 PLAY 广播，不补发就听不到这首歌也不会显示歌词。
     * joinInProgress=true：客户端从头播放，但不回发「已播完」信号
     * （它是中途加入的，播完的时刻比全服晚，回发会把这首歌提前切掉）。
     */
    public void sendCurrentPlay(Player player) {
        if (player == null || !player.isOnline()) return;
        if (queueScheduler == null) return;
        PlayQueue playQueue = queueScheduler.getPlayQueue();
        if (playQueue == null || !playQueue.isPlaying()) return;
        SongDetail detail = playQueue.getCurrentSongDetail();
        if (detail == null) return;
        sendPluginMessage(player, buildPlayData(detail, true));
        if (playQueue.isPaused()) {
            // 全服暂停中进服：必须补一个 PAUSE 包。否则该客户端收到 PLAY 就从头出声，
            // 全服都在暂停、只有他一个人在响（QUEUE_SYNC 里的 paused 只用于界面显示，不会暂停音频）
            sendPluginMessage(player, new byte[]{PACKET_PAUSE});
        }
    }

    /**
     * 构建 PLAY 包 (0x01)：歌曲信息 + 歌词 + 追加字段。
     * 追加字段放在末尾（老客户端读到歌词就结束解析，会自然忽略；新客户端读不到则按默认值处理）：
     *   playbackId(8)    —— 本首歌的播放代次，「已播完」回执原样带回，服务端据此丢弃迟到信号
     *   joinInProgress(1)—— 该客户端是中途加入（补发），不要回发「已播完」
     *   songId(str)      —— 供客户端 GUI 核对「这首是不是我刚点的那首」
     */
    private byte[] buildPlayData(SongDetail detail, boolean joinInProgress) {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeByte(PACKET_PLAY);

        // 写入歌曲信息
        writeString(output, detail.getTitle());
        writeString(output, detail.getArtist());
        output.writeLong(detail.getDuration());
        writeString(output, detail.getSource());
        writeString(output, detail.getAudioUrl() != null ? detail.getAudioUrl() : "");
        writeString(output, detail.getLocalPath() != null ? detail.getLocalPath() : "");

        // 写入歌词
        Lyrics lyrics = detail.getLyrics();
        if (lyrics != null && !lyrics.isEmpty()) {
            List<LyricsLine> lines = lyrics.getLines();
            output.writeInt(lines.size());
            for (LyricsLine line : lines) {
                output.writeLong(line.getTimestamp());
                writeString(output, line.getText());
                writeString(output, line.hasTranslation() ? line.getTranslation() : "");
            }
        } else {
            output.writeInt(0);
        }

        // 追加字段（见方法注释）
        PlayQueue playQueue = queueScheduler != null ? queueScheduler.getPlayQueue() : null;
        output.writeLong(playQueue != null ? playQueue.getPlaybackId() : -1L);
        output.writeBoolean(joinInProgress);
        writeString(output, detail.getSongId() != null ? detail.getSongId() : "");

        return output.toByteArray();
    }

    /**
     * 广播停止播放
     */
    public void broadcastStop() {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeByte(PACKET_STOP);

        byte[] data = output.toByteArray();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline()) {
                sendPluginMessage(player, data);
            }
        }
    }

    /**
     * 广播暂停
     */
    public void broadcastPause() {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeByte(PACKET_PAUSE);

        byte[] data = output.toByteArray();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline()) {
                sendPluginMessage(player, data);
            }
        }
    }

    /**
     * 广播恢复
     */
    public void broadcastResume() {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeByte(PACKET_RESUME);

        byte[] data = output.toByteArray();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline()) {
                sendPluginMessage(player, data);
            }
        }
    }

    /**
     * 广播队列状态到所有在线玩家
     */
    public void broadcastQueueSync() {
        byte[] data = buildQueueSyncData();
        if (data == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline()) {
                sendPluginMessage(player, data);
            }
        }
    }

    /**
     * 发送队列状态给指定玩家
     */
    public void sendQueueSync(Player player) {
        if (!player.isOnline()) return;

        byte[] data = buildQueueSyncData();
        if (data == null) return;

        sendPluginMessage(player, data);
    }

    /**
     * 构建队列同步数据包 (0x08):
     * hasCurrent(1) + playing(1) + paused(1)
     * + [current: songId/title/artist/source/requesterName]
     * + historySize(4) + N * [songId/title/artist/source/requesterName]   (已播放, 最近优先)
     * + queueSize(4) + M * [songId/title/artist/source/requesterName]     (等待队列)
     *
     * 注意：paused(1) 必须与客户端 ChannelHandler.handleQueueSync() 同步修改，两端一起部署。
     */
    private byte[] buildQueueSyncData() {
        PlayQueue playQueue = plugin.getPlayQueue();
        if (playQueue == null) return null;

        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeByte(PACKET_QUEUE_SYNC);

        QueueItem current = playQueue.getCurrentPlaying();
        output.writeBoolean(current != null);
        output.writeBoolean(playQueue.isPlaying());
        output.writeBoolean(playQueue.isPaused());
        if (current != null) {
            writeQueueItem(output, current);
        }

        List<QueueItem> history = playQueue.getHistorySnapshot();
        output.writeInt(history.size());
        for (QueueItem item : history) {
            writeQueueItem(output, item);
        }

        List<QueueItem> queue = playQueue.getSnapshot();
        output.writeInt(queue.size());
        for (QueueItem item : queue) {
            writeQueueItem(output, item);
        }

        return output.toByteArray();
    }

    /**
     * 写入队列项
     */
    private void writeQueueItem(ByteArrayDataOutput output, QueueItem item) {
        writeString(output, item.getSongId());
        writeString(output, item.getTitle());
        writeString(output, item.getArtist());
        writeString(output, item.getSource());
        writeString(output, item.getRequesterName());
    }

    /**
     * 发送Plugin消息
     */
    private void sendPluginMessage(Player player, byte[] data) {
        try {
            player.sendPluginMessage(plugin, "allmusic:main", data);
        } catch (Exception e) {
            logger.error("发送Plugin消息失败: " + e.getMessage());
        }
    }

    /**
     * 写入字符串
     */
    /**
     * 发送搜索结果给指定玩家（GUI 搜索界面直接展示）
     */
    public void sendSearchResult(Player player, List<SongInfo> results) {
        if (player == null || !player.isOnline()) return;
        try {
            ByteArrayDataOutput output = ByteStreams.newDataOutput();
            output.writeByte(PACKET_SEARCH_RESULT);
            output.writeInt(results == null ? 0 : results.size());
            if (results != null) {
                for (SongInfo s : results) {
                    writeString(output, s.getSource());
                    writeString(output, s.getSongId() != null ? s.getSongId() : "");
                    writeString(output, s.getTitle());
                    writeString(output, s.getArtist());
                    output.writeLong(s.getDuration());
                    output.writeInt(parsePages(s.getExtra()));
                }
            }
            sendPluginMessage(player, output.toByteArray());
        } catch (Exception e) {
            logger.error("发送搜索结果失败: " + e.getMessage(), e);
        }
    }

    private int parsePages(String extra) {
        if (extra == null || extra.isEmpty()) return 1;
        try {
            int n = Integer.parseInt(extra.trim());
            return n > 1 ? n : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private void writeString(ByteArrayDataOutput output, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        output.writeShort(bytes.length);
        output.write(bytes);
    }
}
