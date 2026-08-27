package com.walrusone.skywarsreloaded.listeners;

import com.walrusone.skywarsreloaded.SkyWarsReloaded;
import com.walrusone.skywarsreloaded.enums.MatchState;
import com.walrusone.skywarsreloaded.enums.PlayerRemoveReason;
import com.walrusone.skywarsreloaded.game.GameMap;
import com.walrusone.skywarsreloaded.game.PlayerData;
import com.walrusone.skywarsreloaded.managers.MatchManager;
import com.walrusone.skywarsreloaded.menus.gameoptions.objects.CoordLoc;
import com.walrusone.skywarsreloaded.utilities.Util;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PlayerDeathListener implements org.bukkit.event.Listener {

    /**
     * Players whose vanilla death is currently being converted into a SkyWars elimination.
     * The respawn handler uses this to keep them out of the arena world spawn.
     */
    private final Set<UUID> pendingDeaths = new HashSet<>();

    public PlayerDeathListener() {
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeathByDamageEvent(EntityDamageEvent e) {
        // Sanity checks
        if (e.isCancelled()) return;
        if (!(e.getEntity() instanceof Player)) return;

        // Make sure the player is in a game
        Player player = (Player) e.getEntity();
        GameMap gameMap = MatchManager.get().getPlayerMap(player);
        if (gameMap == null) return;

        // Handle fall damage
        if (!gameMap.getAllowFallDamage() && e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            e.setCancelled(true);
            return;
        }

        // Only real in-game deaths are eliminations. Damage during the cage/lobby countdown,
        // during the PVP grace timer and during the ending phase is cancelled by
        // ArenaDamageListener, which runs at HIGHEST - after this handler. Without this guard a
        // lethal hit in those states would already have dropped and wiped the player's
        // inventory and eliminated them before that cancellation happens.
        if (gameMap.getMatchState() != MatchState.PLAYING ||
                gameMap.getSpectators().contains(player.getUniqueId()) ||
                !gameMap.getAlivePlayers().contains(player)) {
            return;
        }
        if (gameMap.isDisableDamage() && e.getCause() != EntityDamageEvent.DamageCause.VOID) {
            return;
        }

        // If the player doesn't die from damage, ignore
        if (player.getHealth() - e.getFinalDamage() > 0) return;

        // Player fake damage sound if dying
        SkyWarsReloaded.getNMS().playGameSound(
                player.getLocation(),
                "ENTITY_PLAYER_DEATH",
                "PLAYERS",
                1,
                1,
                false);

        // Take into account if the player is holding a totem of undying (1.9+)
        if (e.getCause() != EntityDamageEvent.DamageCause.VOID &&
                e.getCause() != EntityDamageEvent.DamageCause.CUSTOM &&
                SkyWarsReloaded.getNMS().isHoldingTotem(player))
        {
            e.setDamage(player.getHealth() - 1);
            // Apply potion effects (global)
            player.addPotionEffect(
                    new PotionEffect(
                            PotionEffectType.REGENERATION, 20 * 45, 1, false, true));
            player.addPotionEffect(
                    new PotionEffect(
                            PotionEffectType.ABSORPTION, 20 * 5, 1, false, true));
            // Show effect on screen, show particles and apply fire resistance in 1.16.2+
            SkyWarsReloaded.getNMS().applyTotemEffect(player);
            return;
        }

        // Drop player items
        boolean canPickup = player.getCanPickupItems();
        player.setCanPickupItems(false);
        Location playerDeathLoc = player.getLocation().clone();
        World deathWorld = playerDeathLoc.getWorld();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) continue;
            deathWorld.dropItemNaturally(playerDeathLoc, item);
        }

        // Reset health & clear inv
        e.setCancelled(true);
        player.setHealthScale(20);
        player.setMaxHealth(20);
        player.setHealth(20);
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[] {null, null, null, null});

        // Handle cause of death & player removal
        EntityDamageEvent.DamageCause damageCause = EntityDamageEvent.DamageCause.CUSTOM;
        if (player.getLastDamageCause() != null) {
            damageCause = e.getCause();
        }

        SkyWarsReloaded.get().getPlayerManager().removePlayer(
                player, PlayerRemoveReason.DEATH, damageCause, true);

        // Reset pickup state as it was before now the the player is either in spectator mode or removed
        player.setCanPickupItems(canPickup);

        // Update the scoreboard for all current player
        gameMap.getGameBoard().updateScoreboard();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeathByDeathEvent(PlayerDeathEvent event) {
        final Player player = event.getEntity();
        final GameMap gameMap = MatchManager.get().getPlayerMap(player);

        if (gameMap == null || gameMap.getMatchState() != MatchState.PLAYING ||
                !gameMap.getAlivePlayers().contains(player)) {
            // The player is not an active competitor, but they may still have died inside an
            // arena world (already eliminated, spectating, or the match just ended). Vanilla
            // would respawn them at that arena's world spawn - above the map - so route the
            // respawn out of the arena instead.
            GameMap arenaWorldMap = SkyWarsReloaded.getGameMapMgr().getMap(player.getWorld().getName());
            if (arenaWorldMap != null) {
                pendingDeaths.add(player.getUniqueId());
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        pendingDeaths.remove(player.getUniqueId());
                    }
                }.runTaskLater(SkyWarsReloaded.get(), 20L);
            }
            return;
        }

        // This is a fallback for deaths not intercepted by the damage listener (for example a
        // hit whose final damage is raised by another handler running after this listener).
        // The inventory is dropped by the server at the death location, exactly like the
        // damage path does, so the kill still leaves loot behind.
        event.setDeathMessage("");
        event.setDroppedExp(0);

        // Mark the player so the respawn handler below can override the vanilla respawn
        // location. Without this the player is placed at the arena world spawn - alive,
        // in survival mode and with an empty inventory - instead of being eliminated.
        pendingDeaths.add(player.getUniqueId());

        final EntityDamageEvent.DamageCause damageCause = player.getLastDamageCause() == null
                ? EntityDamageEvent.DamageCause.CUSTOM
                : player.getLastDamageCause().getCause();

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (!player.isOnline()) return;
                    // Skip the death screen. This fires PlayerRespawnEvent, which is handled
                    // below while this UUID is still marked as a pending death.
                    if (player.isDead()) Util.get().respawnPlayer(player);
                    // Re-read the map: the player may already have been removed in the meantime.
                    if (MatchManager.get().getPlayerMap(player) == null) return;
                    SkyWarsReloaded.get().getPlayerManager().removePlayer(
                            player, PlayerRemoveReason.DEATH, damageCause, true);
                } finally {
                    pendingDeaths.remove(player.getUniqueId());
                }
            }
        }.runTask(SkyWarsReloaded.get());
    }

    @EventHandler
    public void onQuickDeath(PlayerMoveEvent e) {
        if (e.isCancelled()) return;

        Player player = e.getPlayer();
        GameMap gameMap = MatchManager.get().getPlayerMap(player);

        if (gameMap == null) {
            return;
        }

        if (gameMap.getMatchState() == MatchState.ENDING &&
                gameMap.getAlivePlayers().contains(player) &&
                e.getTo().getY() <= SkyWarsReloaded.getCfg().getQuickDeathY()
        ) {
            CoordLoc loc = gameMap.getSpectateSpawn();
            player.teleport(new Location(gameMap.getCurrentWorld(), loc.getX(), loc.getY(), loc.getZ()));
        }

        if (player.getGameMode().equals(GameMode.SURVIVAL)) {
            if (SkyWarsReloaded.getCfg().getEnableQuickDeath()) {
                if (e.getTo().getY() <= SkyWarsReloaded.getCfg().getQuickDeathY()) {
                    if (gameMap.getMatchState() == MatchState.PLAYING) {
                        EntityDamageEvent.DamageCause damageCause = EntityDamageEvent.DamageCause.VOID;
                        if (player.getLastDamageCause() != null) {
                            damageCause = player.getLastDamageCause().getCause();
                        }
                        SkyWarsReloaded.get().getPlayerManager().removePlayer(
                                player, PlayerRemoveReason.DEATH, damageCause, true);
                        // MatchManager.get().removeAlivePlayer(e.getPlayer(), damageCause, false, true);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRespawn(final PlayerRespawnEvent a1) {
        final Player respawningPlayer = a1.getPlayer();

        // Vanilla death that is still being converted into an elimination: the player is not
        // flagged dead in the game map yet, so getDeadPlayerMap() cannot find them. Send them
        // to the spectator spawn (or the lobby) instead of the arena world spawn.
        if (pendingDeaths.contains(respawningPlayer.getUniqueId())) {
            GameMap pendingMap = MatchManager.get().getPlayerMap(respawningPlayer);
            Location target = null;
            if (pendingMap != null && SkyWarsReloaded.getCfg().spectateEnable()) {
                World world = pendingMap.getCurrentWorld();
                CoordLoc specSpawn = pendingMap.getSpectateSpawn();
                if (world != null && specSpawn != null) {
                    target = new Location(world, specSpawn.getX(), specSpawn.getY(), specSpawn.getZ());
                }
            }
            if (target == null) target = SkyWarsReloaded.getCfg().getSpawn();
            if (target != null) a1.setRespawnLocation(target);
            return;
        }

        final PlayerData pData = PlayerData.getPlayerData(respawningPlayer.getUniqueId());
        if (pData != null) {
            if (SkyWarsReloaded.getCfg().spectateEnable()) {
                final GameMap gMap = MatchManager.get().getDeadPlayerMap(a1.getPlayer());
                if (gMap != null) {
                    World world = gMap.getCurrentWorld();
                    CoordLoc cLoc = gMap.getSpectateSpawn();
                    Location respawn = new Location(world, cLoc.getX(), cLoc.getY(), cLoc.getZ());
                    a1.setRespawnLocation(respawn);
                   /* new BukkitRunnable() {
                        public void run() {
                            SkyWarsReloaded.get().getPlayerManager().addSpectator(gMap, a1.getPlayer());
                        }
                    }.runTaskLater(SkyWarsReloaded.get(), 1L);*/
                }
            } else {
                GameMap gMap = MatchManager.get().getDeadPlayerMap(a1.getPlayer());
                if (gMap != null) {
                    World world = gMap.getCurrentWorld();
                    Location respawn = SkyWarsReloaded.getCfg().getSpawn();
                    if (respawn == null) respawn = new Location(world, 0.0D, 200.0D, 0.0D);
                    a1.setRespawnLocation(respawn);
                    new BukkitRunnable() {
                        public void run() {
                            pData.restoreToBeforeGameState(false);
                        }
                    }.runTaskLater(SkyWarsReloaded.get(), 1L);
                }
            }
        }
        if (Util.get().isSpawnWorld(a1.getPlayer().getWorld())) {
            a1.setRespawnLocation(SkyWarsReloaded.getCfg().getSpawn());
            com.walrusone.skywarsreloaded.managers.PlayerStat.updatePlayer(a1.getPlayer().getUniqueId().toString());
        }

        // Final safety net: never leave a respawn pointing into an arena world unless the
        // player actually belongs to that match (alive or spectating). A leftover arena
        // respawn is what drops players onto/above the map with an empty inventory.
        Location finalRespawn = a1.getRespawnLocation();
        if (finalRespawn != null && finalRespawn.getWorld() != null) {
            GameMap arenaMap = SkyWarsReloaded.getGameMapMgr().getMap(finalRespawn.getWorld().getName());
            if (arenaMap != null &&
                    !arenaMap.getSpectators().contains(respawningPlayer.getUniqueId()) &&
                    MatchManager.get().getPlayerMap(respawningPlayer) != arenaMap) {
                Location lobby = SkyWarsReloaded.getCfg().getSpawn();
                if (lobby != null) a1.setRespawnLocation(lobby);
            }
        }
    }
}
