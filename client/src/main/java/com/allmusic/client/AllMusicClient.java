package com.allmusic.client;

import com.allmusic.client.audio.AudioPlayer;
import com.allmusic.client.config.ClientConfig;
import com.allmusic.client.gui.MainScreen;
import com.allmusic.client.hud.PlayerHUD;
import com.allmusic.client.lyrics.LyricsRenderer;
import com.allmusic.client.network.ChannelHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class AllMusicClient implements ClientModInitializer {

    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-Client");
    private static AllMusicClient instance;

    private AudioPlayer audioPlayer;
    private LyricsRenderer lyricsRenderer;
    private ChannelHandler channelHandler;
    private ClientConfig config;

    // 快捷键 - 只保留一个打开主界面
    private KeyBinding openMenuKey;

    @Override
    public void onInitializeClient() {
        instance = this;
        logger.info("MygoMusic 客户端初始化...");

        try {
            // 加载配置
            config = new ClientConfig();
            config.load();

            // 初始化音频播放器
            audioPlayer = new AudioPlayer(config);

            // 初始化歌词渲染器
            lyricsRenderer = new LyricsRenderer(config);

            // 初始化网络处理器
            channelHandler = new ChannelHandler(this);

            // 注册快捷键
            registerKeyBindings();

            // 注册事件
            registerEvents();

            // 注册 Plugin Channel
            registerPluginChannel();

            // 注册 HUD
            PlayerHUD.register(config, lyricsRenderer);

            logger.info("MygoMusic 客户端初始化完成!");
        } catch (Exception e) {
            logger.error("MygoMusic 客户端初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 注册快捷键
     */
    private void registerKeyBindings() {
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.allmusic.menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.allmusic"
        ));
    }

    /**
     * 注册事件
     */
    private void registerEvents() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // 处理快捷键 - 打开主界面
            while (openMenuKey.wasPressed()) {
                client.setScreen(new MainScreen(null, config));
            }
            // 歌词由 PlayerHUD 每帧绘制（常驻显示），无需在此 tick 更新
        });
    }

    /**
     * 注册 Plugin Channel
     */
    private void registerPluginChannel() {
        channelHandler.register();
    }

    /**
     * 获取音频播放器
     */
    public AudioPlayer getAudioPlayer() {
        return audioPlayer;
    }

    /**
     * 获取歌词渲染器
     */
    public LyricsRenderer getLyricsRenderer() {
        return lyricsRenderer;
    }

    /**
     * 获取配置
     */
    public ClientConfig getConfig() {
        return config;
    }

    /**
     * 获取网络处理器
     */
    public ChannelHandler getChannelHandler() {
        return channelHandler;
    }

    /**
     * 获取实例
     */
    public static AllMusicClient getInstance() {
        return instance;
    }

    /**
     * 获取日志器
     */
    public static Logger getLogger() {
        return logger;
    }
}
