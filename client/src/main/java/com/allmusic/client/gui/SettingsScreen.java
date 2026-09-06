package com.allmusic.client.gui;

import com.allmusic.client.AllMusicClient;
import com.allmusic.client.audio.AudioPlayer;
import com.allmusic.client.config.ClientConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 设置界面
 */
public class SettingsScreen extends Screen {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-GUI");

    private final Screen parent;
    private final ClientConfig config;

    public SettingsScreen(Screen parent, ClientConfig config) {
        super(Text.literal("MygoMusic - 设置"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        int y = 30;
        final int rowH = 22;

        // 音量滑块 (0-100, 实时生效)
        addDrawableChild(new SliderWidget(width / 2 - 100, y, 200, 20, Text.literal("音量: " + config.getVolume()), config.getVolume() / 100.0) {
            @Override
            protected void updateMessage() {
                setMessage(Text.literal("音量: " + (int) (value * 100)));
            }

            @Override
            protected void applyValue() {
                int v = (int) (value * 100);
                AudioPlayer audioPlayer = AllMusicClient.getInstance() == null ? null : AllMusicClient.getInstance().getAudioPlayer();
                if (audioPlayer != null) {
                    audioPlayer.setVolume(v); // 同步实时音量并保存
                } else {
                    config.setVolume(v);
                    config.save();
                }
            }
        });

        y += rowH;

        // 歌词开关
        addDrawableChild(ButtonWidget.builder(
                Text.literal("歌词显示: " + (config.isLyricsEnabled() ? "§a开启" : "§c关闭")),
                button -> {
                    config.setLyricsEnabled(!config.isLyricsEnabled());
                    button.setMessage(Text.literal("歌词显示: " + (config.isLyricsEnabled() ? "§a开启" : "§c关闭")));
                    config.save();
                }
        ).dimensions(width / 2 - 100, y, 200, 20).build());

        y += rowH;

        // 翻译开关
        addDrawableChild(ButtonWidget.builder(
                Text.literal("显示翻译: " + (config.isShowTranslation() ? "§a开启" : "§c关闭")),
                button -> {
                    config.setShowTranslation(!config.isShowTranslation());
                    button.setMessage(Text.literal("显示翻译: " + (config.isShowTranslation() ? "§a开启" : "§c关闭")));
                    config.save();
                }
        ).dimensions(width / 2 - 100, y, 200, 20).build());

        y += rowH;

        // 缓存由服务端统一控制（config.yml 的 client-cache 段下发），客户端不再提供缓存设置
        y += rowH + 4;

        // 歌词显示位置区块标题
        contextHintY = y;
        y += rowH;

        // 歌词在游戏界面显示位置调整（X: 相对屏幕中心左右偏移, Y: 相对默认底部位置上下偏移）
        final int offsetRange = 240;
        addDrawableChild(new SliderWidget(width / 2 - 100, y, 200, 20,
                Text.literal("歌词横移: " + config.getLyricsOffsetX()),
                (config.getLyricsOffsetX() + offsetRange) / (offsetRange * 2.0)) {
            @Override
            protected void updateMessage() {
                setMessage(Text.literal("歌词横移: " + (Math.round(value * offsetRange * 2) - offsetRange)));
            }

            @Override
            protected void applyValue() {
                config.setLyricsOffsetX((int) (Math.round(value * offsetRange * 2) - offsetRange));
                config.save();
            }
        });

        y += rowH;

        addDrawableChild(new SliderWidget(width / 2 - 100, y, 200, 20,
                Text.literal("歌词纵移: " + config.getLyricsOffsetY()),
                (config.getLyricsOffsetY() + offsetRange) / (offsetRange * 2.0)) {
            @Override
            protected void updateMessage() {
                setMessage(Text.literal("歌词纵移: " + (Math.round(value * offsetRange * 2) - offsetRange)));
            }

            @Override
            protected void applyValue() {
                config.setLyricsOffsetY((int) (Math.round(value * offsetRange * 2) - offsetRange));
                config.save();
            }
        });

        y += rowH + 6;

        // 返回按钮：放在最后一个控件之后，且不超出屏幕底部
        int backY = Math.min(y, Math.max(20, height - 28));
        addDrawableChild(ButtonWidget.builder(Text.literal("返回"), button -> {
            client.setScreen(parent);
        }).dimensions(width / 2 - 50, backY, 100, 20).build());
    }

    private int contextHintY = 0;

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        // 标题
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 15, 0xFFFFFF);

        // 歌词显示位置区块标题
        if (contextHintY > 0) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("§8歌词显示位置调整"), width / 2, contextHintY, 0xAAAAAA);
        }
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
