package com.walrusone.skywarsreloaded.game;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

public class PlayerNameColorManager {
    private final Map<UUID, TeamPair> playerTeams = new HashMap<>();

    private final Map<UUID, Objective> healthObjectives = new HashMap<>();
    private final JavaPlugin plugin;
    private final GameMap gameMap;
    private int healthUpdateTaskId = -1;

    private static class TeamPair {
        final Team selfTeam;
        final Team enemyTeam;

        TeamPair(Team self, Team enemy) {
            this.selfTeam = self;
            this.enemyTeam = enemy;
        }
    }

    // 核心修复：适配1.8.8的血量+吸收值显示
    private void updateAllPlayersHealth() {
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            Scoreboard scoreboard = viewer.getScoreboard();
            if (scoreboard == null) continue;

            // 获取当前玩家的血量显示Objective（优先用缓存）
            Objective healthObjective = healthObjectives.get(viewer.getUniqueId());
            if (healthObjective == null) {
                healthObjective = scoreboard.getObjective("health_display");
                if (healthObjective == null) continue;
            }

            // 遍历所有游戏内玩家更新血量
            for (Player target : gameMap.getAllPlayers()) {
                if (!target.isOnline()) continue;

                // 1.8.8 获取基础血量
                double rawHealth = target.getHealth();
                // 1.8.8 获取吸收值（通过反射/NMS）
                double absorption = getAbsorptionAmount1_8_8(target);

                int totalHealth = (int) (rawHealth + absorption);
                totalHealth = totalHealth > 0 ? totalHealth : 0;

                // 显示总血量（比如20+4=24）
                healthObjective.getScore(target.getName()).setScore(totalHealth);
            }
        }
    }

    // 新增：1.8.8 专用获取吸收值的方法（反射实现）
    private double getAbsorptionAmount1_8_8(Player player) {
        try {
            // 1.8.8 NMS 路径：EntityPlayer -> getHealth() 包含基础血量，getAbsorptionHearts() 是吸收值
            // 获取 CraftPlayer 中的 handle（EntityPlayer）
            Object craftPlayer = player.getClass().getMethod("getHandle").invoke(player);
            // 获取吸收值（1.8.8 中 absorptionHearts 是 float 类型）
            Method getAbsorptionHearts = craftPlayer.getClass().getMethod("getAbsorptionHearts");
            float absorption = (float) getAbsorptionHearts.invoke(craftPlayer);
            return absorption;
        } catch (Exception e) {
            // 反射失败时返回0（避免报错）
            plugin.getLogger().warning("获取玩家吸收值失败：" + e.getMessage());
            return 0.0;
        }
    }

    public PlayerNameColorManager(JavaPlugin plugin, GameMap gameMap) {
        this.plugin = plugin;
        this.gameMap = gameMap;
    }

    // 新增：启动血量定时更新（1.8.8 专用）
    private void startHealthUpdateTask() {
        // 先取消已有任务（避免重复）
        if (healthUpdateTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(healthUpdateTaskId);
            healthUpdateTaskId = -1;
        }

        // 1.8.8 定时任务：返回 int 类型的任务ID
        healthUpdateTaskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
            @Override
            public void run() {
                updateAllPlayersHealth(); // 替换为新的血量更新方法
            }
        }, 0L, 5L); // 缩短更新间隔到5刻（0.25秒），显示更实时
    }

    public void initNameColorTeams(Player player, Collection<Player> allPlayers, UUID selfUuid) {
        setupNameColorTeams(player, allPlayers, selfUuid);

        if (healthUpdateTaskId == -1) {
            startHealthUpdateTask();
        }
        for (UUID existingPlayerUuid : playerTeams.keySet()) {
            if (existingPlayerUuid.equals(selfUuid)) {
                continue;
            }

            Player existingPlayer = plugin.getServer().getPlayer(existingPlayerUuid);
            if (existingPlayer == null || !existingPlayer.isOnline()) {
                continue;
            }

            TeamPair existingTeams = playerTeams.get(existingPlayerUuid);
            if (existingTeams == null) {
                continue;
            }

            if (!existingTeams.enemyTeam.hasEntry(player.getName())) {
                existingTeams.enemyTeam.addEntry(player.getName());

            }

            TeamPair newPlayerTeams = playerTeams.get(selfUuid);
            if (newPlayerTeams != null && !newPlayerTeams.enemyTeam.hasEntry(existingPlayer.getName())) {
                newPlayerTeams.enemyTeam.addEntry(existingPlayer.getName());
            }
        }
    }

    private void setupNameColorTeams(
            Player player,
            Collection<Player> allPlayers,
            UUID selfUuid
    ) {
        org.bukkit.scoreboard.Scoreboard scoreboard = player.getScoreboard();
        if (scoreboard == null) {
            scoreboard = org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard();
        }

        String uuidShort = selfUuid.toString().replace("-", "").substring(0, 6);
        String selfTeamName = "SWS_" + uuidShort;
        String enemyTeamName = "SWE_" + uuidShort;

        org.bukkit.scoreboard.Team selfTeam = scoreboard.getTeam(selfTeamName);
        if (selfTeam == null) {
            selfTeam = scoreboard.registerNewTeam(selfTeamName);
            selfTeam.setPrefix(org.bukkit.ChatColor.GREEN.toString());
        }
        if (!selfTeam.hasEntry(player.getName())) {
            selfTeam.addEntry(player.getName());
        }

        org.bukkit.scoreboard.Team enemyTeam = scoreboard.getTeam(enemyTeamName);
        if (enemyTeam == null) {
            enemyTeam = scoreboard.registerNewTeam(enemyTeamName);
            enemyTeam.setPrefix(org.bukkit.ChatColor.RED.toString());
        }
        for (Player other : allPlayers) {
            if (!other.getUniqueId().equals(selfUuid) && !enemyTeam.hasEntry(other.getName())) {
                enemyTeam.addEntry(other.getName());
            }
        }

        playerTeams.put(selfUuid, new TeamPair(selfTeam, enemyTeam));
// ========== 核心修复：血量显示逻辑（适配1.8.8，支持金苹果吸收值） ==========
// 1. 初始化血量Objective（绑定到头顶显示槽位）
        Objective healthObjective = scoreboard.getObjective("health_display");
        if (healthObjective == null) {
            // 关键修改：使用"dummy"类型（无上限），替代原版"health"类型（限20）
            healthObjective = scoreboard.registerNewObjective("health_display", "dummy");
            healthObjective.setDisplayName(ChatColor.RED + "❤"); // 头顶血量标题（红色爱心）
            healthObjective.setDisplaySlot(DisplaySlot.BELOW_NAME); // 显示在玩家头顶
        }

// 缓存Objective，避免重复获取
        healthObjectives.put(selfUuid, healthObjective);

// 2. 为当前玩家设置血量（1.8.8支持基础血量+金苹果吸收值）
        double playerRawHealth = player.getHealth();
        double playerAbsorption = getAbsorptionAmount1_8_8(player); // 调用1.8.8专用方法
        int playerTotalHealth = (int) (playerRawHealth + playerAbsorption);
        playerTotalHealth = playerTotalHealth > 0 ? playerTotalHealth : 0;
        healthObjective.getScore(player.getName()).setScore(playerTotalHealth);

// 3. 为所有其他玩家设置血量（让当前玩家能看到其他人的血量+吸收值）
        for (Player other : allPlayers) {
            if (!other.isOnline()) continue;
            double otherRawHealth = other.getHealth();
            double otherAbsorption = getAbsorptionAmount1_8_8(other); // 调用1.8.8专用方法
            int otherTotalHealth = (int) (otherRawHealth + otherAbsorption);
            otherTotalHealth = otherTotalHealth > 0 ? otherTotalHealth : 0;
            healthObjective.getScore(other.getName()).setScore(otherTotalHealth);
        }
    }

    public void initAllNameColorTeams(Collection<Player> allPlayers) {
        for (Player player : allPlayers) {
            initNameColorTeams(player, allPlayers, player.getUniqueId());
        }
    }

    public void clearPlayerData(Player player) {

        if (healthUpdateTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(healthUpdateTaskId);
            healthUpdateTaskId = -1;
        }

        UUID uuid = player.getUniqueId();
        TeamPair teams = playerTeams.get(uuid);

        if (teams != null) {
            teams.selfTeam.unregister();
            teams.enemyTeam.unregister();
        }

        playerTeams.remove(uuid);

        for (UUID otherUuid : playerTeams.keySet()) {
            TeamPair otherTeams = playerTeams.get(otherUuid);
            if (otherTeams != null && otherTeams.enemyTeam.hasEntry(player.getName())) {
                otherTeams.enemyTeam.removeEntry(player.getName());
            }
        }

        player.setDisplayName(player.getName());
        player.setPlayerListName(player.getName());
        healthObjectives.remove(uuid);
    }

    public void resetAllPlayersNameColor() {
        for (TeamPair teams : playerTeams.values()) {
            teams.selfTeam.unregister();
            teams.enemyTeam.unregister();
        }

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.setDisplayName(player.getName());
            player.setPlayerListName(player.getName());
        }
        healthObjectives.clear();
        playerTeams.clear();
    }
}