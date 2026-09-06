package com.allmusic.queue;

import com.allmusic.config.ConfigManager;
import com.allmusic.model.QueueItem;
import com.allmusic.model.SongDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 播放队列
 */
public class PlayQueue {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-Queue");

    private final LinkedList<QueueItem> queue = new LinkedList<>();
    private final LinkedList<QueueItem> history = new LinkedList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final ConfigManager configManager;

    private QueueItem currentPlaying = null;
    private SongDetail currentSongDetail = null;
    private long playbackStartTime = 0;
    private boolean playing = false;

    /** 全局暂停：暂停期间播放进度冻结（不会因时间流逝触发自动切歌/完成判定） */
    private volatile boolean paused = false;
    private long pausedAtMs = -1;     // 进入暂停的时刻，-1 表示未处于暂停中
    private long totalPausedMs = 0;   // 历史累计暂停时长（不含当前这段）

    public PlayQueue(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * 添加歌曲到队列
     */
    public boolean add(QueueItem item) {
        lock.lock();
        try {
            if (queue.size() >= configManager.getQueueMaxSize()) {
                return false;
            }
            queue.add(item);
            logger.info("歌曲已加入队列: {}", item);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 取出下一首歌曲 (仅从等待队列取出, 播放历史在歌曲真正开始时记录)
     */
    public QueueItem poll() {
        lock.lock();
        try {
            return queue.poll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取上一首歌曲并移出历史 (供"上一首"播放)
     */
    public QueueItem getPrevious() {
        lock.lock();
        try {
            if (history.isEmpty()) {
                return null;
            }
            return history.removeFirst();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 记录一首已播放的歌曲到历史 (最近播放的排在最前, 不含当前播放)
     */
    private void pushHistory(QueueItem item) {
        if (item == null) return;
        // 避免与最近一条历史重复
        if (!history.isEmpty() && history.getFirst().getSongId().equals(item.getSongId())) {
            return;
        }
        history.addFirst(item);
        if (history.size() > configManager.getHistorySize()) {
            history.removeLast();
        }
    }

    /**
     * 查看队首歌曲 (不取出)
     */
    public QueueItem peek() {
        lock.lock();
        try {
            return queue.peek();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取队列大小
     */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 队列是否为空
     */
    public boolean isEmpty() {
        lock.lock();
        try {
            return queue.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清空队列
     */
    public void clear() {
        lock.lock();
        try {
            queue.clear();
            logger.info("队列已清空");
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取队列快照
     */
    public List<QueueItem> getSnapshot() {
        lock.lock();
        try {
            return new ArrayList<>(queue);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取历史记录快照
     */
    public List<QueueItem> getHistorySnapshot() {
        lock.lock();
        try {
            return new ArrayList<>(history);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按序号移除队列中的一首歌（序号 0 基，与 getSnapshot() 顺序一致）。
     *
     * @param index          等待队列内的下标
     * @param requesterUuid  操作者 UUID（用于校验是否本人所点）
     * @param allowAny       是否允许移除任意人点的歌（管理员）
     * @return 1=成功移除 0=序号越界/不存在 -1=非本人所点（无权限）
     */
    public int removeAt(int index, UUID requesterUuid, boolean allowAny) {
        lock.lock();
        try {
            if (index < 0 || index >= queue.size()) return 0;
            QueueItem item = queue.get(index);
            if (!allowAny && !item.getRequesterUuid().equals(requesterUuid)) return -1;
            queue.remove(index);
            logger.info("已从队列移除: {} (操作者: {})", item, requesterUuid);
            return 1;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 暂停当前播放（全服同步）。幂等。
     */
    public void pause() {
        lock.lock();
        try {
            if (paused) return;
            paused = true;
            pausedAtMs = System.currentTimeMillis();
            logger.info("播放已暂停 (全服)");
        } finally {
            lock.unlock();
        }
    }

    /**
     * 恢复当前播放（全服同步）。幂等；把暂停时长并入累计，使有效进度冻结后继续。
     */
    public void resume() {
        lock.lock();
        try {
            if (!paused) return;
            long now = System.currentTimeMillis();
            if (pausedAtMs >= 0) {
                totalPausedMs += (now - pausedAtMs);
            }
            paused = false;
            pausedAtMs = -1;
            logger.info("播放已恢复 (全服)");
        } finally {
            lock.unlock();
        }
    }

    /**
     * 是否处于（全服）暂停中
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * 有效播放时长（毫秒）：扣除历史与当前暂停时段，暂停时进度保持不变
     */
    private long effectiveElapsedMs(long now) {
        long elapsed = now - playbackStartTime - totalPausedMs;
        if (paused && pausedAtMs >= 0) {
            elapsed -= (now - pausedAtMs);
        }
        return Math.max(0, elapsed);
    }

    private void resetPauseState() {
        paused = false;
        pausedAtMs = -1;
        totalPausedMs = 0;
    }

    /**
     * 设置当前播放歌曲
     */
    public void setCurrentPlaying(QueueItem item, SongDetail detail) {
        lock.lock();
        try {
            // 上一首真正开始播放过的歌曲先进入历史
            if (this.currentPlaying != null && !this.currentPlaying.equals(item)) {
                pushHistory(this.currentPlaying);
            }
            this.currentPlaying = item;
            this.currentSongDetail = detail;
            this.playbackStartTime = System.currentTimeMillis();
            this.playing = true;
            // 新歌开始 → 解除暂停（切歌即“继续播放新歌”）
            resetPauseState();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取当前播放歌曲
     */
    public QueueItem getCurrentPlaying() {
        lock.lock();
        try {
            return currentPlaying;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取当前歌曲详情
     */
    public SongDetail getCurrentSongDetail() {
        lock.lock();
        try {
            return currentSongDetail;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 停止播放
     */
    public void stop() {
        lock.lock();
        try {
            this.playing = false;
            resetPauseState();
            logger.info("播放已停止");
        } finally {
            lock.unlock();
        }
    }

    /**
     * 是否正在播放
     */
    public boolean isPlaying() {
        lock.lock();
        try {
            return playing;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取当前播放进度 (毫秒)，扣除暂停时间（暂停时进度保持冻结）
     */
    public long getPlaybackPosition() {
        lock.lock();
        try {
            if (!playing || currentSongDetail == null) {
                return 0;
            }
            return effectiveElapsedMs(System.currentTimeMillis());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 检查歌曲是否播放完毕（基于有效播放时长，暂停期间不会“到点”）
     */
    public boolean isPlaybackFinished() {
        lock.lock();
        try {
            if (!playing || currentSongDetail == null) {
                return false;
            }
            long position = effectiveElapsedMs(System.currentTimeMillis());
            return position >= currentSongDetail.getDuration();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 恢复队列 (从持久化数据)
     */
    public void restore(List<QueueItem> items) {
        lock.lock();
        try {
            queue.clear();
            queue.addAll(items);
            logger.info("队列已恢复，共 {} 首歌曲", items.size());
        } finally {
            lock.unlock();
        }
    }
}
