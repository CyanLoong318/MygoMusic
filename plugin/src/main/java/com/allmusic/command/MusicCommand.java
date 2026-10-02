package com.allmusic.command;

import com.allmusic.AllMusicPlugin;
import com.allmusic.config.ConfigManager;
import com.allmusic.database.DatabaseManager;
import com.allmusic.model.*;
import com.allmusic.network.HttpFileServer;
import com.allmusic.network.PluginChannel;
import com.allmusic.queue.PlayQueue;
import com.allmusic.queue.QueuePersistence;
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
import java.util.concurrent.ConcurrentHashMap;
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

    // 点歌冷却（异步搜索线程写、主线程读，必须并发安全）
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    // 搜索结果缓存（同上）
    private final Map<UUID, List<SongInfo>> searchResults = new ConcurrentHashMap<>();
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
                handleLyrics(sender, args);
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
        if (!checkCooldown(sender)) {
            return;
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
     * 玩家退出时清掉该玩家的临时数据（搜索结果缓存）。
     * 注意不要把点歌冷却一起清掉：退服/子服切换都会触发这里，清了冷却等于重登即可绕过
     * queue.cooldown-seconds。冷却表改由 markCooldown 顺带清理过期条目来防堆积。
     */
    public void clearPlayerCache(UUID playerId) {
        if (playerId == null) return;
        searchResults.remove(playerId);
    }

    /**
     * 点歌冷却检查：冷却中返回 false（并已给出提示）。
     * 控制台、持 bypass 权限的玩家、冷却配置为 0 时一律放行。
     *
     * /mm play、/mm select、/mm playid 三条点歌入口都必须过这里 ——
     * 旧代码只在 /mm play 里查冷却，换 /mm select 或 /mm playid 就能无限连点。
     */
    private boolean checkCooldown(CommandSender sender) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        if (player.hasPermission(configManager.getCooldownBypassPermission())) return true;

        long cooldownMs = configManager.getCooldownSeconds() * 1000L;
        if (cooldownMs <= 0) return true;

        Long lastPlay = cooldowns.get(player.getUniqueId());
        if (lastPlay == null) return true;

        long remainingMs = cooldownMs - (System.currentTimeMillis() - lastPlay);
        if (remainingMs <= 0) return true;

        MessageUtil.sendWarning(player, "点歌冷却中，请等待 " + (remainingMs / 1000 + 1) + " 秒");
        return false;
    }

    /**
     * 记一次点歌冷却（控制台不记）
     */
    private void markCooldown(CommandSender sender) {
        if (!(sender instanceof Player)) return;
        long cooldownMs = configManager.getCooldownSeconds() * 1000L;
        if (cooldownMs <= 0) return; // 冷却关闭：不必记录，免得表只增不减
        long now = System.currentTimeMillis();
        cooldowns.put(((Player) sender).getUniqueId(), now);
        // 顺带清理已过期的条目：替代原先「退服即删」的防堆积手段（那样会放过重登绕冷却）
        cooldowns.entrySet().removeIf(e -> now - e.getValue() >= cooldownMs);
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
     * 将已获取的详情加入队列。调用方通常在异步搜索线程上，因此：
     * 冷却检查、队列/调度器/发消息统一切回主线程 —— playNext() 会读写 activeFetch
     * （约定只在主线程读写），从异步调用会和 tick() 抢；数据库写入再切回异步线程执行。
     */
    private void enqueueDetail(CommandSender sender, SongDetail detail, String requesterName,
                               UUID requesterUuid, boolean applyCooldown) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            // 冷却检查统一放在主线程（hasPermission/sendMessage 非线程安全）。
            // 此前 BV 单P直达是在异步线程里直接调 checkCooldown 的，放到这里也让各入口收口
            if (applyCooldown && !checkCooldown(sender)) {
                return;
            }

            // add 在队列满时返回 false：不能默默丢歌还回一句「已点歌」，那会让玩家以为点上了
            if (!playQueue.add(new QueueItem(detail, requesterName, requesterUuid))) {
                MessageUtil.sendError(sender, "播放队列已满（上限 " + configManager.getQueueMaxSize() + " 首），请稍后再点");
                return;
            }

            // 历史记录写入切回异步（别阻塞主线程）。放在成功入队之后：
            // 冷却中被拒、队列满被拒的点歌都不该记进播放历史
            if (databaseManager != null) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin,
                        () -> databaseManager.savePlayHistory(requesterUuid, requesterName, detail));
            }

            if (applyCooldown) {
                markCooldown(sender);
            }

            MessageUtil.sendSuccess(sender, "已点歌: " + detail.getTitle() + " - " + detail.getArtist());

            if (!playQueue.isPlaying()) {
                queueScheduler.playNext();
            }

            pluginChannel.broadcastQueueSync();
        });
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
            MessageUtil.sendError(sender, "用法: /mm select <序号>");
            return;
        }

        int index;
        try {
            index = Integer.parseInt(args[1]) - 1;
        } catch (NumberFormatException e) {
            MessageUtil.sendError(sender, "无效的序号");
            return;
        }

        // 客户端 GUI 会附带它点的那一行的 songId，用于确认服务端缓存没被别的搜索顶掉
        String expectedSongId = args.length >= 3 ? args[2] : null;
        doSelect(sender, index, expectedSongId);
    }

    private void doSelect(CommandSender sender, int index) {
        doSelect(sender, index, null);
    }

    /**
     * 执行选择歌曲
     *
     * @param expectedSongId 调用方期望选中的歌曲 ID（客户端 GUI 传入）。与服务端缓存对不上说明
     *                       期间有别的搜索覆盖了缓存，此时必须拒绝，否则会点错歌。
     */
    private void doSelect(CommandSender sender, int index, String expectedSongId) {
        if (!hasPermission(sender, "mygomusic.play")) {
            MessageUtil.sendError(sender, "你没有点歌权限");
            return;
        }
        if (!checkCooldown(sender)) {
            return;
        }

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

        if (expectedSongId != null && selected.getSongId() != null
                && !expectedSongId.equals(selected.getSongId())) {
            // 界面上的第 N 行已经不是服务端缓存的第 N 首了（期间有人 /mm play 顶掉了缓存）；
            // 也可能是旧用法「/mm select <序号> <玩家名>」——第三个参数现在只认歌曲 ID
            MessageUtil.sendError(sender, "搜索结果已变化，或第 3 个参数不是歌曲 ID（用法: /mm select <序号>），请重新搜索后再选择");
            return;
        }

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

        markCooldown(sender);

        // 保存到数据库（异步，别占主线程）
        if (databaseManager != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin,
                    () -> databaseManager.savePlayHistory(requesterUuid, requesterName, selected));
        }

        MessageUtil.sendSuccess(sender, "已点歌: " + selected.getTitle() + " - " + selected.getArtist());

        // 如果没有正在播放的歌曲，立即播放
        if (!playQueue.isPlaying()) {
            queueScheduler.playNext();
        }

        // 同步队列到客户端 GUI
        pluginChannel.broadcastQueueSync();

        // 注意：这里不清搜索结果缓存。
        // 旧行为是选完就 remove，于是「从搜索结果里再点一首」会报「没有搜索结果，请先搜索」，
        // 对客户端 GUI 尤其致命 —— 界面还开着，再点就是一句报错。
        // 缓存由下一次搜索覆盖，容量只有 5 条/人，不存在堆积问题。
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

        if (!checkCooldown(sender)) {
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
                enqueueDetail(sender, detail, requesterName, requesterUuid, true);
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

        // 取歌中（还没出声）也算“正在进行”，否则 /mm stop 会答“没有正在播放的歌曲”，
        // 而在途的取歌任务随后照样把歌播出来
        if (!playQueue.isPlaying() && !queueScheduler.isFetching()) {
            MessageUtil.sendWarning(sender, "当前没有正在播放的歌曲");
            return;
        }

        queueScheduler.stopByUser();
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
                        // 单P BV 直达：直接入队。冷却检查与入队统一在 enqueueDetail 的主线程任务里做
                        // （checkCooldown 会碰 hasPermission/sendMessage，不能在异步线程调）。
                        // 注意这里不 return：继续走下面的统一回包路径把结果回给搜索 GUI，
                        // 否则 GUI 会一直「搜索中」直到 15 秒超时（歌其实已经点上了）。
                        String reqName = sender instanceof Player ? sender.getName() : "控制台";
                        UUID reqUuid = getSenderId(sender);
                        enqueueDetail(sender, detail, reqName, reqUuid, sender instanceof Player);
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
                            // 搜索结果里已经带分P数时直接用，别再为每条结果多发一次 view 请求（最多少 5 次）
                            String extra = s.getExtra();
                            if (extra != null && !extra.isEmpty()) {
                                try {
                                    pages = Integer.parseInt(extra.trim());
                                } catch (NumberFormatException ignore) {
                                    pages = 1;
                                }
                            } else {
                                try {
                                    pages = bili.getPageCount(s.getSongId());
                                } catch (Exception ignore) {}
                            }
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

        // 音频是在玩家客户端本地播的，服务端只能把这个值下发过去才会真的生效。
        // 旧实现只回了一句「音量已设置为: N」就结束了 —— 按了跟没按一样。
        if (!(sender instanceof Player)) {
            MessageUtil.sendError(sender, "该命令只能由玩家执行（音量是客户端本地设置）");
            return;
        }
        pluginChannel.sendVolume((Player) sender, volume);
        MessageUtil.sendSuccess(sender, "音量已设置为: " + volume);
    }

    /**
     * 处理歌词开关：/mm lyrics [on|off]（不带参数则切换）
     */
    private void handleLyrics(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "mygomusic.lyrics")) {
            MessageUtil.sendError(sender, "你没有切换歌词的权限");
            return;
        }

        int mode = 0; // 0=切换
        if (args.length >= 2) {
            String value = args[1].toLowerCase();
            if (value.equals("on") || value.equals("true") || value.equals("开")) {
                mode = 1;
            } else if (value.equals("off") || value.equals("false") || value.equals("关")) {
                mode = 2;
            } else {
                MessageUtil.sendError(sender, "用法: /mm lyrics [on|off]");
                return;
            }
        }

        if (!(sender instanceof Player)) {
            MessageUtil.sendError(sender, "该命令只能由玩家执行（歌词显示是客户端本地设置）");
            return;
        }
        pluginChannel.sendLyricsToggle((Player) sender, mode);
        MessageUtil.sendSuccess(sender, mode == 1 ? "歌词已开启" : mode == 2 ? "歌词已关闭" : "歌词显示已切换");
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
        // 同时清掉磁盘上保存的 Cookie，否则重启后会自动「登回去」
        configManager.removeSourceCookie(platform);
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
                handleAdminRestore(sender, args);
                break;
            default:
                sendAdminHelp(sender);
                break;
        }
    }

    private void handleAdminReload(CommandSender sender) {
        configManager.reloadConfig();
        sourceManager.reload();
        // HTTP 分发服务的端口/目录/对外主机都取自配置，而它们只在 start() 时生效。
        // 不重启的话：改了 ffmpeg.cache-dir 后 getFileUrl() 会按新目录拼下载地址，
        // 服务却仍在旧目录里找文件 → 转码好的歌 404 播不出来。
        HttpFileServer fileServer = plugin.getHttpFileServer();
        if (fileServer != null) {
            fileServer.restart(configManager.getHttpServerPort(), configManager.getFfmpegCacheDir(),
                    configManager.getHttpServerHost());
        }
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
        queueScheduler.stopByUser();
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
        // 逐音源真实发网络请求，必须异步：跑在主线程会冻结整个服务端（含队列调度 tick）数分钟
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, boolean[]> results = sourceManager.testAllSources();
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Map.Entry<String, boolean[]> entry : results.entrySet()) {
                    String status = entry.getValue()[0] ? "§a可用" : "§c不可用";
                    String login = entry.getValue()[1] ? "§a已登录" : "§e未登录";
                    sender.sendMessage(String.format("§e%s: %s §7(%s)", entry.getKey(), status, login));
                }
                sender.sendMessage("§6========================================");
            });
        });
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
        // 探活要起进程并 waitFor，绝不能占主线程 —— 卡满 5 秒就是整个服务器卡 5 秒
        String ffmpegPath = configManager.getFfmpegPath();
        MessageUtil.sendInfo(sender, "正在检查 ffmpeg...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean available = FfmpegUtil.isAvailable(ffmpegPath);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (available) {
                    MessageUtil.sendSuccess(sender, "ffmpeg 可用");
                } else {
                    MessageUtil.sendError(sender, "ffmpeg 不可用，请检查配置");
                }
            });
        });
    }

    /**
     * 恢复关服时保存的队列：/mm admin restore [文件名]（不带文件名则恢复最近的一份）
     */
    private void handleAdminRestore(CommandSender sender, String[] args) {
        QueuePersistence persistence = plugin.getQueuePersistence();
        if (persistence == null) {
            MessageUtil.sendError(sender, "队列持久化未初始化");
            return;
        }

        List<String> available = persistence.getAvailableRestores();
        if (available.isEmpty()) {
            MessageUtil.sendInfo(sender, "没有可恢复的队列文件");
            return;
        }
        // 文件名形如 queue_2026-10-02_13-05-11.json，按名字排序即按时间排序
        Collections.sort(available);
        String fileName = args.length >= 3 ? args[2] : available.get(available.size() - 1);

        if (!persistence.restore(fileName)) {
            MessageUtil.sendError(sender, "恢复失败: " + fileName + "（请检查文件名）");
            MessageUtil.sendInfo(sender, "可用文件: " + String.join(", ", available));
            return;
        }

        MessageUtil.sendSuccess(sender, "已恢复队列: " + fileName);
        if (configManager.isAutoPlay() && !playQueue.isPlaying() && !playQueue.isEmpty()) {
            queueScheduler.playNext();
        }
        pluginChannel.broadcastQueueSync();
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
        sender.sendMessage("§e/mm lyrics [on|off] §7- 开关歌词（不带参数则切换，需客户端 Mod）");
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
        sender.sendMessage("§e/mm admin restore [文件名] §7- 恢复关服时保存的队列（默认最近一份）");
        sender.sendMessage("§6========================================");
    }
}
