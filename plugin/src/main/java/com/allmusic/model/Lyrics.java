package com.allmusic.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词数据
 */
public class Lyrics {
    private final List<LyricsLine> lines;
    private final String rawLyrics; // 原始LRC文本
    private final boolean hasTranslation; // 是否有翻译

    public Lyrics(List<LyricsLine> lines, String rawLyrics, boolean hasTranslation) {
        this.lines = lines != null ? lines : new ArrayList<>();
        this.rawLyrics = rawLyrics;
        this.hasTranslation = hasTranslation;
    }

    public List<LyricsLine> getLines() { return lines; }
    public String getRawLyrics() { return rawLyrics; }
    public boolean hasTranslation() { return hasTranslation; }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public int size() {
        return lines.size();
    }

    public LyricsLine getLine(int index) {
        if (index >= 0 && index < lines.size()) {
            return lines.get(index);
        }
        return null;
    }

    /**
     * 根据播放时间获取当前歌词行索引
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
     * 根据播放时间获取当前歌词行
     */
    public LyricsLine getCurrentLine(long positionMs) {
        int index = getCurrentLineIndex(positionMs);
        return index >= 0 ? lines.get(index) : null;
    }
}
