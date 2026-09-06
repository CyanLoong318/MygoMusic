package com.allmusic.queue;

import com.allmusic.AllMusicPlugin;
import com.allmusic.model.QueueItem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 队列持久化
 */
public class QueuePersistence {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-Persistence");
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final AllMusicPlugin plugin;
    private final PlayQueue playQueue;
    private final File historyDir;

    public QueuePersistence(AllMusicPlugin plugin, PlayQueue playQueue) {
        this.plugin = plugin;
        this.playQueue = playQueue;
        this.historyDir = new File(plugin.getDataFolder(), "queue_history");
        historyDir.mkdirs();
    }

    /**
     * 开服时加载队列
     */
    public void loadOnStartup() {
        // 检查是否有未恢复的队列文件
        File[] files = historyDir.listFiles((dir, name) -> name.startsWith("queue_") && name.endsWith(".json"));
        if (files != null && files.length > 0) {
            logger.info("发现 {} 个未恢复的队列文件", files.length);
            // 不自动恢复，等待OP手动恢复
        }
    }

    /**
     * 关服时保存队列
     */
    public void saveOnShutdown() {
        if (playQueue.isEmpty() && !playQueue.isPlaying()) {
            return;
        }

        try {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File saveFile = new File(historyDir, "queue_" + timestamp + ".json");

            JsonObject root = new JsonObject();
            root.addProperty("savedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()));
            root.addProperty("serverName", plugin.getServer().getName());

            // 保存当前播放
            QueueItem currentPlaying = playQueue.getCurrentPlaying();
            if (currentPlaying != null) {
                JsonObject current = new JsonObject();
                current.addProperty("songId", currentPlaying.getSongId());
                current.addProperty("title", currentPlaying.getTitle());
                current.addProperty("artist", currentPlaying.getArtist());
                current.addProperty("source", currentPlaying.getSource());
                current.addProperty("requesterName", currentPlaying.getRequesterName());
                current.addProperty("requesterUuid", currentPlaying.getRequesterUuid().toString());
                current.addProperty("positionMs", playQueue.getPlaybackPosition());
                root.add("currentPlaying", current);
            }

            // 保存队列
            JsonArray queueArray = new JsonArray();
            for (QueueItem item : playQueue.getSnapshot()) {
                JsonObject itemJson = new JsonObject();
                itemJson.addProperty("songId", item.getSongId());
                itemJson.addProperty("title", item.getTitle());
                itemJson.addProperty("artist", item.getArtist());
                itemJson.addProperty("source", item.getSource());
                itemJson.addProperty("requesterName", item.getRequesterName());
                itemJson.addProperty("requesterUuid", item.getRequesterUuid().toString());
                itemJson.addProperty("addedAt", item.getAddedAt());
                queueArray.add(itemJson);
            }
            root.add("queue", queueArray);

            // 保存到文件
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(saveFile), StandardCharsets.UTF_8)) {
                gson.toJson(root, writer);
            }

            logger.info("队列已保存到: {}", saveFile.getName());
        } catch (Exception e) {
            logger.error("保存队列失败: " + e.getMessage(), e);
        }
    }

    /**
     * 恢复队列
     */
    public boolean restore(String fileName) {
        File file = new File(historyDir, fileName);
        if (!file.exists()) {
            return false;
        }

        try {
            JsonObject root;
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                root = gson.fromJson(reader, JsonObject.class);
            }

            List<QueueItem> items = new ArrayList<>();
            if (root.has("queue")) {
                JsonArray queueArray = root.getAsJsonArray("queue");
                for (com.google.gson.JsonElement element : queueArray) {
                    JsonObject itemJson = element.getAsJsonObject();
                    QueueItem item = new QueueItem(
                            itemJson.get("songId").getAsString(),
                            itemJson.get("title").getAsString(),
                            itemJson.get("artist").getAsString(),
                            itemJson.get("source").getAsString(),
                            itemJson.get("requesterName").getAsString(),
                            UUID.fromString(itemJson.get("requesterUuid").getAsString()),
                            itemJson.has("addedAt") ? itemJson.get("addedAt").getAsLong() : System.currentTimeMillis()
                    );
                    items.add(item);
                }
            }

            playQueue.restore(items);

            // 删除已恢复的文件
            file.delete();

            logger.info("队列已恢复，共 {} 首歌曲", items.size());
            return true;
        } catch (Exception e) {
            logger.error("恢复队列失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取可恢复的队列文件列表
     */
    public List<String> getAvailableRestores() {
        List<String> files = new ArrayList<>();
        File[] list = historyDir.listFiles((dir, name) -> name.startsWith("queue_") && name.endsWith(".json"));
        if (list != null) {
            for (File file : list) {
                files.add(file.getName());
            }
        }
        return files;
    }

    /**
     * 清理旧的队列文件
     */
    public void cleanOldFiles(int keepCount) {
        File[] files = historyDir.listFiles((dir, name) -> name.startsWith("queue_") && name.endsWith(".json"));
        if (files == null || files.length <= keepCount) {
            return;
        }

        // 按修改时间排序
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        // 删除多余的文件
        for (int i = keepCount; i < files.length; i++) {
            if (files[i].delete()) {
                logger.info("清理旧队列文件: {}", files[i].getName());
            }
        }
    }
}
