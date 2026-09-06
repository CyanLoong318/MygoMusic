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
                    handleSongFinished(player);
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
        if (queueScheduler.getPlayQueue().isPlaying()) {
            SongDetail detail = queueScheduler.getPlayQueue().getCurrentSongDetail();
            if (detail != null) {
                sendPlayToPlayer(player, detail);
            }
        }
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
     */
    private void handleSongFinished(Player player) {
        try {
            if (queueScheduler == null) return;
            PlayQueue playQueue = queueScheduler.getPlayQueue();
            if (playQueue == null || !playQueue.isPlaying()) return;
            logger.info("客户端确认歌曲播放完毕(来自 {})，自动切下一首", player.getName());
            // 标记当前结束，触发下一首（picking 原子锁防止与定时器重复）
            queueScheduler.onSongFinishedByClient();
        } catch (Exception e) {
            logger.error("处理客户端播放完成信号失败: " + e.getMessage(), e);
        }
    }

    /**
     * 广播播放歌曲
     */
    public void broadcastPlay(SongDetail detail) {
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

        // 广播给所有玩家
        byte[] data = output.toByteArray();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline()) {
                sendPluginMessage(player, data);
            }
        }
    }

    /**
     * 发送播放信息给指定玩家
     */
    public void sendPlayToPlayer(Player player, SongDetail detail) {
        if (!player.isOnline()) return;

        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeByte(PACKET_PLAY);

        writeString(output, detail.getTitle());
        writeString(output, detail.getArtist());
        output.writeLong(detail.getDuration());
        writeString(output, detail.getSource());
        writeString(output, detail.getAudioUrl() != null ? detail.getAudioUrl() : "");
        writeString(output, detail.getLocalPath() != null ? detail.getLocalPath() : "");

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

        sendPluginMessage(player, output.toByteArray());
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
     * hasCurrent(1) + playing(1)
     * + [current: songId/title/artist/source/requesterName]
     * + historySize(4) + N * [songId/title/artist/source/requesterName]   (已播放, 最近优先)
     * + queueSize(4) + M * [songId/title/artist/source/requesterName]     (等待队列)
     */
    private byte[] buildQueueSyncData() {
        PlayQueue playQueue = plugin.getPlayQueue();
        if (playQueue == null) return null;

        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeByte(PACKET_QUEUE_SYNC);

        QueueItem current = playQueue.getCurrentPlaying();
        output.writeBoolean(current != null);
        output.writeBoolean(playQueue.isPlaying());
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
