package com.allmusic.queue;

import com.allmusic.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 投票管理器
 */
public class VoteManager {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-Vote");

    private final ConfigManager configManager;
    private final Set<UUID> skipVotes = new HashSet<>();
    private boolean votingActive = false;

    public VoteManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * 发起跳过投票
     */
    public boolean startSkipVote(UUID voter) {
        if (votingActive) {
            return false;
        }

        votingActive = true;
        skipVotes.clear();
        skipVotes.add(voter);

        int onlineCount = Bukkit.getOnlinePlayers().size();
        int requiredVotes = Math.max(1, (int) Math.ceil(onlineCount * configManager.getSkipVotePercent() / 100.0));

        // 通知所有玩家
        Player voterPlayer = Bukkit.getPlayer(voter);
        String voterName = voterPlayer != null ? voterPlayer.getName() : "未知";

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage("§6[MygoMusic] §e" + voterName + " §f发起了跳过投票");
            player.sendMessage("§6[MygoMusic] §e输入 §f/mm skip §e投票跳过当前歌曲");
            player.sendMessage("§6[MygoMusic] §7(" + skipVotes.size() + "/" + requiredVotes + " 票)");
        }

        // 检查是否已经达到要求
        if (skipVotes.size() >= requiredVotes) {
            return true;
        }

        return false;
    }

    /**
     * 投票跳过
     */
    public boolean voteSkip(UUID voter) {
        if (!votingActive) {
            return false;
        }

        if (skipVotes.contains(voter)) {
            return false; // 已经投过票
        }

        skipVotes.add(voter);

        int onlineCount = Bukkit.getOnlinePlayers().size();
        int requiredVotes = Math.max(1, (int) Math.ceil(onlineCount * configManager.getSkipVotePercent() / 100.0));

        // 通知投票进度
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage("§6[MygoMusic] §7(" + skipVotes.size() + "/" + requiredVotes + " 票)");
        }

        // 检查是否达到要求
        if (skipVotes.size() >= requiredVotes) {
            return true;
        }

        return false;
    }

    /**
     * 是否正在投票
     */
    public boolean isVotingActive() {
        return votingActive;
    }

    /**
     * 获取当前票数
     */
    public int getCurrentVotes() {
        return skipVotes.size();
    }

    /**
     * 获取所需票数
     */
    public int getRequiredVotes() {
        int onlineCount = Bukkit.getOnlinePlayers().size();
        return Math.max(1, (int) Math.ceil(onlineCount * configManager.getSkipVotePercent() / 100.0));
    }

    /**
     * 重置投票
     */
    public void reset() {
        votingActive = false;
        skipVotes.clear();
    }
}
