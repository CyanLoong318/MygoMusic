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
     * 获取当前播放进度 (毫秒)
     */
    public long getPlaybackPosition() {
        lock.lock();
        try {
            if (!playing || currentSongDetail == null) {
                return 0;
            }
            return System.currentTimeMillis() - playbackStartTime;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 检查歌曲是否播放完毕
     */
    public boolean isPlaybackFinished() {
        lock.lock();
        try {
            if (!playing || currentSongDetail == null) {
                return false;
            }
            long position = System.currentTimeMillis() - playbackStartTime;
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
