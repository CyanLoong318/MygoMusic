package com.allmusic.util;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 消息工具类
 */
public class MessageUtil {
    private static final String PREFIX = "§6[MygoMusic] §r";

    /**
     * 发送带前缀的消息
     */
    public static void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + message);
    }

    /**
     * 发送成功消息
     */
    public static void sendSuccess(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + "§a" + message);
    }

    /**
     * 发送错误消息
     */
    public static void sendError(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + "§c" + message);
    }

    /**
     * 发送警告消息
     */
    public static void sendWarning(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + "§e" + message);
    }

    /**
     * 发送信息消息
     */
    public static void sendInfo(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + "§7" + message);
    }

    /**
     * 发送可点击的链接消息
     */
    public static void sendClickableLink(Player player, String message, String url) {
        TextComponent text = new TextComponent(PREFIX + message);
        text.setColor(ChatColor.GREEN);
        text.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        text.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§e点击打开链接").create()));
        player.spigot().sendMessage(text);
    }

    /**
     * 发送带复制功能的消息
     */
    public static void sendCopyable(Player player, String message, String copyText) {
        TextComponent text = new TextComponent(PREFIX + message);
        text.setColor(ChatColor.YELLOW);
        text.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, copyText));
        text.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§e点击复制").create()));
        player.spigot().sendMessage(text);
    }

    /**
     * 格式化歌曲信息
     */
    public static String formatSongInfo(String title, String artist, String source) {
        return String.format("§e%s §7- §f%s §7(%s)", title, artist, source);
    }

    /**
     * 格式化播放信息
     */
    public static String formatNowPlaying(String title, String artist, String source, String requester) {
        return String.format("§6正在播放: §e%s §7- §f%s §7(%s) §7[§a%s§7]", title, artist, source, requester);
    }

    /**
     * 格式化队列条目
     */
    public static String formatQueueItem(int index, String title, String artist, String source, String requester) {
        return String.format("§7#%d §e%s §7- §f%s §7(%s) §7[§a%s§7]", index, title, artist, source, requester);
    }
}
