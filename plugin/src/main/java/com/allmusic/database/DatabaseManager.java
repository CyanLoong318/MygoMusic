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
    /** 是否 MySQL。方言差别很大（自增主键 / INSERT OR IGNORE / INSERT OR REPLACE / CREATE INDEX IF NOT EXISTS），
     *  之前这些 SQLite 写法在 MySQL 上全都是语法错误，等于 config 里填 mysql 就直接不可用。 */
    private boolean mysql = false;

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
            this.mysql = dbType.equalsIgnoreCase("mysql");
            if (mysql) {
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

            hikariConfig.setMaximumPoolSize(mysql ? configManager.getMysqlPoolSize() : 1);
            hikariConfig.setConnectionTimeout(10000);
            hikariConfig.setIdleTimeout(600000);
            hikariConfig.setMaxLifetime(1800000);

            dataSource = new HikariDataSource(hikariConfig);
            connected = true;

            // 创建表
            createTables();

            logger.info("数据库初始化成功 ({})", dbType);
        } catch (Exception e) {
            logger.error("数据库初始化失败: " + e.getMessage(), e);
            connected = false;
        }
    }

    /**
     * 创建表
     */
    private void createTables() {
        // 自增主键写法两库不同
        String pk = mysql ? "BIGINT AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        // MySQL 不支持 CREATE INDEX IF NOT EXISTS，索引只能写在建表语句里
        String historyKeys = mysql ? ", KEY idx_history_player (player_uuid), KEY idx_history_time (played_at)" : "";
        String favoritesKeys = mysql ? ", KEY idx_favorites_player (player_uuid)" : "";

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // 播放历史表
            stmt.execute("CREATE TABLE IF NOT EXISTS play_history ("
                    + "id " + pk + ", "
                    + "player_uuid VARCHAR(36) NOT NULL, "
                    + "player_name VARCHAR(16) NOT NULL, "
                    + "song_source VARCHAR(16) NOT NULL, "
                    + "song_id VARCHAR(128) NOT NULL, "
                    + "song_title VARCHAR(256) NOT NULL, "
                    + "song_artist VARCHAR(256), "
                    + "played_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                    + historyKeys
                    + ")");

            // 收藏表
            stmt.execute("CREATE TABLE IF NOT EXISTS favorites ("
                    + "id " + pk + ", "
                    + "player_uuid VARCHAR(36) NOT NULL, "
                    + "song_source VARCHAR(16) NOT NULL, "
                    + "song_id VARCHAR(128) NOT NULL, "
                    + "song_title VARCHAR(256) NOT NULL, "
                    + "song_artist VARCHAR(256), "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "UNIQUE(player_uuid, song_source, song_id)"
                    + favoritesKeys
                    + ")");

            // 账号表
            stmt.execute("CREATE TABLE IF NOT EXISTS accounts ("
                    + "id " + pk + ", "
                    + "player_uuid VARCHAR(36) NOT NULL, "
                    + "platform VARCHAR(16) NOT NULL, "
                    + "token_encrypted TEXT NOT NULL, "
                    + "expires_at TIMESTAMP, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "UNIQUE(player_uuid, platform)"
                    + ")");

            // SQLite 的索引单独建（MySQL 已写在建表语句里）
            if (!mysql) {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_history_player ON play_history(player_uuid)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_history_time ON play_history(played_at)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_favorites_player ON favorites(player_uuid)");
            }

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

        String sql = mysql
                ? "INSERT IGNORE INTO favorites (player_uuid, song_source, song_id, song_title, song_artist) VALUES (?, ?, ?, ?, ?)"
                : "INSERT OR IGNORE INTO favorites (player_uuid, song_source, song_id, song_title, song_artist) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

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

        String sql = mysql
                ? "INSERT INTO accounts (player_uuid, platform, token_encrypted, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)"
                        + " ON DUPLICATE KEY UPDATE token_encrypted = VALUES(token_encrypted), updated_at = CURRENT_TIMESTAMP"
                : "INSERT OR REPLACE INTO accounts (player_uuid, platform, token_encrypted, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

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
