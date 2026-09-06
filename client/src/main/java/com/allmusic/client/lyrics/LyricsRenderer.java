package com.allmusic.client.lyrics;

import com.allmusic.client.AllMusicClient;
import com.allmusic.client.config.ClientConfig;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词数据与显示文本构造。
 * 由 PlayerHUD 每帧读取当前播放进度并绘制（常驻显示，不会像 actionbar 那样几秒后消失）。
 */
public class LyricsRenderer {
    private static final Logger logger = AllMusicClient.getLogger();

    private final ClientConfig config;
    private final List<LyricsLine> lines = new ArrayList<>();
    private String songTitle;
    private String songArtist;
    private boolean hasTranslation = false;

    public LyricsRenderer(ClientConfig config) {
        this.config = config;
    }

    /**
     * 设置歌词数据
     */
    public void setLyrics(String title, String artist, List<LyricsLine> lyrics, boolean hasTranslation) {
        this.songTitle = title;
        this.songArtist = artist;
        this.lines.clear();
        if (lyrics != null) {
            this.lines.addAll(lyrics);
        }
        this.hasTranslation = hasTranslation;
        logger.info("歌词已加载: {} 行", this.lines.size());
    }

    /**
     * 清除歌词
     */
    public void clear() {
        lines.clear();
        songTitle = null;
        songArtist = null;
    }

    /**
     * 获取当前播放位置对应歌词行的索引
     */
    public int getCurrentLineIndex(long positionMs) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).getTimestamp() <= positionMs) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 构造歌词 HUD 需要显示的几行文本：
     * 标题 - 歌手 / 当前歌词(可含译文) / 进度条
     */
    public List<String> buildDisplayLines(long positionMs, long durationMs) {
        List<String> out = new ArrayList<>();

        if (songTitle != null && !songTitle.isEmpty()) {
            out.add("§6♪ §e" + songTitle + " §7- §f" + (songArtist == null ? "" : songArtist));
        }

        int index = getCurrentLineIndex(positionMs);
        if (index >= 0 && index < lines.size()) {
            LyricsLine line = lines.get(index);
            out.add("§f" + line.getText());
            // 译文：原词非中文且有译文时显示
            if (config.isShowTranslation() && hasTranslation && line.hasTranslation()
                    && !isChinese(line.getText())) {
                out.add("§7" + line.getTranslation());
            }
        } else {
            // 还没唱到第一句
            out.add("§7♪");
        }

        if (durationMs > 0) {
            // 只显示时长进度，去掉进度条
            out.add("§8" + formatTime(positionMs) + " §7/ §f" + formatTime(durationMs));
        }
        return out;
    }

    /**
     * 检测是否包含中文
     */
    private boolean isChinese(String text) {
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    /**
     * 格式化时间
     */
    private String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    /**
     * 歌词行数据
     */
    public static class LyricsLine {
        private final long timestamp;
        private final String text;
        private final String translation;

        public LyricsLine(long timestamp, String text, String translation) {
            this.timestamp = timestamp;
            this.text = text;
            this.translation = translation;
        }

        public long getTimestamp() { return timestamp; }
        public String getText() { return text; }
        public String getTranslation() { return translation; }
        public boolean hasTranslation() { return translation != null && !translation.isEmpty(); }
    }
}
