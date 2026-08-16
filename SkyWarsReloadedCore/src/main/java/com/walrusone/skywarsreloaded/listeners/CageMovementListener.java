package com.walrusone.skywarsreloaded.listeners;

import com.walrusone.skywarsreloaded.SkyWarsReloaded;
import com.walrusone.skywarsreloaded.enums.MatchState;
import com.walrusone.skywarsreloaded.events.SkyWarsJoinEvent;
import com.walrusone.skywarsreloaded.game.GameMap;
import com.walrusone.skywarsreloaded.game.PlayerCard;
import com.walrusone.skywarsreloaded.managers.MatchManager;
import com.walrusone.skywarsreloaded.menus.gameoptions.objects.CoordLoc;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Keeps waiting players inside their spawn cage until the cage is removed. */
public class CageMovementListener implements Listener {
    private static final double MAX_DISTANCE = 2.0D;
    private final Map<UUID, Location> cageOrigins = new HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSkyWarsJoin(final SkyWarsJoinEvent event) {
        new BukkitRunnable() {
            @Override
            public void run() {
                Player player = event.getPlayer();
                GameMap map = event.getGame();
                if (!player.isOnline() || MatchManager.get().getPlayerMap(player) != map) return;

                PlayerCard card = map.getPlayerCard(player);
                CoordLoc spawn = card == null ? null : card.getSpawn();
                if (spawn != null && map.getCurrentWorld() != null) {
                    cageOrigins.put(player.getUniqueId(), new Location(map.getCurrentWorld(), spawn.getX() + 0.5D, spawn.getY() + 1.0D, spawn.getZ() + 0.5D));
                } else {
                    cageOrigins.put(player.getUniqueId(), player.getLocation().clone());
                }
            }
        }.runTaskLater(SkyWarsReloaded.get(), 8L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        enforceCage(playerMap(event.getPlayer()), event.getPlayer(), to, event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        enforceCage(playerMap(event.getPlayer()), event.getPlayer(), event.getTo(), event);
    }

    private GameMap playerMap(Player player) {
        return MatchManager.get().getPlayerMap(player);
    }

    private void enforceCage(GameMap map, Player player, Location target, PlayerMoveEvent event) {
        if (target == null || map == null || !isWaitingForStart(map)) {
            cageOrigins.remove(player.getUniqueId());
            return;
        }
        Location origin = cageOrigins.get(player.getUniqueId());
        if (origin == null || !origin.getWorld().equals(target.getWorld()) || !isCagePresent(origin)) return;
        if (isOutsideCage(origin, target)) event.setTo(returnLocation(origin, target));
    }

    private void enforceCage(GameMap map, Player player, Location target, PlayerTeleportEvent event) {
        if (target == null || map == null || !isWaitingForStart(map)) {
            cageOrigins.remove(player.getUniqueId());
            return;
        }
        Location origin = cageOrigins.get(player.getUniqueId());
        if (origin == null || !origin.getWorld().equals(target.getWorld()) || !isCagePresent(origin)) return;
        if (isOutsideCage(origin, target)) event.setTo(returnLocation(origin, target));
    }

    private boolean isWaitingForStart(GameMap map) {
        return map.getMatchState() == MatchState.WAITINGSTART || map.getMatchState() == MatchState.WAITINGLOBBY;
    }

    private boolean isOutsideCage(Location origin, Location target) {
        return Math.abs(target.getX() - origin.getX()) > MAX_DISTANCE
                || Math.abs(target.getY() - origin.getY()) > MAX_DISTANCE
                || Math.abs(target.getZ() - origin.getZ()) > MAX_DISTANCE;
    }

    private Location returnLocation(Location origin, Location target) {
        Location result = origin.clone();
        result.setYaw(target.getYaw());
        result.setPitch(target.getPitch());
        return result;
    }

    private boolean isCagePresent(Location origin) {
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    Material material = origin.clone().add(x, y, z).getBlock().getType();
                    if (material == Material.GLASS || material.name().contains("GLASS")) return true;
                }
            }
        }
        return false;
    }
}

