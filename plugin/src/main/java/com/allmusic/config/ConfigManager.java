package com.allmusic.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * 配置管理器
 */
public class ConfigManager {
    private final JavaPlugin plugin;
    private FileConfiguration config;

    // 数据库配置
    private String dbType;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;
    private int mysqlPoolSize;

    // 音源配置
    private boolean neteaseEnabled;
    private boolean kugouEnabled;
    private boolean bilibiliEnabled;

    // 队列配置
    private int queueMaxSize;
    private int cooldownSeconds;
    private boolean autoPlay;
    private int historySize;
    private boolean saveOnShutdown;

    // 播放配置
    private int defaultVolume;

    // 歌词配置
    private boolean lyricsEnabled;
    private String showTranslation; // auto/true/false

    // FFmpeg 配置
    private String ffmpegPath;
    private String ffmpegCacheDir;
    private int ffmpegCacheMaxSize;

    // 客户端音频缓存配置（由服务端统一下发，客户端GUI不再提供该设置）
    private boolean clientCacheEnabled;
    private int clientCacheMaxSizeMb;

    // HTTP 文件服务器配置（B站转码音频对外分发；无公网 IPv4 时靠公网 IPv6 供外地玩家下载）
    private int httpServerPort;
    private String httpServerHost;

    // 权限配置
    private String cooldownBypassPermission;
    private String queueLimitPermission;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        // 保存默认配置
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();

        // 数据库配置
        dbType = config.getString("database.type", "sqlite");
        mysqlHost = config.getString("database.mysql.host", "localhost");
        mysqlPort = config.getInt("database.mysql.port", 3306);
        mysqlDatabase = config.getString("database.mysql.database", "mygomusic");
        mysqlUsername = config.getString("database.mysql.username", "root");
        mysqlPassword = config.getString("database.mysql.password", "");
        mysqlPoolSize = config.getInt("database.mysql.pool-size", 10);

        // 音源配置
        neteaseEnabled = config.getBoolean("sources.netease.enabled", true);
        kugouEnabled = config.getBoolean("sources.kugou.enabled", true);
        bilibiliEnabled = config.getBoolean("sources.bilibili.enabled", true);

        // 队列配置
        queueMaxSize = config.getInt("queue.max-size", 50);
        cooldownSeconds = config.getInt("queue.cooldown-seconds", 10);
        autoPlay = config.getBoolean("queue.auto-play", true);
        historySize = config.getInt("queue.history-size", 100);
        saveOnShutdown = config.getBoolean("queue.save-on-shutdown", true);

        // 播放配置
        defaultVolume = config.getInt("playback.default-volume", 80);

        // 歌词配置
        lyricsEnabled = config.getBoolean("lyrics.enabled", true);
        showTranslation = config.getString("lyrics.show-translation", "auto");

        // FFmpeg 配置
        ffmpegPath = config.getString("ffmpeg.path", "ffmpeg");
        ffmpegCacheDir = new File(config.getString("ffmpeg.cache-dir", "plugins/MygoMusic/cache/bilibili")).getAbsolutePath();
        ffmpegCacheMaxSize = config.getInt("ffmpeg.cache-max-size", 1024);

        // 客户端音频缓存（服务端统一设置）
        clientCacheEnabled = config.getBoolean("client-cache.enabled", true);
        clientCacheMaxSizeMb = config.getInt("client-cache.max-size-mb", 512);

        // HTTP 文件服务器
        httpServerPort = config.getInt("http-server.port", 8080);
        httpServerHost = config.getString("http-server.host", "");

        // 权限配置
        cooldownBypassPermission = config.getString("permissions.cooldown-bypass", "mygomusic.nocooldown");
        queueLimitPermission = config.getString("permissions.queue-limit", "mygomusic.queuelimit");

        // 确保缓存目录存在
        new File(ffmpegCacheDir).mkdirs();
    }

    public void reloadConfig() {
        loadConfig();
    }

    /**
     * 保存某平台的登录 Cookie 到 cookies.yml（重启后自动恢复，无需重登）
     */
    public void saveSourceCookie(String platform, String cookie) {
        try {
            if (cookie == null || cookie.isEmpty()) return;
            File file = new File(plugin.getDataFolder(), "cookies.yml");
            FileConfiguration c = YamlConfiguration.loadConfiguration(file);
            c.set("cookies." + platform, cookie);
            c.save(file);
            plugin.getLogger().info("已保存 " + platform + " 登录Cookie，重启后自动恢复");
        } catch (Exception e) {
            plugin.getLogger().warning("保存 " + platform + " Cookie 失败: " + e.getMessage());
        }
    }

    /**
     * 读取某平台已保存的登录 Cookie
     */
    public String getSourceCookie(String platform) {
        try {
            File file = new File(plugin.getDataFolder(), "cookies.yml");
            if (!file.exists()) return "";
            FileConfiguration c = YamlConfiguration.loadConfiguration(file);
            return c.getString("cookies." + platform, "");
        } catch (Exception e) {
            return "";
        }
    }

    // Getters
    public String getDbType() { return dbType; }
    public String getMysqlHost() { return mysqlHost; }
    public int getMysqlPort() { return mysqlPort; }
    public String getMysqlDatabase() { return mysqlDatabase; }
    public String getMysqlUsername() { return mysqlUsername; }
    public String getMysqlPassword() { return mysqlPassword; }
    public int getMysqlPoolSize() { return mysqlPoolSize; }
    public boolean isNeteaseEnabled() { return neteaseEnabled; }
    public boolean isKugouEnabled() { return kugouEnabled; }
    public boolean isBilibiliEnabled() { return bilibiliEnabled; }
    public int getQueueMaxSize() { return queueMaxSize; }
    public int getCooldownSeconds() { return cooldownSeconds; }
    public boolean isAutoPlay() { return autoPlay; }
    public int getHistorySize() { return historySize; }
    public boolean isSaveOnShutdown() { return saveOnShutdown; }
    public int getDefaultVolume() { return defaultVolume; }
    public boolean isLyricsEnabled() { return lyricsEnabled; }
    public String getShowTranslation() { return showTranslation; }
    public String getFfmpegPath() { return ffmpegPath; }
    public String getFfmpegCacheDir() { return ffmpegCacheDir; }
    public int getFfmpegCacheMaxSize() { return ffmpegCacheMaxSize; }
    public boolean isClientCacheEnabled() { return clientCacheEnabled; }
    public int getClientCacheMaxSizeMb() { return clientCacheMaxSizeMb; }
    public int getHttpServerPort() { return httpServerPort; }
    public String getHttpServerHost() { return httpServerHost; }
    public String getCooldownBypassPermission() { return cooldownBypassPermission; }
    public String getQueueLimitPermission() { return queueLimitPermission; }
}
