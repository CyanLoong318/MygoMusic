package com.allmusic.model;

/**
 * 歌曲基本信息 (搜索结果)
 */
public class SongInfo {
    private final String songId;
    private final String title;
    private final String artist;
    private final String album;
    private final long duration; // 毫秒
    private final String source; // netease/kugou/bilibili
    private final String coverUrl; // 封面图URL
    private final String extra; // 额外信息

    public SongInfo(String songId, String title, String artist, String album, long duration, String source, String coverUrl, String extra) {
        this.songId = songId;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
        this.source = source;
        this.coverUrl = coverUrl;
        this.extra = extra;
    }

    public String getSongId() { return songId; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public long getDuration() { return duration; }
    public String getSource() { return source; }
    public String getCoverUrl() { return coverUrl; }
    public String getExtra() { return extra; }

    public String getSourceDisplayName() {
        switch (source) {
            case "netease": return "网易云";
            case "kugou": return "酷狗";
            case "bilibili": return "B站";
            default: return source;
        }
    }

    public String getDurationFormatted() {
        long seconds = duration / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    @Override
    public String toString() {
        return String.format("%s - %s (%s) [%s]", title, artist, source, getDurationFormatted());
    }
}
