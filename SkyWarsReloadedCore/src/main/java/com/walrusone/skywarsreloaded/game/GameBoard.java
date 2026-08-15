package com.walrusone.skywarsreloaded.game;

import com.walrusone.skywarsreloaded.SkyWarsReloaded;
import com.walrusone.skywarsreloaded.enums.MatchState;
import com.walrusone.skywarsreloaded.enums.ScoreVar;
import com.walrusone.skywarsreloaded.managers.PlayerStat;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class GameBoard {
    private GameMap gMap;
    private int restartTimer;

    GameBoard(GameMap gMap) {
        this.gMap = gMap;
        this.restartTimer = -1;
        setupScoreBoard();
    }

    private void setupScoreBoard() {
        // todo add new code for teams
        updateScoreboard();
    }

    public void updateScoreboard() {
        for (Player player : gMap.getAllPlayers()) {
            if (player == null) continue;
            updateScoreboard(player);
        }
    }

    public void updateScoreboard(Player player) {
        String sb = "";
        if (gMap.getMatchState() == MatchState.WAITINGSTART || gMap.getMatchState() == MatchState.WAITINGLOBBY) {
            if (gMap.getAllPlayers().size() >= gMap.getMinTeams() || (gMap.getForceStart() && gMap.getAllPlayers().size() != 0)) {
                sb = "waitboard-countdown";
            } else {
                sb = "waitboard";
            }
        } else if (gMap.getMatchState() == MatchState.PLAYING) {
            sb = "playboard";
        } else if (gMap.getMatchState() == MatchState.ENDING) {
            sb = "endboard";
            if (restartTimer == -1) {
                startRestartTimer();
            }
        }

        // SWR 原生逻辑：更新玩家计分板
        PlayerStat.updateScoreboard(player, sb);

        if (gMap.getMatchState() == MatchState.ENDING || gMap.getMatchState() == MatchState.OFFLINE) {
            return;
        }

        // ========== 核心注入：SWR 更新完计分板后，立刻加颜色 ==========
        try {
            // 1. 获取当前玩家的计分板（就是 SWR 刚设置的那个）
            org.bukkit.scoreboard.Scoreboard scoreboard = player.getScoreboard();
            if (scoreboard == null) {
                scoreboard = org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard();
            }

            // 2. 初始化颜色管理器（用 SWR 的 GameMap）
            if (gMap.nameColorManager == null) {
                gMap.nameColorManager = new PlayerNameColorManager(SkyWarsReloaded.get(), gMap);
            }

            // 3. 给当前玩家注入颜色（用 SWR 的计分板）
            gMap.nameColorManager.initNameColorTeams(player, gMap.getAllPlayers(), player.getUniqueId());

            // 日志验证（可选）

        } catch (Exception e) {
            SkyWarsReloaded.get().getLogger().severe("[SWR-" + gMap.getName() + "] 注入颜色失败：" + e.getMessage());
        }
    }

    public void updateScoreboardVar(ScoreVar var) {
        /*updateScoreboard();*/
    }


    private void startRestartTimer() {
        restartTimer = SkyWarsReloaded.getCfg().getTimeAfterMatch();
        if (SkyWarsReloaded.get().isEnabled()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (gMap.getMatchState() != MatchState.ENDING) {
                        this.cancel();
                        return;
                    }

                    // Keep this task alive for the zero tick. MatchManager uses that
                    // tick to remove everyone and refresh the arena.
                    if (restartTimer > 0) {
                        restartTimer--;
                    }
                    updateScoreboard();
                }
            }.runTaskTimer(SkyWarsReloaded.get(), 0, 20);
        }
    }
    public int getRestartTimer() {
        return restartTimer;
    }

    public void setRestartTimer(int i) {
        restartTimer = i;
    }
}