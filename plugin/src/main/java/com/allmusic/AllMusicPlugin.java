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
    private MusicCommand musicCommand;

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

            // 启动队列调度器：每秒 tick —— 取歌看门狗（防止一次卡死的取歌把整队拖停）、
            // 按时长判断歌曲播完并续播、时长未知时 15 分钟兜底切歌。
            // 没有这一步，调度器一次都不会跑（这些兜底全是死代码）。
            queueScheduler.start();

            // 启动 HTTP 文件服务器（用于 B站转码后的 MP3）
            // 直链模式下也保留：视频只有 HE-AAC/杜比/无损音轨时会回退到转码分发
            httpFileServer = new HttpFileServer();
            httpFileServer.start(configManager.getHttpServerPort(), configManager.getFfmpegCacheDir(),
                    configManager.getHttpServerHost());

            // B站播放方式提示
            if ("transcode".equalsIgnoreCase(configManager.getBilibiliPlayMode())) {
                logger.info("B站播放方式: transcode（服务端 ffmpeg 转码 MP3，需已安装 ffmpeg）");
            } else {
                logger.info("B站播放方式: direct（下发B站CDN直链，客户端自行拉流解码；"
                        + "需要 v1.0.2+ 客户端，旧客户端播放B站音频会失败）");
            }

            // 注册命令
            musicCommand = new MusicCommand(this, sourceManager, playQueue, queueScheduler, pluginChannel, configManager, databaseManager);
            getCommand("mm").setExecutor(musicCommand);
            getCommand("mm").setTabCompleter(new MusicTabCompleter(sourceManager));

            // 注册 Plugin Messaging Channel
            getServer().getMessenger().registerOutgoingPluginChannel(this, "allmusic:main");
            getServer().getMessenger().registerIncomingPluginChannel(this, "allmusic:main", pluginChannel);

            // 玩家加入时推送缓存设置与当前队列
            registerJoinSync();

            // 注册 PlaceholderAPI 扩展
            if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                try {
                    Object expansion = Class.forName("com.allmusic.placeholder.AllMusicExpansion")
                            .getDeclaredConstructor(AllMusicPlugin.class, PlayQueue.class, QueueScheduler.class)
                            .newInstance(this, playQueue, queueScheduler);
                    // 构造出来只是拿到对象，必须再 register() 才会被 PAPI 收进注册表。
                    // 旧代码漏了这一步（对象建完就扔），所以 %mygomusic_xxx% 一个都解析不出来。
                    Object accepted = expansion.getClass().getMethod("register").invoke(expansion);
                    if (Boolean.FALSE.equals(accepted)) {
                        logger.warning("PlaceholderAPI 扩展注册被拒绝（标识符可能已被占用）");
                    } else {
                        logger.info("PlaceholderAPI 扩展已注册");
                    }
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
                    // 正在播放时补发当前歌曲：玩家是中途进服的，错过了 PLAY 广播，
                    // 不补发就听不到这首歌、也看不到歌词（旧版本这里只同步了队列状态）
                    pluginChannel.sendCurrentPlay(p);
                    pluginChannel.sendQueueSync(p);
                    pluginChannel.sendCacheConfig(p);
                }, 30L);
            }

            // 退出时清掉该玩家的冷却与搜索结果缓存，避免长期开服后逐人堆积
            @org.bukkit.event.EventHandler
            public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
                if (musicCommand != null) {
                    musicCommand.clearPlayerCache(event.getPlayer().getUniqueId());
                }
            }
        }, this);
    }

    @Override
    public void onDisable() {
        logger.info("MygoMusic 正在关闭...");

        // 保存队列到文件（queue.save-on-shutdown 配置此前完全没被读过）
        if (queuePersistence != null && configManager.isSaveOnShutdown()) {
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

    public QueuePersistence getQueuePersistence() {
        return queuePersistence;
    }
}
