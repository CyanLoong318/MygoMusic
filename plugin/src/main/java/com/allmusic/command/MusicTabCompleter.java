package com.allmusic.command;

import com.allmusic.source.SourceManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tab 补全
 */
public class MusicTabCompleter implements TabCompleter {

    private final SourceManager sourceManager;

    private static final List<String> MAIN_COMMANDS = Arrays.asList(
            "play", "playid", "stop", "continue", "next", "prev",
            "queue", "search", "now", "volume", "lyrics", "login", "logout", "admin", "select"
    );

    private static final List<String> ADMIN_COMMANDS = Arrays.asList(
            "reload", "clearqueue", "skip", "prev", "stop", "debug",
            "accounts", "sources", "cache", "db", "ffmpeg", "restore"
    );

    public MusicTabCompleter(SourceManager sourceManager) {
        this.sourceManager = sourceManager;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return filterCompletions(MAIN_COMMANDS, args[0]);
        }

        if (args.length == 2) {
            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "play":
                case "search":
                    // 返回音源列表（现在音源是必填的第二个参数）
                    return filterCompletions(sourceManager.getEnabledSourceNames(), args[1]);
                case "playid":
                case "login":
                case "logout":
                    // 返回音源列表
                    return filterCompletions(sourceManager.getEnabledSourceNames(), args[1]);
                case "select":
                    // 返回序号 1-5
                    return filterCompletions(Arrays.asList("1", "2", "3", "4", "5"), args[1]);
                case "volume":
                    // 返回音量值
                    return filterCompletions(Arrays.asList("0", "25", "50", "75", "100"), args[1]);
                case "admin":
                    return filterCompletions(ADMIN_COMMANDS, args[1]);
            }
        }

        if (args.length == 3) {
            String subCommand = args[0].toLowerCase();

            if (subCommand.equals("admin")) {
                String adminCmd = args[1].toLowerCase();
                if (adminCmd.equals("cache")) {
                    return filterCompletions(Arrays.asList("clear"), args[2]);
                }
            }
        }

        return new ArrayList<>();
    }

    /**
     * 过滤补全选项
     */
    private List<String> filterCompletions(List<String> options, String prefix) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }
}
