package com.allmusic.client.gui;

import com.allmusic.client.AllMusicClient;
import com.allmusic.client.audio.AudioPlayer;
import com.allmusic.client.config.ClientConfig;
import com.allmusic.client.network.ChannelHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import org.slf4j.Logger;

/**
 * 主界面 - 打开搜索、队列、设置子界面
 */
public class MainScreen extends Screen {
    private static final Logger logger = AllMusicClient.getLogger();

    private final Screen parent;
    private final ClientConfig config;
    /** 暂停/继续按钮：根据服务端队列状态决定文案与命令 */
    private ButtonWidget pauseButton;

    public MainScreen(Screen parent, ClientConfig config) {
        super(Text.of("MygoMusic"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;

        int btnWidth = 120;
        int btnHeight = 25;
        int gap = 8;
        int startY = 60;

        // 搜索按钮 - 打开搜索子界面
        this.addDrawableChild(ButtonWidget.builder(Text.of("§e搜索歌曲"), button -> {
            if (this.client != null) {
                this.client.setScreen(new SearchScreen(this));
            }
        }).dimensions(centerX - btnWidth / 2, startY, btnWidth, btnHeight).build());

        // 队列按钮 - 打开队列子界面
        this.addDrawableChild(ButtonWidget.builder(Text.of("§a播放队列"), button -> {
            if (this.client != null) {
                this.client.setScreen(new QueueScreen(this));
            }
        }).dimensions(centerX - btnWidth / 2, startY + btnHeight + gap, btnWidth, btnHeight).build());

        // 设置按钮 - 打开设置子界面
        this.addDrawableChild(ButtonWidget.builder(Text.of("§b播放设置"), button -> {
            if (this.client != null) {
                this.client.setScreen(new SettingsScreen(this, config));
            }
        }).dimensions(centerX - btnWidth / 2, startY + (btnHeight + gap) * 2, btnWidth, btnHeight).build());

        // 播放控制
        int controlY = startY + (btnHeight + gap) * 3 + 10;
        AudioPlayer audioPlayer = AllMusicClient.getInstance().getAudioPlayer();

        // 暂停/继续：改为全服同步，由服务端 mm pause / mm continue 统一处理。
        // 按钮文案在 render() 里根据 QueueState 实时刷新（正在播放→暂停；已暂停/未播放→继续）。
        this.pauseButton = ButtonWidget.builder(Text.of("§c暂停"), button -> {
            QueueState q = QueueState.get();
            if (q.isPlaying() && !q.isPaused()) {
                sendCommand("mm pause");
            } else {
                sendCommand("mm continue");
            }
        }).dimensions(centerX - btnWidth / 2 - 62, controlY, 60, 20).build();
        this.addDrawableChild(this.pauseButton);

        this.addDrawableChild(ButtonWidget.builder(Text.of("§e上一首"), button -> {
            sendCommand("mm prev");
        }).dimensions(centerX - 30, controlY, 60, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.of("§a下一首"), button -> {
            sendCommand("mm next");
        }).dimensions(centerX + 32, controlY, 60, 20).build());

        // 第二行控制：歌词开关 + 音量滑块，作为居中整块对齐
        int lw = 90, sw = 142, cgap = 6;
        int blkW = lw + cgap + sw;
        int blkX = centerX - blkW / 2;
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(config.isLyricsEnabled() ? "§a歌词: 开" : "§c歌词: 关"),
                button -> {
                    config.setLyricsEnabled(!config.isLyricsEnabled());
                    config.save();
                    button.setMessage(Text.of(config.isLyricsEnabled() ? "§a歌词: 开" : "§c歌词: 关"));
                }).dimensions(blkX, controlY + 25, lw, 20).build());

        // 音量滑块 (0-100, 实时生效并保存)
        this.addDrawableChild(new SliderWidget(blkX + lw + cgap, controlY + 25, sw, 20,
                Text.of("§b音量: " + config.getVolume() + "%"), config.getVolume() / 100.0) {
            @Override
            protected void updateMessage() {
                setMessage(Text.literal("§b音量: " + (int) (value * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                int v = (int) (value * 100);
                audioPlayer.setVolume(v);
                config.setVolume(v);
                config.save();
            }
        });

        // 关闭按钮
        this.addDrawableChild(ButtonWidget.builder(Text.of("§7关闭"), button -> close())
                .dimensions(centerX - 25, controlY + 50, 50, 20).build());

        // 主动拉取一次服务端播放/暂停状态，保证「暂停/继续」按钮文案不过期
        ChannelHandler.requestQueueSync();
    }

    private void sendCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand(command);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;

        // 先绘制控件，再绘制标题，避免标题被控件覆盖
        super.render(context, mouseX, mouseY, delta);

        // 标题（顶部）
        context.drawCenteredTextWithShadow(this.textRenderer, Text.of("§6§lMygoMusic 点歌系统"), centerX, 15, 0xFFFFFF);

        // 当前播放信息（标题下方，按钮上方）
        AudioPlayer audioPlayer = AllMusicClient.getInstance().getAudioPlayer();
        QueueState queueState = QueueState.get();
        int infoY = 38;
        if (audioPlayer.isPlaying()) {
            String title = audioPlayer.getCurrentTitle();
            String artist = audioPlayer.getCurrentArtist();
            String prefix = queueState.isPaused() ? "§c已暂停: §f" : "§e正在播放: §f";
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.of(prefix + title), centerX, infoY, 0xFFFFFF);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.of("§7歌手: §f" + artist), centerX, infoY + 12, 0xAAAAAA);
        } else {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.of("§7当前没有正在播放"), centerX, infoY, 0xAAAAAA);
        }

        // 按服务端队列状态刷新「暂停/继续」按钮文案
        // 正在播放且未暂停 → 暂停；否则（已暂停或未播放）→ 继续
        if (this.pauseButton != null) {
            boolean playable = queueState.isPlaying() && !queueState.isPaused();
            this.pauseButton.setMessage(Text.of(playable ? "§c暂停" : "§a继续"));
        }
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
