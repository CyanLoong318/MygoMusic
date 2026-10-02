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
import java.nio.file.Path;
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
    /** 最多保留几份关服队列快照（供 /mm admin restore 使用） */
    private static final int KEEP_QUEUE_FILES = 10;

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
            // 不自动恢复（避免上线就把上次的队列灌进来），由 OP 手动执行
            logger.info("发现 {} 个未恢复的队列快照，可用 /mm admin restore 恢复最近一份", files.length);
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
            // 顺带清理：每次关服都会落一份文件，不限制的话目录会一直涨。
            // （这个清理方法原来写好了却没人调用）
            cleanOldFiles(KEEP_QUEUE_FILES);
        } catch (Exception e) {
            logger.error("保存队列失败: " + e.getMessage(), e);
        }
    }

    /**
     * 恢复队列
     */
    public boolean restore(String fileName) {
        File file = new File(historyDir, fileName);
        // 防目录穿越：fileName 来自命令参数（/mm admin restore <文件名>），可能是 ../ 开头的任意路径。
        // 解析真实路径后必须仍在 queue_history 内，否则会清空队列并误删队列之外的无关文件。
        try {
            Path base = historyDir.getCanonicalFile().toPath();
            Path target = file.getCanonicalFile().toPath();
            if (!target.startsWith(base)) {
                logger.warn("拒绝恢复越界路径: {}", fileName);
                return false;
            }
        } catch (IOException e) {
            logger.warn("恢复路径解析失败: {} ({})", fileName, e.getMessage());
            return false;
        }
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

            // 关服时正在播放的那首不在等待队列里（放完才会再入队），但被单独保存过；
            // 恢复时把它插回队首，避免「恢复成功但正在播的那首凭空消失」。
            if (root.has("currentPlaying") && root.get("currentPlaying").isJsonObject()) {
                try {
                    JsonObject cur = root.getAsJsonObject("currentPlaying");
                    UUID requesterUuid;
                    try {
                        requesterUuid = UUID.fromString(cur.get("requesterUuid").getAsString());
                    } catch (Exception ignore) {
                        requesterUuid = new UUID(0L, 0L); // 点歌人信息缺失时用零 UUID 占位
                    }
                    QueueItem current = new QueueItem(
                            cur.get("songId").getAsString(),
                            cur.get("title").getAsString(),
                            cur.get("artist").getAsString(),
                            cur.get("source").getAsString(),
                            cur.has("requesterName") ? cur.get("requesterName").getAsString() : "?",
                            requesterUuid,
                            System.currentTimeMillis());
                    items.add(0, current);
                } catch (Exception e) {
                    logger.warn("跳过无法恢复的正在播放记录: {}", e.getMessage());
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
