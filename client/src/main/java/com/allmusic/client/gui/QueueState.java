package com.allmusic.client.gui;

import java.util.Collections;
import java.util.List;

/**
 * 播放队列快照 - 由 QUEUE_SYNC (0x08) 数据包解析而来，供 GUI 渲染
 */
public class QueueState {

    /**
     * 队列中的一首歌曲
     */
    public record Song(String songId, String title, String artist, String source, String requesterName) {

        public String sourceDisplayName() {
            switch (source) {
                case "netease": return "网易云";
                case "kugou": return "酷狗";
                case "bilibili": return "B站";
                default: return source;
            }
        }

        @Override
        public String toString() {
            return title + " - " + artist + " (" + sourceDisplayName() + ")";
        }
    }

    /** 空状态（尚未收到任何队列数据） */
    public static final QueueState EMPTY = new QueueState(null, false, false, Collections.emptyList(), Collections.emptyList());

    private static volatile QueueState current = EMPTY;

    /** 当前正在播放的歌曲（可能为 null） */
    private final Song nowPlaying;
    /** 服务端是否处于播放状态 */
    private final boolean playing;
    /** 服务端是否处于（全服）暂停状态 */
    private final boolean paused;
    /** 已播放历史（最近优先，不含当前播放） */
    private final List<Song> history;
    /** 等待队列（不含当前播放） */
    private final List<Song> queue;

    public QueueState(Song nowPlaying, boolean playing, boolean paused, List<Song> history, List<Song> queue) {
        this.nowPlaying = nowPlaying;
        this.playing = playing;
        this.paused = paused;
        this.history = Collections.unmodifiableList(history);
        this.queue = Collections.unmodifiableList(queue);
    }

    public static QueueState get() {
        return current;
    }

    /**
     * 更新快照（应由主线程调用）
     */
    public static void update(Song nowPlaying, boolean playing, boolean paused, List<Song> history, List<Song> queue) {
        current = new QueueState(nowPlaying, playing, paused, history, queue);
    }

    public Song getNowPlaying() {
        return nowPlaying;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isPaused() {
        return paused;
    }

    public List<Song> getHistory() {
        return history;
    }

    public List<Song> getQueue() {
        return queue;
    }
}
