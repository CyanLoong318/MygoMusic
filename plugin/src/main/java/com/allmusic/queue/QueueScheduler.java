package com.allmusic.queue;

import com.allmusic.AllMusicPlugin;
import com.allmusic.config.ConfigManager;
import com.allmusic.model.QueueItem;
import com.allmusic.model.SongDetail;
import com.allmusic.network.PluginChannel;
import com.allmusic.source.MusicSource;
import com.allmusic.source.SourceManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 队列调度器
 */
public class QueueScheduler {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-Scheduler");

    private final AllMusicPlugin plugin;
    private final PlayQueue playQueue;
    private final SourceManager sourceManager;
    private final ConfigManager configManager;

    private BukkitTask schedulerTask;
    private boolean running = false;

    public QueueScheduler(AllMusicPlugin plugin, PlayQueue playQueue, SourceManager sourceManager) {
        this.plugin = plugin;
        this.playQueue = playQueue;
        this.sourceManager = sourceManager;
        this.configManager = plugin.getConfigManager();
    }

    /**
     * 启动调度器
     */
    public void start() {
        if (running) return;
        running = true;

        schedulerTask = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 20L, 20L); // 每秒执行一次

        logger.info("队列调度器已启动");
    }

    /**
     * 停止调度器
     */
    public void shutdown() {
        running = false;
        if (schedulerTask != null) {
            schedulerTask.cancel();
        }
        logger.info("队列调度器已停止");
    }

    /**
     * 主循环
     */
    private void tick() {
        // 全服暂停：不检查完成、不自动切歌（暂停时长不计入歌曲进度）
        if (playQueue.isPaused()) {
            return;
        }
        if (!playQueue.isPlaying()) {
            // 没有正在播放的歌曲，尝试从队列取下一首
            if (configManager.isAutoPlay() && !playQueue.isEmpty()) {
                playNext();
            }
        } else {
            // 检查当前歌曲是否播放完毕
            if (playQueue.isPlaybackFinished()) {
                logger.info("歌曲播放完毕");
                playQueue.stop();

                // 同步队列状态到客户端 GUI
                PluginChannel channel = plugin.getPluginChannel();
                if (channel != null) {
                    channel.broadcastQueueSync();
                }

                // 自动播放下一首
                if (configManager.isAutoPlay() && !playQueue.isEmpty()) {
                    playNext();
                }
            }
        }
    }

    /** 是否有一次"取歌→获取详情→开始播放"流程正在进行中（防止重复取歌） */
    private final AtomicBoolean picking = new AtomicBoolean(false);

    /**
     * 客户端确认当前歌曲播放完毕时调用：停止当前并切下一首。
     * 与定时器完成路径一致；picking 原子锁防止二者并发重复。
     */
    public void onSongFinishedByClient() {
        // 全服暂停期间不收“已播完”信号，避免迟到 EOF 跳歌
        if (playQueue.isPaused()) {
            return;
        }
        if (playQueue.isPlaying()) {
            playQueue.stop();
        }
        playNext();
    }

    /**
     * 播放下一首
     */
    public boolean playNext() {
        if (!picking.compareAndSet(false, true)) {
            logger.debug("正在切换歌曲中，忽略本次 playNext 调用");
            return false;
        }
        QueueItem item = playQueue.poll();
        if (item == null) {
            picking.set(false);
            return false;
        }
        startFetch(item, true);
        return true;
    }

    /**
     * 播放上一首 (从历史中取)
     */
    public boolean playPrevious() {
        if (!picking.compareAndSet(false, true)) {
            return false;
        }
        QueueItem item = playQueue.getPrevious();
        if (item == null) {
            picking.set(false);
            return false;
        }
        // 播放"上一首"失败时不自动跳队列，避免打断正在播放的歌曲
        startFetch(item, false);
        return true;
    }

    /**
     * 异步获取歌曲详情并播放。
     *
     * @param skipOnFail 当歌曲无法获取到可播放 URL 时，是否自动尝试队列中的下一首
     */
    private void startFetch(QueueItem item, boolean skipOnFail) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                MusicSource source = sourceManager.getSource(item.getSource());
                if (source == null) {
                    handleFetchFailure(item, "找不到音源: " + item.getSource(), skipOnFail);
                    return;
                }

                SongDetail detail = source.getDetail(item.getSongId());
                if (detail == null) {
                    handleFetchFailure(item, "获取歌曲失败: " + item.getTitle(), skipOnFail);
                    return;
                }

                // 歌曲信息缺失时使用队列中的信息
                if (detail.getTitle() == null || detail.getTitle().isEmpty()) {
                    detail = new SongDetail(
                            detail.getSongId(),
                            item.getTitle(),
                            item.getArtist(),
                            detail.getAlbum(),
                            detail.getDuration(),
                            detail.getSource(),
                            detail.getCoverUrl(),
                            detail.getExtra(),
                            detail.getAudioUrl(),
                            detail.getLyrics(),
                            detail.isVip(),
                            detail.getLocalPath()
                    );
                }

                // 检查是否能拿到可播放的音频（audioUrl 或本地转码文件）
                boolean hasAudio = (detail.getAudioUrl() != null && !detail.getAudioUrl().isEmpty())
                        || (detail.getLocalPath() != null && !detail.getLocalPath().isEmpty());
                if (!hasAudio) {
                    logger.error("歌曲audioUrl为空，无法播放: {} - {}", detail.getTitle(), detail.getArtist());
                    handleFetchFailure(item, "获取音频URL失败: " + detail.getTitle(), skipOnFail);
                    return;
                }

                logger.info("获取歌曲详情成功: {} - {} (audioUrl={})", detail.getTitle(), detail.getArtist(),
                        detail.getAudioUrl() != null && !detail.getAudioUrl().isEmpty()
                                ? detail.getAudioUrl().substring(0, Math.min(50, detail.getAudioUrl().length())) + "..."
                                : detail.getLocalPath());

                final SongDetail finalDetail = detail;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // 流程结束，允许下一次取歌
                    picking.set(false);
                    playSongDetail(item, finalDetail);
                });
            } catch (Exception e) {
                logger.error("播放歌曲失败: " + e.getMessage(), e);
                handleFetchFailure(item, "播放失败: " + item.getTitle(), skipOnFail);
            }
        });
    }

    /**
     * 歌曲无法播放时的处理：提示点歌人；若需要则自动尝试下一首（跳过无法播放的歌曲）
     */
    private void handleFetchFailure(QueueItem item, String reason, boolean skipOnFail) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player requester = Bukkit.getPlayer(item.getRequesterUuid());
            if (requester != null) {
                requester.sendMessage("§c[MygoMusic] " + reason);
            }
            logger.error("[MygoMusic] {} ，已移除该歌曲", reason);

            if (!skipOnFail) {
                // 播放"上一首"等显式操作失败：不抢队列，结束本次流程
                picking.set(false);
                return;
            }

            // 跳过取不到 URL 的歌，继续尝试下一首
            QueueItem next = playQueue.poll();
            if (next == null) {
                picking.set(false);
                PluginChannel channel = plugin.getPluginChannel();
                if (channel != null) {
                    channel.broadcastQueueSync();
                }
                return;
            }
            startFetch(next, true);
        });
    }

    /**
     * 播放歌曲详情
     */
    private void playSongDetail(QueueItem item, SongDetail detail) {
        // 设置当前播放
        playQueue.setCurrentPlaying(item, detail);

        // 通知所有在线玩家
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline()) {
                player.sendMessage("§6[MygoMusic] §e正在播放: §f" + detail.getTitle() + " §7- §f" + detail.getArtist() +
                        " §7(" + detail.getSource() + ") §7[§a" + item.getRequesterName() + "§7]");
            }
        }

        // 发送给客户端
        PluginChannel pluginChannel = plugin.getPluginChannel();
        if (pluginChannel != null) {
            pluginChannel.broadcastPlay(detail);
            // 同步队列状态（当前播放 + 等待队列）到客户端 GUI
            pluginChannel.broadcastQueueSync();
        }

        logger.info("正在播放: {} - {} ({})", detail.getTitle(), detail.getArtist(), detail.getSource());
    }

    /**
     * 停止当前播放
     */
    public void stopCurrent() {
        playQueue.stop();

        // 通知所有玩家停止播放
        PluginChannel pluginChannel = plugin.getPluginChannel();
        if (pluginChannel != null) {
            pluginChannel.broadcastStop();
            // 同步队列状态（当前播放可能仍保留上一首，队列变化需同步）
            pluginChannel.broadcastQueueSync();
        }

        // 通知在线玩家
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline()) {
                player.sendMessage("§6[MygoMusic] §e播放已停止，输入 §f/mm continue §e继续");
            }
        }
    }

    /**
     * 继续播放
     */
    public void continuePlay() {
        if (playQueue.isPlaying()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOnline()) {
                    player.sendMessage("§6[MygoMusic] §e当前正在播放中");
                }
            }
            return;
        }

        if (playQueue.isEmpty()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOnline()) {
                    player.sendMessage("§6[MygoMusic] §e队列为空");
                }
            }
            return;
        }

        playNext();
    }

    /**
     * 跳过当前歌曲
     */
    public void skipCurrent() {
        stopCurrent();
        playNext();
    }

    /**
     * 获取播放队列
     */
    public PlayQueue getPlayQueue() {
        return playQueue;
    }

    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        return running;
    }
}
