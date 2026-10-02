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
import java.util.concurrent.atomic.AtomicLong;

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
        // 取歌看门狗：即使暂停/播放中也要检查，任何一次取歌卡住都不能让队列无限期停摆
        checkFetchWatchdog();

        // 全服暂停：不检查完成、不自动切歌（暂停时长不计入歌曲进度）
        if (playQueue.isPaused()) {
            return;
        }
        if (!playQueue.isPlaying()) {
            // 没有正在播放的歌曲，尝试从队列取下一首。
            // 但「用户主动 /mm stop」之后必须真的停住：旧行为是照常续播，
            // 于是 /mm stop 一秒后自动开下一首，实际效果等于 /mm next。
            if (!autoAdvanceSuspended && configManager.isAutoPlay() && !playQueue.isEmpty()) {
                playNext();
            }
        } else {
            // 检查当前歌曲是否播放完毕（带宽容，避免服务端时钟比客户端实际出声快而削掉歌尾）
            if (playQueue.isPlaybackFinished(PlayQueue.SERVER_CLOCK_GRACE_MS)) {
                completeCurrentSong();
            }
        }
    }

    /**
     * 当前歌曲正常播完：停播 + 同步 GUI；是否自动续播由 queue.auto-play 决定。
     * 定时器（服务端按时长判断）与客户端「已播完」信号共用这一条收尾路径，
     * 保证两条路的行为一致 —— 尤其是 auto-play=false 时两条路都不许自动开下一首。
     */
    private void completeCurrentSong() {
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

    /**
     * 用户主动停止后置位：抑制 tick 的「队列非空就自动开下一首」。
     * 任何显式的开始播放动作（/mm continue、next、prev、点歌）都会解除它。
     */
    private volatile boolean autoAdvanceSuspended = false;

    /** 是否有一次"取歌→获取详情→开始播放"流程正在进行中（防止重复取歌） */
    private final AtomicBoolean picking = new AtomicBoolean(false);

    /** 取歌代次号：每次开始取歌自增。迟到完成的旧任务凭它识别并丢弃，绝不劫持已经切走的新播放 */
    private final AtomicLong fetchEpoch = new AtomicLong(0);
    /** 当前在途的取歌任务；null=空闲。只在服务器主线程读写 */
    private volatile FetchState activeFetch;

    /**
     * 一次取歌任务。epoch 标识代次；done 保证「看门狗超时放弃」与「异步结果正常返回」
     * 只有一方能收尾，另一方的迟到动作会被静默丢弃。
     */
    private static final class FetchState {
        final long epoch;
        final QueueItem item;
        final boolean skipOnFail;
        final long startedAtMs = System.currentTimeMillis();
        final AtomicBoolean done = new AtomicBoolean(false);

        FetchState(long epoch, QueueItem item, boolean skipOnFail) {
            this.epoch = epoch;
            this.item = item;
            this.skipOnFail = skipOnFail;
        }
    }

    /**
     * 取歌看门狗：超过阈值仍没有结果就放弃这首歌、释放取歌锁、跳到下一首。
     * 事故复盘：一次「服务端下载+转码」卡满 300 秒，picking 一直被占，整队 5 分钟一首不放。
     */
    private void checkFetchWatchdog() {
        FetchState state = activeFetch;
        if (state == null || state.done.get()) {
            return;
        }
        long timeoutMs = fetchTimeoutMs(state.item);
        if (timeoutMs <= 0) {
            return; // 看门狗被配置关闭
        }
        long elapsedMs = System.currentTimeMillis() - state.startedAtMs;
        if (elapsedMs < timeoutMs) {
            return;
        }
        if (!state.done.compareAndSet(false, true)) {
            return; // 异步结果已经抢先收尾
        }
        activeFetch = null;
        logger.warn("取歌超时({}秒)，放弃并继续队列: {} - {} ({})", elapsedMs / 1000,
                state.item.getTitle(), state.item.getArtist(), state.item.getSource());
        handleFetchFailure(state, "获取歌曲信息超时(" + elapsedMs / 1000 + "秒)，已跳过: " + state.item.getTitle());
    }

    /**
     * 当前这次取歌适用的看门狗超时(毫秒)，&lt;=0 表示关闭。
     * 直链/网易云/酷狗等解析 URL 应当秒级完成，用短阈值快速失败；
     * B站 transcode 模式、以及任何正在跑「下载/转码」慢路径的取歌用宽容阈值，避免误杀正常的长转码。
     */
    private long fetchTimeoutMs(QueueItem item) {
        boolean slow = "bilibili".equalsIgnoreCase(item.getSource())
                && "transcode".equalsIgnoreCase(configManager.getBilibiliPlayMode());
        if (!slow) {
            MusicSource source = sourceManager.getSource(item.getSource());
            slow = source != null && source.isInSlowPath();
        }
        int seconds = slow ? configManager.getTranscodeFetchTimeoutSeconds()
                : configManager.getFetchTimeoutSeconds();
        return seconds > 0 ? seconds * 1000L : -1L;
    }

    /**
     * 客户端确认当前歌曲播放完毕时调用：停止当前并按 auto-play 决定是否切下一首。
     *
     * @param playbackId 客户端回带的服务端播放代次。PLAY 是广播包，全服每个装了 Mod 的客户端
     *                   都会在各自听完整首后回一个「已播完」；若这时全服已经切到下一首
     *                   （服务端按长度先切、或别的客户端先发、或刚有人 /mm next），
     *                   没有这层代次校验就会把正在播的新歌停掉再跳一首（表现为莫名跳歌）。
     *                   -1 = 旧客户端（包里没有代次），只当冗余触发，仍以服务端自身判断为准。
     */
    public void onSongFinishedByClient(long playbackId) {
        // 全服暂停期间不收“已播完”信号，避免迟到 EOF 跳歌
        if (playQueue.isPaused()) {
            return;
        }
        if (!playQueue.isPlaying()) {
            return; // 已经停了/正在取下一首：本信号无需处理
        }
        if (playbackId >= 0) {
            if (playbackId != playQueue.getPlaybackId()) {
                logger.debug("忽略迟到的「已播完」信号: 信号代次={}, 当前代次={}", playbackId, playQueue.getPlaybackId());
                return;
            }
        } else if (!playQueue.isPlaybackFinished()) {
            // 旧客户端不带代次：无法分辨它说的是哪一首，只采信「服务端自己也认为该切了」的情况
            logger.debug("忽略旧客户端不带代次的「已播完」信号，等待服务端时长判定");
            return;
        }
        completeCurrentSong();
    }

    /**
     * 播放下一首
     */
    public boolean playNext() {
        // 显式要求播下一首 = 用户要继续听，解除「主动停止」的抑制
        autoAdvanceSuspended = false;
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
        autoAdvanceSuspended = false; // 同 playNext：显式操作即恢复自动续播
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
        final FetchState state = new FetchState(fetchEpoch.incrementAndGet(), item, skipOnFail);
        activeFetch = state;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            SongDetail detail = null;
            String failReason = null;
            try {
                MusicSource source = sourceManager.getSource(item.getSource());
                if (source == null) {
                    failReason = "找不到音源: " + item.getSource();
                } else {
                    detail = source.getDetail(item.getSongId());
                    if (detail == null) {
                        failReason = "获取歌曲失败: " + item.getTitle();
                    }
                }
            } catch (Throwable t) {
                // 连 Error 一起兜住：任何逃逸都会让 picking 永不释放 -> 队列永久卡死
                failReason = "播放失败: " + item.getTitle();
                logger.error("取歌任务异常: " + t.getMessage(), t);
            }
            final SongDetail finalDetail = detail;
            final String finalReason = failReason;
            Bukkit.getScheduler().runTask(plugin, () -> completeFetch(state, finalDetail, finalReason));
        });
    }

    /**
     * 取歌收尾（主线程）。两道闸：
     * 1) done 的 CAS —— 看门狗已经放弃的任务不再收尾；
     * 2) epoch 比对 —— 已经切到下一首后，迟到的旧结果直接丢弃，不播放、不释放、不跳歌。
     */
    private void completeFetch(FetchState state, SongDetail detail, String failReason) {
        if (!state.done.compareAndSet(false, true)) {
            return; // 看门狗已收尾
        }
        if (state.epoch != fetchEpoch.get()) {
            return; // 旧代次的迟到结果
        }
        activeFetch = null;

        if (failReason == null && detail != null) {
            // 歌曲信息缺失时使用队列中的信息
            if (detail.getTitle() == null || detail.getTitle().isEmpty()) {
                detail = new SongDetail(
                        detail.getSongId(),
                        state.item.getTitle(),
                        state.item.getArtist(),
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
            if (hasAudio) {
                logger.info("获取歌曲详情成功: {} - {} (audioUrl={})", detail.getTitle(), detail.getArtist(),
                        detail.getAudioUrl() != null && !detail.getAudioUrl().isEmpty()
                                ? detail.getAudioUrl().substring(0, Math.min(50, detail.getAudioUrl().length())) + "..."
                                : detail.getLocalPath());
                // 流程结束，允许下一次取歌
                picking.set(false);
                playSongDetail(state.item, detail);
                return;
            }
            logger.error("歌曲audioUrl为空，无法播放: {} - {}", detail.getTitle(), detail.getArtist());
            failReason = "获取音频URL失败: " + detail.getTitle();
        }
        if (failReason == null) {
            failReason = "获取歌曲失败: " + state.item.getTitle();
        }
        handleFetchFailure(state, failReason);
    }

    /**
     * 歌曲无法播放时的处理（主线程）：提示点歌人；若需要则自动尝试下一首（跳过无法播放的歌曲）
     */
    private void handleFetchFailure(FetchState state, String reason) {
        if (!Bukkit.isPrimaryThread()) {
            // 防御：目前所有调用点都在主线程；万一将来有异步调用点，切回主线程再处理
            Bukkit.getScheduler().runTask(plugin, () -> handleFetchFailure(state, reason));
            return;
        }
        if (state.epoch != fetchEpoch.get()) {
            return; // 代次已作废：不重复释放、不重复跳歌
        }
        try {
            Player requester = Bukkit.getPlayer(state.item.getRequesterUuid());
            if (requester != null) {
                requester.sendMessage("§c[MygoMusic] " + reason);
            }
            logger.error("[MygoMusic] {} ，已移除该歌曲", reason);

            if (!state.skipOnFail) {
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
            startFetch(next, true); // picking 保持 true 不断档；新 epoch 让旧任务的迟到结果自动作废
        } catch (Throwable t) {
            // 兜底：收尾自身出错也必须把取歌锁放掉，否则队列永久停摆
            logger.error("取歌失败处理异常，强制释放取歌锁: {}", t.getMessage(), t);
            activeFetch = null;
            picking.set(false);
        }
    }

    /**
     * 取消在途取歌（配合 /mm next 等显式操作）：释放取歌锁，迟到的结果按 epoch/done 丢弃。
     *
     * @return 是否确实取消了一个在途任务
     */
    public boolean cancelFetch() {
        FetchState state = activeFetch;
        if (state == null || !state.done.compareAndSet(false, true)) {
            return false;
        }
        fetchEpoch.incrementAndGet(); // 作废当前代次
        activeFetch = null;
        picking.set(false);
        return true;
    }

    /**
     * 是否正在取歌（解析歌曲信息/URL）
     */
    public boolean isFetching() {
        return activeFetch != null;
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
     * 用户显式停止（/mm stop、/mm admin stop）：停止当前播放，并抑制自动续播。
     *
     * 不做抑制的话，tick() 下一秒就会因为「没在播 + 队列非空」把下一首自动开起来，
     * 玩家看到的 /mm stop 实际是 /mm next。要恢复播放得用 /mm continue 或再点一首。
     * 同时在途的取歌任务也一并取消 —— 否则它取完照样会把歌播出来，看起来像停止无效。
     */
    public void stopByUser() {
        autoAdvanceSuspended = true;
        cancelFetch();
        stopCurrent();
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

        // 取歌进行中：给出明确反馈（原先 playNext 的 CAS 失败被静默吞掉，玩家连按毫无反应）
        FetchState state = activeFetch;
        if (state != null) {
            long seconds = (System.currentTimeMillis() - state.startedAtMs) / 1000;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOnline()) {
                    player.sendMessage("§6[MygoMusic] §e正在获取歌曲信息，请稍候（已等待 " + seconds + " 秒）");
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
        // 卡在「取歌」时 /mm next 也要能用：取消在途任务，否则 playNext 的 CAS 永远失败
        cancelFetch();
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
