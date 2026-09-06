package com.allmusic.database;

import com.allmusic.config.ConfigManager;
import com.allmusic.model.SongInfo;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.util.UUID;

/**
 * 数据库管理器
 */
public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-DB");

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private HikariDataSource dataSource;
    private boolean connected = false;

    public DatabaseManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    /**
     * 初始化数据库
     */
    public void initialize() {
        try {
            HikariConfig hikariConfig = new HikariConfig();

            String dbType = configManager.getDbType();
            if (dbType.equalsIgnoreCase("mysql")) {
                // MySQL 配置
                hikariConfig.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=Asia/Shanghai",
                        configManager.getMysqlHost(),
                        configManager.getMysqlPort(),
                        configManager.getMysqlDatabase()));
                hikariConfig.setUsername(configManager.getMysqlUsername());
                hikariConfig.setPassword(configManager.getMysqlPassword());
                hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
            } else {
                // SQLite 配置
                File dbFile = new File(plugin.getDataFolder(), "mygomusic.db");
                hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
                hikariConfig.setDriverClassName("org.sqlite.JDBC");
            }

            hikariConfig.setMaximumPoolSize(dbType.equalsIgnoreCase("mysql") ? configManager.getMysqlPoolSize() : 1);
            hikariConfig.setConnectionTimeout(10000);
            hikariConfig.setIdleTimeout(600000);
            hikariConfig.setMaxLifetime(1800000);

            dataSource = new HikariDataSource(hikariConfig);
            connected = true;

            // 创建表
            createTables();

            logger.info("数据库初始化成功 ({}:)", dbType);
        } catch (Exception e) {
            logger.error("数据库初始化失败: " + e.getMessage(), e);
            connected = false;
        }
    }

    /**
     * 创建表
     */
    private void createTables() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // 播放历史表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS play_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid VARCHAR(36) NOT NULL,
                    player_name VARCHAR(16) NOT NULL,
                    song_source VARCHAR(16) NOT NULL,
                    song_id VARCHAR(128) NOT NULL,
                    song_title VARCHAR(256) NOT NULL,
                    song_artist VARCHAR(256),
                    played_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            // 收藏表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS favorites (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid VARCHAR(36) NOT NULL,
                    song_source VARCHAR(16) NOT NULL,
                    song_id VARCHAR(128) NOT NULL,
                    song_title VARCHAR(256) NOT NULL,
                    song_artist VARCHAR(256),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(player_uuid, song_source, song_id)
                )
            """);

            // 账号表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid VARCHAR(36) NOT NULL,
                    platform VARCHAR(16) NOT NULL,
                    token_encrypted TEXT NOT NULL,
                    expires_at TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(player_uuid, platform)
                )
            """);

            // 创建索引
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_history_player ON play_history(player_uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_history_time ON play_history(played_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_favorites_player ON favorites(player_uuid)");

        } catch (SQLException e) {
            logger.error("创建表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 保存播放历史
     */
    public void savePlayHistory(UUID playerUuid, String playerName, SongInfo song) {
        if (!connected) return;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO play_history (player_uuid, player_name, song_source, song_id, song_title, song_artist) VALUES (?, ?, ?, ?, ?, ?)")) {

            stmt.setString(1, playerUuid.toString());
            stmt.setString(2, playerName);
            stmt.setString(3, song.getSource());
            stmt.setString(4, song.getSongId());
            stmt.setString(5, song.getTitle());
            stmt.setString(6, song.getArtist());
            stmt.executeUpdate();

        } catch (SQLException e) {
            logger.error("保存播放历史失败: " + e.getMessage(), e);
        }
    }

    /**
     * 添加收藏
     */
    public boolean addFavorite(UUID playerUuid, SongInfo song) {
        if (!connected) return false;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT OR IGNORE INTO favorites (player_uuid, song_source, song_id, song_title, song_artist) VALUES (?, ?, ?, ?, ?)")) {

            stmt.setString(1, playerUuid.toString());
            stmt.setString(2, song.getSource());
            stmt.setString(3, song.getSongId());
            stmt.setString(4, song.getTitle());
            stmt.setString(5, song.getArtist());
            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            logger.error("添加收藏失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 保存账号信息
     */
    public void saveAccount(UUID playerUuid, String platform, String encryptedToken) {
        if (!connected) return;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT OR REPLACE INTO accounts (player_uuid, platform, token_encrypted, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)")) {

            stmt.setString(1, playerUuid.toString());
            stmt.setString(2, platform);
            stmt.setString(3, encryptedToken);
            stmt.executeUpdate();

        } catch (SQLException e) {
            logger.error("保存账号失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取账号信息
     */
    public String getAccountToken(UUID playerUuid, String platform) {
        if (!connected) return null;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT token_encrypted FROM accounts WHERE player_uuid = ? AND platform = ?")) {

            stmt.setString(1, playerUuid.toString());
            stmt.setString(2, platform);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("token_encrypted");
            }

        } catch (SQLException e) {
            logger.error("获取账号失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 检查连接
     */
    public boolean isConnected() {
        return connected && dataSource != null && !dataSource.isClosed();
    }

    /**
     * 关闭数据库
     */
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("数据库连接已关闭");
        }
    }
}
