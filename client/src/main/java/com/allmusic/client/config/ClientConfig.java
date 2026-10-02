package com.allmusic.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * 客户端配置
 */
public class ClientConfig {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "allmusic-client.json";

    // 音频设置
    private int volume = 80;

    // 歌词设置
    private boolean lyricsEnabled = true;
    private boolean showTranslation = true;

    // 缓存由服务端统一控制（通过通道下发），客户端不再本地配置缓存

    // 歌词在游戏界面的显示位置偏移 (相对默认位置: 屏幕中央水平、距底部约58px处)
    private int lyricsOffsetX = 0; // 正=右, 负=左
    private int lyricsOffsetY = 0; // 正=下, 负=上

    private File configFile;

    public ClientConfig() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE).toFile();
    }

    /**
     * 加载配置
     */
    public void load() {
        if (!configFile.exists()) {
            save();
            return;
        }

        try (Reader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
            JsonObject json = gson.fromJson(reader, JsonObject.class);

            if (json.has("volume")) volume = json.get("volume").getAsInt();
            if (json.has("lyricsEnabled")) lyricsEnabled = json.get("lyricsEnabled").getAsBoolean();
            if (json.has("showTranslation")) showTranslation = json.get("showTranslation").getAsBoolean();
            if (json.has("lyricsOffsetX")) lyricsOffsetX = json.get("lyricsOffsetX").getAsInt();
            if (json.has("lyricsOffsetY")) lyricsOffsetY = json.get("lyricsOffsetY").getAsInt();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 保存配置
     */
    public void save() {
        JsonObject json = new JsonObject();
        json.addProperty("volume", volume);
        json.addProperty("lyricsEnabled", lyricsEnabled);
        json.addProperty("showTranslation", showTranslation);
        json.addProperty("lyricsOffsetX", lyricsOffsetX);
        json.addProperty("lyricsOffsetY", lyricsOffsetY);

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
            gson.toJson(json, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Getters and Setters
    public int getVolume() { return volume; }
    public void setVolume(int volume) { this.volume = Math.max(0, Math.min(100, volume)); }

    public boolean isLyricsEnabled() { return lyricsEnabled; }
    public void setLyricsEnabled(boolean lyricsEnabled) { this.lyricsEnabled = lyricsEnabled; }

    public boolean isShowTranslation() { return showTranslation; }
    public void setShowTranslation(boolean showTranslation) { this.showTranslation = showTranslation; }

    public int getLyricsOffsetX() { return lyricsOffsetX; }
    public void setLyricsOffsetX(int lyricsOffsetX) { this.lyricsOffsetX = Math.max(-500, Math.min(500, lyricsOffsetX)); }

    public int getLyricsOffsetY() { return lyricsOffsetY; }
    public void setLyricsOffsetY(int lyricsOffsetY) { this.lyricsOffsetY = Math.max(-500, Math.min(500, lyricsOffsetY)); }
}
