package com.allmusic.placeholder;

import com.allmusic.AllMusicPlugin;
import com.allmusic.model.QueueItem;
import com.allmusic.queue.PlayQueue;
import com.allmusic.queue.QueueScheduler;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * PlaceholderAPI 扩展
 */
public class AllMusicExpansion extends PlaceholderExpansion {

    private final AllMusicPlugin plugin;
    private final PlayQueue playQueue;
    private final QueueScheduler queueScheduler;

    public AllMusicExpansion(AllMusicPlugin plugin, PlayQueue playQueue, QueueScheduler queueScheduler) {
        this.plugin = plugin;
        this.playQueue = playQueue;
        this.queueScheduler = queueScheduler;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "mygomusic";
    }

    @Override
    public @NotNull String getAuthor() {
        return "MygoMusicTeam";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        QueueItem current = playQueue.getCurrentPlaying();

        switch (params.toLowerCase()) {
            case "now_title":
                return current != null ? current.getTitle() : "无";

            case "now_artist":
                return current != null ? current.getArtist() : "无";

            case "now_source":
                return current != null ? current.getSourceDisplayName() : "无";

            case "is_playing":
                return String.valueOf(playQueue.isPlaying());

            case "queue_size":
                return String.valueOf(playQueue.size());

            case "queue_next":
                QueueItem next = playQueue.peek();
                return next != null ? next.getTitle() : "无";

            case "requester":
                return current != null ? current.getRequesterName() : "无";

            case "duration":
                if (current != null && playQueue.getCurrentSongDetail() != null) {
                    long duration = playQueue.getCurrentSongDetail().getDuration();
                    return formatTime(duration);
                }
                return "0:00";

            case "position":
                return formatTime(playQueue.getPlaybackPosition());

            default:
                return null;
        }
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
}
