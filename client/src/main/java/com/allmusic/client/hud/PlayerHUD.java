package com.allmusic.client.hud;

import com.allmusic.client.AllMusicClient;
import com.allmusic.client.audio.AudioPlayer;
import com.allmusic.client.config.ClientConfig;
import com.allmusic.client.lyrics.LyricsRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.client.render.RenderTickCounter;

import java.util.List;

/**
 * 玩家 HUD：底部常驻歌词（左上角信息框已按需求整体移除）
 */
public class PlayerHUD implements HudRenderCallback {
    private final ClientConfig config;
    private final LyricsRenderer lyricsRenderer;

    public PlayerHUD(ClientConfig config, LyricsRenderer lyricsRenderer) {
        this.config = config;
        this.lyricsRenderer = lyricsRenderer;
    }

    @Override
    public void onHudRender(DrawContext drawContext, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        AudioPlayer audioPlayer = AllMusicClient.getInstance().getAudioPlayer();

        // 底部常驻歌词
        if (config.isLyricsEnabled()) {
            drawLyrics(drawContext, client, audioPlayer);
        }
    }

    /**
     * 底部常驻歌词（正在播放时显示，每帧刷新，不随 actionbar 超时消失）
     */
    private void drawLyrics(DrawContext drawContext, MinecraftClient client, AudioPlayer audioPlayer) {
        if (!audioPlayer.isPlaying()) return;

        long position = audioPlayer.getPlaybackPosition();
        long duration = audioPlayer.getCurrentDuration();

        List<String> lines = lyricsRenderer.buildDisplayLines(position, duration);
        if (lines.isEmpty()) return;

        int scaledWidth = client.getWindow().getScaledWidth();
        int scaledHeight = client.getWindow().getScaledHeight();

        // 歌词位置可在设置中调整（偏移量：X 相对屏幕中心，Y 相对默认底部位置）
        int centerX = scaledWidth / 2 + config.getLyricsOffsetX();
        int baseY = (scaledHeight - 58) + config.getLyricsOffsetY();

        // 歌词区块底部靠近快捷栏上方
        int lineHeight = 10;
        int startY = baseY - (lines.size() - 1) * lineHeight;

        for (int i = 0; i < lines.size(); i++) {
            drawContext.drawCenteredTextWithShadow(client.textRenderer,
                    Text.literal(lines.get(i)), centerX, startY + i * lineHeight, 0xFFFFFF);
        }
    }

    /**
     * 注册 HUD
     */
    public static void register(ClientConfig config, LyricsRenderer lyricsRenderer) {
        HudRenderCallback.EVENT.register(new PlayerHUD(config, lyricsRenderer));
    }
}
