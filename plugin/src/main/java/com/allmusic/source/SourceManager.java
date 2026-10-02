package com.allmusic.source;

import com.allmusic.config.ConfigManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 音源管理器
 */
public class SourceManager {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-Source");

    /**
     * 音源表。reload() 是「建新表 → 整体换引用」，不是原地 clear+put：
     * 旧写法在异步搜索/测试遍历到一半时 clear()，会直接抛 ConcurrentModificationException，
     * 而且中间还会出现一个「什么都查不到」的空窗期。表本身只读，换引用才安全。
     */
    private volatile Map<String, MusicSource> sources = Map.of();
    private final JavaPlugin plugin;
    private final ConfigManager configManager;

    public SourceManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        initializeSources();
    }

    private void initializeSources() {
        Map<String, MusicSource> loaded = new LinkedHashMap<>();

        // 初始化网易云音源
        if (configManager.isNeteaseEnabled()) {
            try {
                NeteaseSource netease = new NeteaseSource();
                loaded.put("netease", netease);
                restoreCookie(netease, "netease");
                logger.info("网易云音源已加载");
            } catch (Exception e) {
                logger.error("网易云音源加载失败: " + e.getMessage());
            }
        }

        // 初始化酷狗音源
        if (configManager.isKugouEnabled()) {
            try {
                KugouSource kugou = new KugouSource();
                loaded.put("kugou", kugou);
                restoreCookie(kugou, "kugou");
                logger.info("酷狗音源已加载");
            } catch (Exception e) {
                logger.error("酷狗音源加载失败: " + e.getMessage());
            }
        }

        // 初始化B站音源
        if (configManager.isBilibiliEnabled()) {
            try {
                BilibiliSource bilibili = new BilibiliSource(configManager);
                loaded.put("bilibili", bilibili);
                restoreCookie(bilibili, "bilibili");
                logger.info("B站音源已加载");
            } catch (Exception e) {
                logger.error("B站音源加载失败: " + e.getMessage());
            }
        }

        this.sources = Collections.unmodifiableMap(loaded);
        logger.info("已加载 {} 个音源", loaded.size());
    }

    /**
     * 从 cookies.yml 恢复某平台的登录态（重启后无需重登）
     */
    private void restoreCookie(MusicSource source, String platform) {
        String saved = configManager.getSourceCookie(platform);
        if (saved == null || saved.isEmpty()) {
            return;
        }
        try {
            source.restoreSession(saved);
            logger.info("已从持久化恢复 {} 登录态", platform);
        } catch (Exception e) {
            logger.warn("恢复 {} 登录态失败: {}", platform, e.getMessage());
        }
    }

    /**
     * 获取指定音源
     */
    public MusicSource getSource(String name) {
        return sources.get(name.toLowerCase());
    }

    /**
     * 获取所有音源
     */
    public List<MusicSource> getAllSources() {
        return new ArrayList<>(sources.values());
    }

    /**
     * 获取所有已启用的音源名称
     */
    public List<String> getEnabledSourceNames() {
        return new ArrayList<>(sources.keySet());
    }

    /**
     * 获取默认音源 (第一个可用的)
     */
    public MusicSource getDefaultSource() {
        return sources.values().stream().findFirst().orElse(null);
    }

    /**
     * 在所有音源中搜索
     */
    public Map<String, List<com.allmusic.model.SongInfo>> searchAll(String keyword, int limitPerSource) {
        Map<String, List<com.allmusic.model.SongInfo>> results = new HashMap<>();
        for (Map.Entry<String, MusicSource> entry : sources.entrySet()) {
            try {
                List<com.allmusic.model.SongInfo> results1 = entry.getValue().search(keyword, limitPerSource);
                results.put(entry.getKey(), results1);
            } catch (Exception e) {
                logger.error("搜索失败 ({}): {}", entry.getKey(), e.getMessage());
                results.put(entry.getKey(), new ArrayList<>());
            }
        }
        return results;
    }

    /**
     * 测试所有音源连通性
     */
    public Map<String, boolean[]> testAllSources() {
        Map<String, boolean[]> results = new HashMap<>();
        for (Map.Entry<String, MusicSource> entry : sources.entrySet()) {
            try {
                // 测试搜索功能
                List<com.allmusic.model.SongInfo> searchResults = entry.getValue().search("测试", 1);
                boolean searchOk = !searchResults.isEmpty();

                // 测试登录状态
                boolean loginOk = entry.getValue().isLoggedIn();

                results.put(entry.getKey(), new boolean[]{searchOk, loginOk});
            } catch (Exception e) {
                results.put(entry.getKey(), new boolean[]{false, false});
            }
        }
        return results;
    }

    public void reload() {
        // initializeSources() 内部建新表并整体替换引用，无需（也不能）在这里先 clear
        initializeSources();
    }
}
