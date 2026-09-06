package com.allmusic.model;

import java.util.UUID;

/**
 * 队列中的歌曲项
 */
public class QueueItem {
    private final String songId;
    private final String title;
    private final String artist;
    private final String source;
    private final String requesterName;
    private final UUID requesterUuid;
    private final long addedAt;

    public QueueItem(String songId, String title, String artist, String source,
                     String requesterName, UUID requesterUuid, long addedAt) {
        this.songId = songId;
        this.title = title;
        this.artist = artist;
        this.source = source;
        this.requesterName = requesterName;
        this.requesterUuid = requesterUuid;
        this.addedAt = addedAt;
    }

    public QueueItem(SongInfo song, String requesterName, UUID requesterUuid) {
        this(song.getSongId(), song.getTitle(), song.getArtist(), song.getSource(),
                requesterName, requesterUuid, System.currentTimeMillis());
    }

    public String getSongId() { return songId; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getSource() { return source; }
    public String getRequesterName() { return requesterName; }
    public UUID getRequesterUuid() { return requesterUuid; }
    public long getAddedAt() { return addedAt; }

    public String getSourceDisplayName() {
        switch (source) {
            case "netease": return "网易云";
            case "kugou": return "酷狗";
            case "bilibili": return "B站";
            default: return source;
        }
    }

    @Override
    public String toString() {
        return String.format("%s - %s (%s) [%s]", title, artist, getSourceDisplayName(), requesterName);
    }
}
