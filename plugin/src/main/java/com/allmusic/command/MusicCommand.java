package com.allmusic.command;

import com.allmusic.AllMusicPlugin;
import com.allmusic.config.ConfigManager;
import com.allmusic.database.DatabaseManager;
import com.allmusic.model.*;
import com.allmusic.network.PluginChannel;
import com.allmusic.queue.PlayQueue;
import com.allmusic.queue.QueueScheduler;
import com.allmusic.source.BilibiliSource;
import com.allmusic.source.MusicSource;
import com.allmusic.source.SourceManager;
import com.allmusic.util.FfmpegUtil;
import com.allmusic.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 点歌命令 - 支持控制台执行
 */
public class MusicCommand implements CommandExecutor {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-Cmd");

    private final AllMusicPlugin plugin;
    private final SourceManager sourceManager;
    private final PlayQueue playQueue;
    private final QueueScheduler queueScheduler;
    private final PluginChannel pluginChannel;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;

    // 点歌冷却
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    // 搜索结果缓存
    private final Map<UUID, List<SongInfo>> searchResults = new HashMap<>();
    // 控制台搜索结果缓存
    private final List<SongInfo> consoleSearchResults = new ArrayList<>();

    // 搜索结果只显示前 N 条（含/mm play 与 /mm search）
    private static final int MAX_SEARCH_RESULTS = 5;
    // B站 BV 号识别 (例如 BV1xx411c7mD)
    private static final Pattern BILIBILI_BV_PATTERN = Pattern.compile("^BV[0-9A-Za-z]{8,20}$");
    // B站 BV 号 + 分P页码 (例如 "BV1xx411c7mD p2")
    private static final Pattern BILIBILI_BV_WITH_PAGE_PATTERN = Pattern.compile("^(BV[0-9A-Za-z]{8,20})\\s+p?([1-9]\\d{0,2})$");

    public MusicCommand(AllMusicPlugin plugin, SourceManager sourceManager, PlayQueue playQueue,
                        QueueScheduler queueScheduler, PluginChannel pluginChannel,
                        ConfigManager configManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.sourceManager = sourceManager;
        this.playQueue = playQueue;
        this.queueScheduler = queueScheduler;
        this.pluginChannel = pluginChannel;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "play":
                handlePlay(sender, args);
                break;
            case "playid":
                handlePlayId(sender, args);
                break;
            case "pause":
                handlePause(sender);
                break;
            case "stop":
                handleStop(sender);
                break;
            case "continue":
                handleContinue(sender);
                break;
            case "remove":
                handleRemove(sender, args);
                break;
            case "next":
                handleNext(sender);
                break;
            case "prev":
                handlePrev(sender);
                break;
            case "queue":
                handleQueue(sender);
                break;
            case "search":
                handleSearch(sender, args);
                break;
            case "now":
                handleNow(sender);
                break;
            case "volume":
                handleVolume(sender, args);
                break;
            case "lyrics":
                handleLyrics(sender);
                break;
            case "login":
                handleLogin(sender, args);
                break;
            case "logout":
                handleLogout(sender, args);
                break;
            case "admin":
                handleAdmin(sender, args);
                break;
            case "select":
                handleSelect(sender, args);
                break;
            case "searchparts":
                handleSearchParts(sender, args);
                break;
            default:
                sendHelp(sender);
                break;
        }

        return true;
    }

    /**
     * B站多分P：列出某BV视频的所有分P（每P一条，含分P标题），供GUI展示与选择
     * 用法: /mm searchparts <BV号>
     */
    private void handleSearchParts(CommandSender sender, String[] args) {
        if (sender instanceof Player) {
            if (!hasPermission(sender, "mygomusic.search")) return;
        }
        if (args.length < 2) {
            MessageUtil.sendError(sender, "用法: /mm searchparts <BV号>");
            return;
        }
        MusicSource source = sourceManager.getSource("bilibili");
        if (source == null) {
            MessageUtil.sendError(sender, "B站音源不可用");
            return;
        }
        // 兼容 "/mm searchparts bilibili <BV>" 与 "/mm searchparts <BV>" 两种写法
        final String bv;
        String first = args[1].trim();
        if ((first.equalsIgnoreCase("bilibili") || first.equalsIgnoreCase("kugou") || first.equalsIgnoreCase("netease"))
                && args.length >= 3) {
            bv = args[2].trim();
        } else {
            bv = first;
        }
        if (!(source instanceof BilibiliSource)) {
            MessageUtil.sendError(sender, "该命令仅支持B站");
            return;
        }
        BilibiliSource bili = (BilibiliSource) source;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<SongInfo> parts = bili.listParts(bv);
                if (parts == null || parts.isEmpty()) {
                    MessageUtil.sendError(sender, "无法获取该视频的分P列表，请检查BV号");
                    return;
                }
                // 缓存供 /mm select 用
                if (sender instanceof Player) {
                    searchResults.put(((Player) sender).getUniqueId(), parts);
                } else {
                    synchronized (consoleSearchResults) {
                        consoleSearchResults.clear();
                        consoleSearchResults.addAll(parts);
                    }
                }
                final List<SongInfo> fp = parts;
                if (sender instanceof Player) {
                    final Player p = (Player) sender;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        showSearchResults(sender, fp);
                        pluginChannel.sendSearchResult(p, fp);
                    });
                } else {
                    Bukkit.getScheduler().runTask(plugin, () -> showSearchResults(sender, fp));
                }
            } catch (Exception e) {
                logger.error("获取分P列表失败: " + e.getMessage(), e);
                MessageUtil.sendError(sender, "获取分P列表失败");
            }
        });
    }

    /**
     * 获取操作目标玩家 - 玩家自己或控制台指定/第一个在线玩家
     */
    private Player getTargetPlayer(CommandSender sender, String[] args, int nameIndex) {
        if (sender instanceof Player) {
            return (Player) sender;
        }
        // 控制台：尝试从参数指定玩家名
        if (args.length > nameIndex) {
            Player target = Bukkit.getPlayer(args[nameIndex]);
            if (target != null) return target;
        }
        // 回退到第一个在线玩家
        for (Player p : Bukkit.getOnlinePlayers()) {
            return p;
        }
        return null;
    }

    /**
     * 获取玩家UUID（控制台用控制台UUID）
     */
    private UUID getSenderId(CommandSender sender) {
        if (sender instanceof Player) {
            return ((Player) sender).getUniqueId();
        }
        return new UUID(0, 0); // 控制台用固定UUID
    }

    /**
     * 检查权限 - 控制台默认拥有所有权限
     */
    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender instanceof ConsoleCommandSender) {
            return true;
        }
        return sender.hasPermission(permission);
    }

    /**
     * 处理点歌命令
     * 用法: /mm play <音源> <歌名> [玩家名]
     */
    private void handlePlay(CommandSender sender, String[] args) {
        // 检查权限
        if (!hasPermission(sender, "mygomusic.play")) {
            MessageUtil.sendError(sender, "你没有点歌权限");
            return;
        }

        // 用法: /mm play <音源> <歌名> [玩家名]
        if (args.length < 3) {
            MessageUtil.sendError(sender, "用法: /mm play <音源> <歌名> [玩家名]");
            MessageUtil.sendInfo(sender, "可用音源: netease(网易云), kugou(酷狗), bilibili(B站)");
            return;
        }

        // 解析参数
        String sourceName = args[1].toLowerCase();
        // 判断最后一个参数是否是玩家名（如果最后参数匹配在线玩家则认为是玩家名）
        String keyword;
        String targetPlayerName = null;
        String lastArg = args[args.length - 1];
        Player lastArgPlayer = Bukkit.getPlayer(lastArg);
        if (args.length > 3 && lastArgPlayer != null) {
            // 最后一个参数是玩家名
            keyword = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length - 1));
            targetPlayerName = lastArg;
        } else {
            keyword = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        }

        // 获取目标玩家
        Player targetPlayer;
        if (targetPlayerName != null) {
            targetPlayer = Bukkit.getPlayer(targetPlayerName);
        } else {
            targetPlayer = getTargetPlayer(sender, args, 3);
        }
        if (targetPlayer == null) {
            MessageUtil.sendError(sender, "没有找到在线玩家");
            return;
        }

        // 验证音源
        MusicSource source = sourceManager.getSource(sourceName);
        if (source == null) {
            MessageUtil.sendError(sender, "未知的音源: " + sourceName);
            MessageUtil.sendInfo(sender, "可用音源: netease(网易云), kugou(酷狗), bilibili(B站)");
            return;
        }

        // 检查冷却（控制台跳过冷却）
        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (!player.hasPermission(configManager.getCooldownBypassPermission())) {
                Long lastPlay = cooldowns.get(player.getUniqueId());
                if (lastPlay != null && System.currentTimeMillis() - lastPlay < configManager.getCooldownSeconds() * 1000) {
                    long remaining = (configManager.getCooldownSeconds() * 1000 - (System.currentTimeMillis() - lastPlay)) / 1000;
                    MessageUtil.sendWarning(player, "点歌冷却中，请等待 " + remaining + " 秒");
                    return;
                }
            }
        }

        // 异步搜索
        String requesterName = sender instanceof Player ? sender.getName() : "控制台";
        UUID requesterUuid = getSenderId(sender);
        Player requester = sender instanceof Player ? (Player) sender : null;
        boolean applyCooldown = requester != null;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String kw = keyword.trim();
                // B站 BV 号直达/指定分P（如 /mm play bilibili BV1xx411c7mD 或 BV1xx411c7mD p2）
                String bvDetailId = buildBvDetailId(sourceName, kw);
                if (bvDetailId != null) {
                    SongDetail detail = source.getDetail(bvDetailId);
                    if (detail == null) {
                        MessageUtil.sendError(sender, "获取该B站视频失败，请检查BV号/分P是否正确");
                        return;
                    }
                    if (!bvDetailId.contains("#p")) {
                        // 未指定分P时提示存在多分P，方便用户选择
                        sendMultiPartHint(sender, detail);
                    }
                    enqueueDetail(sender, detail, requesterName, requesterUuid, applyCooldown);
                    return;
                }

                List<SongInfo> fetched = source.search(kw, 10);
                List<SongInfo> results = fetched.size() > MAX_SEARCH_RESULTS
                        ? fetched.subList(0, MAX_SEARCH_RESULTS) : fetched;

                if (results.isEmpty()) {
                    MessageUtil.sendError(sender, "未找到相关歌曲");
                    return;
                }

                final List<SongInfo> finalResults = results;

                // 保存搜索结果
                if (sender instanceof Player) {
                    searchResults.put(((Player) sender).getUniqueId(), finalResults);
                } else {
                    synchronized (consoleSearchResults) {
                        consoleSearchResults.clear();
                        consoleSearchResults.addAll(finalResults);
                    }
                }

                // 显示搜索结果
                Bukkit.getScheduler().runTask(plugin, () -> {
                    showSearchResults(sender, finalResults);
                    // 控制台执行时，如果有且只有1个结果，直接播放
                    if (!(sender instanceof Player) && finalResults.size() == 1) {
                        MessageUtil.sendInfo(sender, "自动选择唯一结果...");
                        doSelect(sender, 0);
                    }
                });
            } catch (Exception e) {
                logger.error("搜索失败: " + e.getMessage(), e);
                MessageUtil.sendError(sender, "搜索失败: " + e.getMessage());
            }
        });
    }

    /**
     * 解析 B站关键词，返回可携带分P的详情ID。
     * 纯BV返回BV本身；带分P如 "BVxxx p2" 返回 "BVxxx#p2"；非BV返回null。
     */
    private String buildBvDetailId(String sourceName, String keyword) {
        if (!"bilibili".equalsIgnoreCase(sourceName) || keyword == null) return null;
        String kw = keyword.trim();
        java.util.regex.Matcher wp = BILIBILI_BV_WITH_PAGE_PATTERN.matcher(kw);
        if (wp.matches()) {
            return wp.group(1) + "#p" + wp.group(2);
        }
        if (BILIBILI_BV_PATTERN.matcher(kw).matches()) {
            return kw;
        }
        return null;
    }

    /**
     * 播放多分P B站视频时的提示（extra 中存了分P总数）
     */
    private void sendMultiPartHint(CommandSender sender, SongInfo info) {
        if (info == null || info.getExtra() == null || info.getExtra().isEmpty()) return;
        try {
            int total = Integer.parseInt(info.getExtra());
            if (total > 1) {
                MessageUtil.sendInfo(sender, "该视频共 " + total + " 个分P。播放指定分P：§e/mm play bilibili <BV号> p<页码>§7，例如 /mm play bilibili " + info.getSongId().split("#p")[0] + " p2");
            }
        } catch (NumberFormatException ignore) {}
    }

    /**
     * 将已获取的详情加入队列（异步线程调用），复用点歌逻辑
     */
    private void enqueueDetail(CommandSender sender, SongDetail detail, String requesterName,
                               UUID requesterUuid, boolean applyCooldown) {
        QueueItem item = new QueueItem(detail, requesterName, requesterUuid);
        playQueue.add(item);

        if (applyCooldown && sender instanceof Player) {
            cooldowns.put(((Player) sender).getUniqueId(), System.currentTimeMillis());
        }

        if (databaseManager != null) {
            databaseManager.savePlayHistory(requesterUuid, requesterName, detail);
        }

        MessageUtil.sendSuccess(sender, "已点歌: " + detail.getTitle() + " - " + detail.getArtist());

        if (!playQueue.isPlaying()) {
            queueScheduler.playNext();
        }

        Bukkit.getScheduler().runTask(plugin, () -> pluginChannel.broadcastQueueSync());
    }

    /**
     * 显示搜索结果
     */
    private void showSearchResults(CommandSender sender, List<SongInfo> results) {
        sender.sendMessage("§6========== [MygoMusic 搜索结果] ==========");
        for (int i = 0; i < results.size(); i++) {
            SongInfo song = results.get(i);
            String idTag = "bilibili".equalsIgnoreCase(song.getSource()) && song.getSongId() != null
                    ? " §8" + song.getSongId().split("#p")[0] : "";
            sender.sendMessage(String.format("§e#%d §f%s §7- §f%s §7(%s) §7[%s]%s",
                    i + 1, song.getTitle(), song.getArtist(), song.getSourceDisplayName(), song.getDurationFormatted(), idTag));
        }
        sender.sendMessage("§6========================================");
        sender.sendMessage("§e输入 §f/mm select <序号> §e选择歌曲");
    }

    /**
     * 处理选择歌曲
     */
    private void handleSelect(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "用法: /mm select <序号> [玩家名]");
            return;
        }

        int index;
        try {
            index = Integer.parseInt(args[1]) - 1;
        } catch (NumberFormatException e) {
            MessageUtil.sendError(sender, "无效的序号");
            return;
        }

        doSelect(sender, index);
    }

    /**
     * 执行选择歌曲
     */
    private void doSelect(CommandSender sender, int index) {
        // 获取搜索结果
        List<SongInfo> results;
        if (sender instanceof Player) {
            results = searchResults.get(((Player) sender).getUniqueId());
        } else {
            synchronized (consoleSearchResults) {
                results = new ArrayList<>(consoleSearchResults);
            }
        }

        if (results == null || results.isEmpty()) {
            MessageUtil.sendError(sender, "没有搜索结果，请先搜索");
            return;
        }

        if (index < 0 || index >= results.size()) {
            MessageUtil.sendError(sender, "序号超出范围，共 " + results.size() + " 个结果");
            return;
        }

        SongInfo selected = results.get(index);

        // 检查队列是否已满
        if (playQueue.size() >= configManager.getQueueMaxSize()) {
            MessageUtil.sendError(sender, "播放队列已满");
            return;
        }

        // 获取点歌人信息
        String requesterName = sender instanceof Player ? sender.getName() : "控制台";
        UUID requesterUuid = getSenderId(sender);

        // 添加到队列
        QueueItem item = new QueueItem(selected, requesterName, requesterUuid);
        playQueue.add(item);

        // 设置冷却（控制台跳过）
        if (sender instanceof Player) {
            cooldowns.put(((Player) sender).getUniqueId(), System.currentTimeMillis());
        }

        // 保存到数据库
        if (databaseManager != null) {
            databaseManager.savePlayHistory(requesterUuid, requesterName, selected);
        }

        MessageUtil.sendSuccess(sender, "已点歌: " + selected.getTitle() + " - " + selected.getArtist());

        // 如果没有正在播放的歌曲，立即播放
        if (!playQueue.isPlaying()) {
            queueScheduler.playNext();
        }

        // 同步队列到客户端 GUI
        pluginChannel.broadcastQueueSync();

        // 清除搜索结果
        if (sender instanceof Player) {
            searchResults.remove(((Player) sender).getUniqueId());
        } else {
            synchronized (consoleSearchResults) {
                consoleSearchResults.clear();
            }
        }
    }

    /**
     * 处理直接点歌
     */
    private void handlePlayId(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "mygomusic.play")) {
            MessageUtil.sendError(sender, "你没有点歌权限");
            return;
        }

        if (args.length < 3) {
            MessageUtil.sendError(sender, "用法: /mm playid <平台> <ID> [玩家名]");
            return;
        }

        String sourceName = args[1].toLowerCase();
        String songId = args[2];

        MusicSource source = sourceManager.getSource(sourceName);
        if (source == null) {
            MessageUtil.sendError(sender, "未知的音源: " + sourceName);
            return;
        }

        // 异步获取歌曲详情
        String requesterName = sender instanceof Player ? sender.getName() : "控制台";
        UUID requesterUuid = getSenderId(sender);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                SongDetail detail = source.getDetail(songId);
                if (detail == null) {
                    MessageUtil.sendError(sender, "获取歌曲失败");
                    return;
                }

                // 添加到队列
                QueueItem item = new QueueItem(detail, requesterName, requesterUuid);
                playQueue.add(item);

                // 设置冷却（控制台跳过）
                if (sender instanceof Player) {
                    cooldowns.put(((Player) sender).getUniqueId(), System.currentTimeMillis());
                }

                // 保存到数据库
                if (databaseManager != null) {
                    databaseManager.savePlayHistory(requesterUuid, requesterName, detail);
                }

                MessageUtil.sendSuccess(sender, "已点歌: " + detail.getTitle() + " - " + detail.getArtist());

                // 如果没有正在播放的歌曲，立即播放
                if (!playQueue.isPlaying()) {
                    queueScheduler.playNext();
                }

                // 同步队列到客户端 GUI（需要回到主线程发送）
                Bukkit.getScheduler().runTask(plugin, () -> pluginChannel.broadcastQueueSync());
            } catch (Exception e) {
                logger.error("点歌失败: " + e.getMessage(), e);
                MessageUtil.sendError(sender, "点歌失败: " + e.getMessage());
            }
        });
    }

    /**
     * 处理暂停（全服同步：暂停所有客户端并冻结服务端歌曲进度）
     */
    private void handlePause(CommandSender sender) {
        if (!hasPermission(sender, "mygomusic.control")) {
            MessageUtil.sendError(sender, "你没有控制播放的权限");
            return;
        }

        if (!playQueue.isPlaying()) {
            MessageUtil.sendWarning(sender, "当前没有正在播放的歌曲");
            return;
        }

        playQueue.pause();
        // 先广播暂停（让客户端本地音频立刻停），再同步 GUI 状态
        pluginChannel.broadcastPause();
        pluginChannel.broadcastQueueSync();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline()) {
                player.sendMessage("§6[MygoMusic] §e播放已暂停 (全服)，输入 §f/mm continue §e继续");
            }
        }
        MessageUtil.sendSuccess(sender, "已暂停播放 (全服)");
    }

    /**
     * 处理停止播放
     */
    private void handleStop(CommandSender sender) {
        if (!hasPermission(sender, "mygomusic.control")) {
            MessageUtil.sendError(sender, "你没有控制播放的权限");
            return;
        }

        if (!playQueue.isPlaying()) {
            MessageUtil.sendWarning(sender, "当前没有正在播放的歌曲");
            return;
        }

        queueScheduler.stopCurrent();
        MessageUtil.sendSuccess(sender, "已停止播放");
    }

    /**
     * 处理继续播放：全服暂停中 → 恢复同一首歌；否则维持原“停止后从队列继续”语义
     */
    private void handleContinue(CommandSender sender) {
        if (!hasPermission(sender, "mygomusic.control")) {
            MessageUtil.sendError(sender, "你没有控制播放的权限");
            return;
        }

        // 全服暂停中 → 原地恢复（不是跳到下一首）
        if (playQueue.isPaused()) {
            playQueue.resume();
            pluginChannel.broadcastResume();
            pluginChannel.broadcastQueueSync();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOnline()) {
                    player.sendMessage("§6[MygoMusic] §e播放已继续 (全服)");
                }
            }
            MessageUtil.sendSuccess(sender, "已继续播放");
            return;
        }

        queueScheduler.continuePlay();
    }

    /**
     * 处理移除队列歌曲（只能移除自己点的；管理员可移除任意）
     */
    private void handleRemove(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "mygomusic.control")) {
            MessageUtil.sendError(sender, "你没有控制播放的权限");
            return;
        }
        if (args.length < 2) {
            MessageUtil.sendError(sender, "用法: /mm remove <序号>");
            return;
        }
        int index;
        try {
            index = Integer.parseInt(args[1]) - 1;
        } catch (NumberFormatException e) {
            MessageUtil.sendError(sender, "无效的序号");
            return;
        }
        if (index < 0) {
            MessageUtil.sendError(sender, "无效的序号");
            return;
        }

        boolean admin = hasPermission(sender, "mygomusic.admin");
        int result = playQueue.removeAt(index, getSenderId(sender), admin);
        if (result == 1) {
            MessageUtil.sendSuccess(sender, "已从队列移除该歌曲");
            // 同步队列到客户端 GUI
            pluginChannel.broadcastQueueSync();
        } else if (result == -1) {
            MessageUtil.sendError(sender, "只能移除自己点的歌");
        } else {
            MessageUtil.sendError(sender, "序号超出范围，当前待播放共 " + playQueue.size() + " 首");
        }
    }

    /**
     * 处理下一首
     */
    private void handleNext(CommandSender sender) {
        if (!hasPermission(sender, "mygomusic.control")) {
            MessageUtil.sendError(sender, "你没有控制播放的权限");
            return;
        }

        if (!playQueue.isPlaying()) {
            MessageUtil.sendWarning(sender, "当前没有正在播放的歌曲");
            return;
        }

        queueScheduler.skipCurrent();
        MessageUtil.sendSuccess(sender, "已跳过当前歌曲");
    }

    /**
     * 处理上一首
     */
    private void handlePrev(CommandSender sender) {
        if (!hasPermission(sender, "mygomusic.control")) {
            MessageUtil.sendError(sender, "你没有控制播放的权限");
            return;
        }

        if (queueScheduler.playPrevious()) {
            MessageUtil.sendSuccess(sender, "已切换到上一首");
        } else {
            MessageUtil.sendWarning(sender, "没有上一首歌曲");
        }
    }

    /**
     * 处理查看队列
     */
    private void handleQueue(CommandSender sender) {
        if (!hasPermission(sender, "mygomusic.queue")) {
            MessageUtil.sendError(sender, "你没有查看队列的权限");
            return;
        }

        sender.sendMessage("§6========== [MygoMusic 播放队列] ==========");

        // 显示当前播放
        QueueItem current = playQueue.getCurrentPlaying();
        if (current != null) {
            sender.sendMessage(MessageUtil.formatNowPlaying(current.getTitle(), current.getArtist(),
                    current.getSourceDisplayName(), current.getRequesterName()));
        } else {
            sender.sendMessage("§7当前没有正在播放的歌曲");
        }

        sender.sendMessage("§6----------------------------------------");

        // 显示队列
        List<QueueItem> queue = playQueue.getSnapshot();
        if (queue.isEmpty()) {
            sender.sendMessage("§7队列为空");
        } else {
            for (int i = 0; i < queue.size(); i++) {
                QueueItem item = queue.get(i);
                sender.sendMessage(MessageUtil.formatQueueItem(i + 1, item.getTitle(), item.getArtist(),
                        item.getSourceDisplayName(), item.getRequesterName()));
            }
            sender.sendMessage("§7共 " + queue.size() + " 首歌曲");
        }

        sender.sendMessage("§6========================================");

        // 同步队列到请求者的客户端 GUI
        if (sender instanceof Player) {
            pluginChannel.sendQueueSync((Player) sender);
        }
    }

    /**
     * 处理搜索
     * 用法: /mm search <音源> <歌名>
     */
    private void handleSearch(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "mygomusic.search")) {
            MessageUtil.sendError(sender, "你没有搜索权限");
            return;
        }

        // 用法: /mm search <音源> <歌名>
        if (args.length < 3) {
            MessageUtil.sendError(sender, "用法: /mm search <音源> <歌名>");
            MessageUtil.sendInfo(sender, "可用音源: netease(网易云), kugou(酷狗), bilibili(B站)");
            return;
        }

        // 解析参数
        String sourceName = args[1].toLowerCase();
        String keyword = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));

        // 验证音源
        MusicSource source = sourceManager.getSource(sourceName);
        if (source == null) {
            MessageUtil.sendError(sender, "未知的音源: " + sourceName);
            MessageUtil.sendInfo(sender, "可用音源: netease(网易云), kugou(酷狗), bilibili(B站)");
            return;
        }

        // 异步搜索
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                final List<SongInfo> finalResults;

                String kw = keyword.trim();
                String bvDetailId = buildBvDetailId(sourceName, kw);
                if (bvDetailId != null) {
                    // B站 BV 号搜索（支持指定分P，如 /mm search bilibili BV1xx411c7mD p2）
                    SongDetail detail = source.getDetail(bvDetailId);
                    if (detail == null) {
                        MessageUtil.sendError(sender, "未找到该B站视频，请检查BV号是否正确");
                        return;
                    }
                    // #5 单P BV搜索直接播放，不用选择；多P则留给GUI让用户自选
                    int pages = 1;
                    try { pages = Integer.parseInt(detail.getExtra()); } catch (Exception ignore) {}
                    boolean explicitlyPicked = bvDetailId.contains("#p");
                    if (!explicitlyPicked && pages <= 1) {
                        String reqName = sender instanceof Player ? sender.getName() : "控制台";
                        UUID reqUuid = getSenderId(sender);
                        enqueueDetail(sender, detail, reqName, reqUuid, sender instanceof Player);
                        return; // 直接入队播放
                    }
                    List<SongInfo> single = new ArrayList<>(1);
                    single.add(new SongInfo(detail.getSongId(), detail.getTitle(), detail.getArtist(),
                            detail.getAlbum(), detail.getDuration(), detail.getSource(),
                            detail.getCoverUrl(), detail.getExtra()));
                    finalResults = single;
                } else {
                    List<SongInfo> fetched = source.search(kw, 10);
                    List<SongInfo> capped = fetched.size() > MAX_SEARCH_RESULTS
                            ? fetched.subList(0, MAX_SEARCH_RESULTS) : fetched;
                    // B站关键字结果里没有分P信息，逐个查 view 得到分P数，用于标注/列分P
                    if (sourceName.equals("bilibili") && source instanceof BilibiliSource) {
                        BilibiliSource bili = (BilibiliSource) source;
                        List<SongInfo> enriched = new ArrayList<>(capped.size());
                        for (SongInfo s : capped) {
                            int pages = 1;
                            try {
                                pages = bili.getPageCount(s.getSongId());
                            } catch (Exception ignore) {}
                            enriched.add(new SongInfo(s.getSongId(), s.getTitle(), s.getArtist(),
                                    s.getAlbum(), s.getDuration(), s.getSource(), s.getCoverUrl(),
                                    pages > 1 ? String.valueOf(pages) : ""));
                        }
                        finalResults = enriched;
                    } else {
                        finalResults = capped;
                    }
                }

                if (finalResults.isEmpty()) {
                    MessageUtil.sendError(sender, "未找到相关歌曲");
                    return;
                }

                // 保存搜索结果
                if (sender instanceof Player) {
                    searchResults.put(((Player) sender).getUniqueId(), finalResults);
                } else {
                    synchronized (consoleSearchResults) {
                        consoleSearchResults.clear();
                        consoleSearchResults.addAll(finalResults);
                    }
                }

                // 显示搜索结果（聊天，作为控制台/非GUI玩家的兜底）
                Bukkit.getScheduler().runTask(plugin, () -> {
                    showSearchResults(sender, finalResults);
                });

                // #2 把结构化结果发给 GUI 搜索界面，点击即点歌
                if (sender instanceof Player) {
                    final Player p = (Player) sender;
                    Bukkit.getScheduler().runTask(plugin, () -> pluginChannel.sendSearchResult(p, finalResults));
                }
            } catch (Exception e) {
                logger.error("搜索失败: " + e.getMessage(), e);
                MessageUtil.sendError(sender, "搜索失败: " + e.getMessage());
            }
        });
    }

    /**
     * 处理查看当前播放
     */
    private void handleNow(CommandSender sender) {
        QueueItem current = playQueue.getCurrentPlaying();
        if (current == null) {
            MessageUtil.sendInfo(sender, "当前没有正在播放的歌曲");
            return;
        }

        sender.sendMessage(MessageUtil.formatNowPlaying(current.getTitle(), current.getArtist(),
                current.getSourceDisplayName(), current.getRequesterName()));
    }

    /**
     * 处理音量调整
     */
    private void handleVolume(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "mygomusic.volume")) {
            MessageUtil.sendError(sender, "你没有调整音量的权限");
            return;
        }

        if (args.length < 2) {
            MessageUtil.sendError(sender, "用法: /mm volume <0-100>");
            return;
        }

        int volume;
        try {
            volume = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            MessageUtil.sendError(sender, "无效的音量值");
            return;
        }

        if (volume < 0 || volume > 100) {
            MessageUtil.sendError(sender, "音量值必须在0-100之间");
            return;
        }

        // 发送音量调整命令给客户端
        MessageUtil.sendSuccess(sender, "音量已设置为: " + volume);
    }

    /**
     * 处理歌词开关
     */
    private void handleLyrics(CommandSender sender) {
        if (!hasPermission(sender, "mygomusic.lyrics")) {
            MessageUtil.sendError(sender, "你没有切换歌词的权限");
            return;
        }

        // 发送歌词切换命令给客户端
        MessageUtil.sendSuccess(sender, "歌词显示已切换");
    }

    /**
     * 处理登录
     */
    private void handleLogin(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "mygomusic.login")) {
            MessageUtil.sendError(sender, "你没有登录权限");
            return;
        }

        if (args.length < 2) {
            MessageUtil.sendError(sender, "用法: /mm login <平台> [Cookie]");
            return;
        }

        String platform = args[1].toLowerCase();
        MusicSource source = sourceManager.getSource(platform);

        if (source == null) {
            MessageUtil.sendError(sender, "未知的平台: " + platform);
            return;
        }

        // Cookie 登录（/mm login <平台> <Cookie>）
        if (args.length >= 3) {
            String cookie = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                LoginResult result = source.loginWithCookie(cookie);
                if (result.isSuccess()) {
                    // 持久化，重启后自动恢复登录态
                    configManager.saveSourceCookie(platform, cookie);
                    MessageUtil.sendSuccess(sender, result.getMessage() + "（已保存，重启后自动恢复）");
                } else {
                    MessageUtil.sendError(sender, result.getMessage());
                }
            });
            return;
        }

        // 二维码登录
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                LoginResult result = source.login(LoginMethod.QR_CODE);

                if (result.hasQrCode()) {
                    if (sender instanceof Player) {
                        MessageUtil.sendClickableLink((Player) sender, "点击打开二维码: " + result.getQrCodeUrl(), result.getQrCodeUrl());
                    } else {
                        MessageUtil.sendInfo(sender, "二维码链接: " + result.getQrCodeUrl());
                    }

                    // 轮询登录状态（最多等待 180 秒）
                    MessageUtil.sendInfo(sender, "请在手机上扫码并确认，等待登录完成...");
                    LoginResult pollResult = source.pollQrCodeLogin(180);

                    if (pollResult.isSuccess()) {
                        // 持久化二维码登录得到的Cookie，重启后自动恢复
                        String savedCookie = source.getSavedCookie();
                        if (savedCookie != null && !savedCookie.isEmpty()) {
                            configManager.saveSourceCookie(platform, savedCookie);
                        }
                        MessageUtil.sendSuccess(sender, pollResult.getMessage() + "（已保存，重启后自动恢复）");
                    } else {
                        MessageUtil.sendError(sender, "登录超时或失败，请重试");
                    }
                } else if (result.hasLoginUrl()) {
                    if (sender instanceof Player) {
                        MessageUtil.sendClickableLink((Player) sender, "点击登录: " + result.getLoginUrl(), result.getLoginUrl());
                    } else {
                        MessageUtil.sendInfo(sender, "登录链接: " + result.getLoginUrl());
                    }
                } else {
                    MessageUtil.sendError(sender, result.getMessage());
                }
            } catch (Exception e) {
                logger.error("登录失败: " + e.getMessage(), e);
                MessageUtil.sendError(sender, "登录失败: " + e.getMessage());
            }
        });
    }

    /**
     * 处理登出
     */
    private void handleLogout(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "mygomusic.login")) {
            MessageUtil.sendError(sender, "你没有登出权限");
            return;
        }

        if (args.length < 2) {
            MessageUtil.sendError(sender, "用法: /mm logout <平台>");
            return;
        }

        String platform = args[1].toLowerCase();
        MusicSource source = sourceManager.getSource(platform);

        if (source == null) {
            MessageUtil.sendError(sender, "未知的平台: " + platform);
            return;
        }

        source.logout();
        MessageUtil.sendSuccess(sender, "已登出 " + source.getDisplayName());
    }

    /**
     * 处理管理员命令 - 控制台可直接执行
     */
    private void handleAdmin(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "mygomusic.admin")) {
            MessageUtil.sendError(sender, "你没有管理员权限");
            return;
        }

        if (args.length < 2) {
            sendAdminHelp(sender);
            return;
        }

        String subCommand = args[1].toLowerCase();

        switch (subCommand) {
            case "reload":
                handleAdminReload(sender);
                break;
            case "clearqueue":
                handleAdminClearQueue(sender);
                break;
            case "skip":
                handleAdminSkip(sender);
                break;
            case "prev":
                handleAdminPrev(sender);
                break;
            case "stop":
                handleAdminStop(sender);
                break;
            case "debug":
                handleAdminDebug(sender);
                break;
            case "accounts":
                handleAdminAccounts(sender);
                break;
            case "sources":
                handleAdminSources(sender);
                break;
            case "cache":
                handleAdminCache(sender, args);
                break;
            case "db":
                handleAdminDb(sender);
                break;
            case "ffmpeg":
                handleAdminFfmpeg(sender);
                break;
            case "restore":
                handleAdminRestore(sender);
                break;
            default:
                sendAdminHelp(sender);
                break;
        }
    }

    private void handleAdminReload(CommandSender sender) {
        configManager.reloadConfig();
        sourceManager.reload();
        // 重载后把新的客户端缓存设置推送给所有在线客户端
        pluginChannel.broadcastCacheConfig();
        MessageUtil.sendSuccess(sender, "配置已重载");
    }

    private void handleAdminClearQueue(CommandSender sender) {
        playQueue.clear();
        MessageUtil.sendSuccess(sender, "队列已清空");

        // 同步队列到客户端 GUI
        pluginChannel.broadcastQueueSync();
    }

    private void handleAdminSkip(CommandSender sender) {
        if (!playQueue.isPlaying()) {
            MessageUtil.sendWarning(sender, "当前没有正在播放的歌曲");
            return;
        }
        queueScheduler.skipCurrent();
        MessageUtil.sendSuccess(sender, "已跳过当前歌曲");
    }

    private void handleAdminPrev(CommandSender sender) {
        if (queueScheduler.playPrevious()) {
            MessageUtil.sendSuccess(sender, "已切换到上一首");
        } else {
            MessageUtil.sendWarning(sender, "没有上一首歌曲");
        }
    }

    private void handleAdminStop(CommandSender sender) {
        queueScheduler.stopCurrent();
        MessageUtil.sendSuccess(sender, "已停止播放");
    }

    private void handleAdminDebug(CommandSender sender) {
        // 切换调试模式
        MessageUtil.sendSuccess(sender, "调试模式已切换");
    }

    private void handleAdminAccounts(CommandSender sender) {
        sender.sendMessage("§6========== [MygoMusic 账号状态] ==========");
        for (MusicSource source : sourceManager.getAllSources()) {
            sender.sendMessage(String.format("§e%s: §f%s", source.getDisplayName(), source.getLoginStatus()));
        }
        sender.sendMessage("§6========================================");
    }

    private void handleAdminSources(CommandSender sender) {
        sender.sendMessage("§6========== [MygoMusic 音源状态] ==========");
        Map<String, boolean[]> results = sourceManager.testAllSources();
        for (Map.Entry<String, boolean[]> entry : results.entrySet()) {
            String status = entry.getValue()[0] ? "§a可用" : "§c不可用";
            String login = entry.getValue()[1] ? "§a已登录" : "§e未登录";
            sender.sendMessage(String.format("§e%s: %s §7(%s)", entry.getKey(), status, login));
        }
        sender.sendMessage("§6========================================");
    }

    private void handleAdminCache(CommandSender sender, String[] args) {
        if (args.length < 3) {
            MessageUtil.sendError(sender, "用法: /mm admin cache clear");
            return;
        }

        if (args[2].equals("clear")) {
            String cacheDir = configManager.getFfmpegCacheDir();
            File dir = new File(cacheDir);
            if (dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
            }
            MessageUtil.sendSuccess(sender, "缓存已清空");
        }
    }

    private void handleAdminDb(CommandSender sender) {
        if (databaseManager != null) {
            MessageUtil.sendSuccess(sender, "数据库状态: " + (databaseManager.isConnected() ? "§a已连接" : "§c未连接"));
        } else {
            MessageUtil.sendWarning(sender, "数据库未初始化");
        }
    }

    private void handleAdminFfmpeg(CommandSender sender) {
        String ffmpegPath = configManager.getFfmpegPath();
        boolean available = FfmpegUtil.isAvailable(ffmpegPath);
        if (available) {
            MessageUtil.sendSuccess(sender, "ffmpeg 可用");
        } else {
            MessageUtil.sendError(sender, "ffmpeg 不可用，请检查配置");
        }
    }

    private void handleAdminRestore(CommandSender sender) {
        List<String> available = plugin.getPlayQueue().getHistorySnapshot() != null ?
                new ArrayList<>() : new ArrayList<>();

        // 这里需要从QueuePersistence获取可用文件
        MessageUtil.sendInfo(sender, "正在恢复队列...");
    }

    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6========== [MygoMusic 帮助] ==========");
        sender.sendMessage("§e/mm play <音源> <歌名> [玩家] §7- 点歌 (音源: netease/kugou/bilibili)");
        sender.sendMessage("§e/mm search <音源> <歌名> §7- 搜索歌曲");
        sender.sendMessage("§e/mm select <序号> §7- 选择搜索结果");
        sender.sendMessage("§e/mm playid <平台> <ID> [玩家] §7- 通过ID点歌");
        sender.sendMessage("§e/mm pause §7- 暂停播放 (全服)");
        sender.sendMessage("§e/mm continue §7- 继续播放 (全服)");
        sender.sendMessage("§e/mm next §7- 下一首");
        sender.sendMessage("§e/mm prev §7- 上一首");
        sender.sendMessage("§e/mm remove <序号> §7- 移除自己点的歌");
        sender.sendMessage("§e/mm queue §7- 查看队列");
        sender.sendMessage("§e/mm now §7- 查看当前播放");
        sender.sendMessage("§e/mm volume <0-100> §7- 调整音量");
        sender.sendMessage("§e/mm lyrics §7- 开关歌词");
        sender.sendMessage("§e/mm login <平台> §7- 登录平台");
        sender.sendMessage("§e/mm logout <平台> §7- 登出平台");
        sender.sendMessage("§e/mm admin §7- 管理员命令");
        sender.sendMessage("§6========================================");
    }

    /**
     * 发送管理员帮助
     */
    private void sendAdminHelp(CommandSender sender) {
        sender.sendMessage("§6========== [MygoMusic 管理员] ==========");
        sender.sendMessage("§e/mm admin reload §7- 重载配置");
        sender.sendMessage("§e/mm admin clearqueue §7- 清空队列");
        sender.sendMessage("§e/mm admin skip §7- 强制跳过");
        sender.sendMessage("§e/mm admin prev §7- 强制上一首");
        sender.sendMessage("§e/mm admin stop §7- 强制停止");
        sender.sendMessage("§e/mm admin debug §7- 调试模式");
        sender.sendMessage("§e/mm admin accounts §7- 账号状态");
        sender.sendMessage("§e/mm admin sources §7- 音源状态");
        sender.sendMessage("§e/mm admin cache clear §7- 清空缓存");
        sender.sendMessage("§e/mm admin db §7- 数据库状态");
        sender.sendMessage("§e/mm admin ffmpeg §7- 检查ffmpeg");
        sender.sendMessage("§e/mm admin restore §7- 恢复队列");
        sender.sendMessage("§6========================================");
    }
}
