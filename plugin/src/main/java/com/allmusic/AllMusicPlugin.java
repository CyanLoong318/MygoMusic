package com.allmusic;

import com.allmusic.command.MusicCommand;
import com.allmusic.command.MusicTabCompleter;
import com.allmusic.config.ConfigManager;
import com.allmusic.database.DatabaseManager;
import com.allmusic.network.HttpFileServer;
import com.allmusic.network.PluginChannel;
import com.allmusic.queue.PlayQueue;
import com.allmusic.queue.QueuePersistence;
import com.allmusic.queue.QueueScheduler;
import com.allmusic.source.SourceManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public class AllMusicPlugin extends JavaPlugin {

    private static AllMusicPlugin instance;
    private static final Logger logger = Logger.getLogger("MygoMusic");

    private ConfigManager configManager;
    private SourceManager sourceManager;
    private PlayQueue playQueue;
    private QueueScheduler queueScheduler;
    private QueuePersistence queuePersistence;
    private PluginChannel pluginChannel;
    private DatabaseManager databaseManager;
    private HttpFileServer httpFileServer;

    @Override
    public void onEnable() {
        instance = this;
        logger.info("MygoMusic 正在启动...");

        try {
            // 初始化配置
            configManager = new ConfigManager(this);
            configManager.loadConfig();

            // 初始化数据库
            databaseManager = new DatabaseManager(this, configManager);
            databaseManager.initialize();

            // 初始化音源管理器
            sourceManager = new SourceManager(this, configManager);

            // 初始化播放队列
            playQueue = new PlayQueue(configManager);
            queuePersistence = new QueuePersistence(this, playQueue);
            queuePersistence.loadOnStartup();

            // 初始化队列调度器
            queueScheduler = new QueueScheduler(this, playQueue, sourceManager);

            // 初始化 Plugin Messaging
            pluginChannel = new PluginChannel(this, queueScheduler);

            // 启动 HTTP 文件服务器（用于 B站转码后的 MP3）
            httpFileServer = new HttpFileServer();
            httpFileServer.start(configManager.getHttpServerPort(), configManager.getFfmpegCacheDir(),
                    configManager.getHttpServerHost());

            // 注册命令
            getCommand("mm").setExecutor(new MusicCommand(this, sourceManager, playQueue, queueScheduler, pluginChannel, configManager, databaseManager));
            getCommand("mm").setTabCompleter(new MusicTabCompleter(sourceManager));

            // 注册 Plugin Messaging Channel
            getServer().getMessenger().registerOutgoingPluginChannel(this, "allmusic:main");
            getServer().getMessenger().registerIncomingPluginChannel(this, "allmusic:main", pluginChannel);

            // 玩家加入时推送缓存设置与当前队列
            registerJoinSync();

            // 注册 PlaceholderAPI 扩展
            if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                try {
                    Class.forName("com.allmusic.placeholder.AllMusicExpansion")
                            .getDeclaredConstructor(AllMusicPlugin.class, PlayQueue.class, QueueScheduler.class)
                            .newInstance(this, playQueue, queueScheduler);
                    logger.info("PlaceholderAPI 扩展已注册");
                } catch (Exception e) {
                    logger.warning("PlaceholderAPI 扩展注册失败: " + e.getMessage());
                }
            }

            logger.info("MygoMusic 启动成功!");
        } catch (Exception e) {
            logger.severe("MygoMusic 启动失败: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /**
     * 注册加入服务器监听：等待客户端注册好插件通道后，推送缓存设置与当前队列
     */
    private void registerJoinSync() {
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                final org.bukkit.entity.Player p = event.getPlayer();
                getServer().getScheduler().runTaskLater(AllMusicPlugin.this, () -> {
                    if (!p.isOnline()) return;
                    if (pluginChannel == null) return;
                    pluginChannel.sendQueueSync(p);
                    pluginChannel.sendCacheConfig(p);
                }, 30L);
            }
        }, this);
    }

    @Override
    public void onDisable() {
        logger.info("MygoMusic 正在关闭...");

        // 保存队列到文件
        if (queuePersistence != null) {
            queuePersistence.saveOnShutdown();
        }

        // 关闭数据库连接
        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        // 关闭队列调度器
        if (queueScheduler != null) {
            queueScheduler.shutdown();
        }

        // 关闭 HTTP 文件服务器
        if (httpFileServer != null) {
            httpFileServer.stop();
        }

        logger.info("MygoMusic 已关闭");
    }

    public static AllMusicPlugin getInstance() {
        return instance;
    }

    public static Logger getPluginLogger() {
        return logger;
    }

    public Logger getLogger() {
        return logger;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public SourceManager getSourceManager() {
        return sourceManager;
    }

    public PlayQueue getPlayQueue() {
        return playQueue;
    }

    public QueueScheduler getQueueScheduler() {
        return queueScheduler;
    }

    public PluginChannel getPluginChannel() {
        return pluginChannel;
    }

    public HttpFileServer getHttpFileServer() {
        return httpFileServer;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}
