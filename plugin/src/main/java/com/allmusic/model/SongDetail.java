package com.allmusic.model;

/**
 * 歌曲详细信息 (包含播放URL和歌词)
 */
public class SongDetail extends SongInfo {
    private final String audioUrl; // 音频直链
    private final Lyrics lyrics; // 歌词
    private final boolean isVip; // 是否VIP歌曲
    private final String localPath; // 本地文件路径 (B站转码后)

    public SongDetail(String songId, String title, String artist, String album, long duration,
                      String source, String coverUrl, String extra, String audioUrl, Lyrics lyrics,
                      boolean isVip, String localPath) {
        super(songId, title, artist, album, duration, source, coverUrl, extra);
        this.audioUrl = audioUrl;
        this.lyrics = lyrics;
        this.isVip = isVip;
        this.localPath = localPath;
    }

    public String getAudioUrl() { return audioUrl; }
    public Lyrics getLyrics() { return lyrics; }
    public boolean isVip() { return isVip; }
    public String getLocalPath() { return localPath; }

    public boolean hasLyrics() {
        return lyrics != null && !lyrics.isEmpty();
    }

    public boolean hasLocalFile() {
        return localPath != null && !localPath.isEmpty();
    }
}
