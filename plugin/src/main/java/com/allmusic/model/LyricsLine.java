package com.allmusic.model;

/**
 * 歌词单行数据
 */
public class LyricsLine {
    private final long timestamp; // 毫秒
    private final String text; // 原文
    private final String translation; // 翻译 (可为null)

    public LyricsLine(long timestamp, String text, String translation) {
        this.timestamp = timestamp;
        this.text = text;
        this.translation = translation;
    }

    public long getTimestamp() { return timestamp; }
    public String getText() { return text; }
    public String getTranslation() { return translation; }

    public boolean hasTranslation() {
        return translation != null && !translation.isEmpty();
    }

    public String getTimestampFormatted() {
        long seconds = timestamp / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        long millis = timestamp % 1000;
        return String.format("[%d:%02d.%03d]", minutes, seconds, millis);
    }

    @Override
    public String toString() {
        if (hasTranslation()) {
            return String.format("%s %s (%s)", getTimestampFormatted(), text, translation);
        }
        return String.format("%s %s", getTimestampFormatted(), text);
    }
}
