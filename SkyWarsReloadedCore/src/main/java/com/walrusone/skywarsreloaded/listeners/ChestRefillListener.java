package com.walrusone.skywarsreloaded.listeners;

import com.walrusone.skywarsreloaded.SkyWarsReloaded;
import com.walrusone.skywarsreloaded.game.ChestRefillManager;
import com.walrusone.skywarsreloaded.game.GameMap;
import com.walrusone.skywarsreloaded.menus.gameoptions.objects.CoordLoc;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;

/**
 * Creates the floating chest countdown once a player actually opens an arena
 * chest, and keeps its "empty" marker in sync. Nothing floats above a chest
 * that has never been touched.
 * <p>
 * On 1.8 a chest a player has searched also keeps its lid open until the next
 * refill, so it is visible from a distance that someone was already there.
 */
public class ChestRefillListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        handle(event.getPlayer() instanceof Player ? (Player) event.getPlayer() : null,
                event.getInventory().getHolder());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = event.getPlayer() instanceof Player ? (Player) event.getPlayer() : null;
        if (player == null) return;

        GameMap gameMap = SkyWarsReloaded.getGameMapMgr().getMap(player.getWorld().getName());
        if (gameMap == null) return;

        ChestRefillManager refillManager = gameMap.getChestRefillManager();
        for (CoordLoc loc : ChestRefillManager.resolveChestBlocks(event.getInventory().getHolder())) {
            // Vanilla closes the lid right after this event; the manager's own
            // per-tick task re-opens it from here on.
            refillManager.holdChestOpen(loc);
            refillManager.showHologram(loc);
            refillManager.updateEmptyHologram(loc);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) return;

        GameMap gameMap = SkyWarsReloaded.getGameMapMgr().getMap(block.getWorld().getName());
        if (gameMap == null) return;

        // removeHologram also forgets the held-open lid of this chest.
        gameMap.getChestRefillManager().removeHologram(new CoordLoc(block.getLocation()));
    }

    /**
     * TNT, creepers and fireballs destroy chests without ever firing a break
     * event, which used to leave the countdown floating above thin air.
     * <p>
     * MONITOR runs after the cancel decision, but the blocks are still standing at
     * this point, so the holograms are dropped now while the coordinates can still
     * be matched against the arena's chest list.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.getLocation().getWorld(), event.blockList());
    }

    /** Bed and respawn-anchor style explosions, which report no entity. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.getBlock().getWorld(), event.blockList());
    }

    private void handleExplosion(World world, List<Block> destroyed) {
        if (world == null || destroyed.isEmpty()) return;

        GameMap gameMap = SkyWarsReloaded.getGameMapMgr().getMap(world.getName());
        if (gameMap == null) return;

        gameMap.getChestRefillManager().removeHologramsOf(destroyed);
    }

    private void handle(Player player, InventoryHolder holder) {
        if (player == null || holder == null) return;

        GameMap gameMap = SkyWarsReloaded.getGameMapMgr().getMap(player.getWorld().getName());
        if (gameMap == null) return;

        ChestRefillManager refillManager = gameMap.getChestRefillManager();
        List<CoordLoc> blocks = ChestRefillManager.resolveChestBlocks(holder);
        for (CoordLoc loc : blocks) {
            refillManager.showHologram(loc);
            refillManager.updateEmptyHologram(loc);
        }
    }
}
