package com.walrusone.skywarsreloaded.game;

import com.walrusone.skywarsreloaded.SkyWarsReloaded;
import com.walrusone.skywarsreloaded.enums.MatchState;
import com.walrusone.skywarsreloaded.managers.MatchManager;
import com.walrusone.skywarsreloaded.menus.gameoptions.objects.CoordLoc;
import com.walrusone.skywarsreloaded.utilities.Messaging;
import com.walrusone.skywarsreloaded.utilities.Util;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Periodically re-populates the arena chests while a match is running.
 * <p>
 * The countdown is driven by the existing one-second match tick in
 * {@link MatchManager}, so it can never outlive its own match. The remaining
 * time is exposed to the scoreboard through the {next_refill} placeholder and,
 * optionally, through floating holograms above the chests a player has opened.
 * On 1.8 a searched chest additionally keeps its lid open until the next refill.
 */
public class ChestRefillManager {

    /** Used when the configured interval is missing or invalid. */
    private static final int FALLBACK_INTERVAL = 60;
    /** Label of the "this chest is empty" hologram. */
    private static final String EMPTY_LABEL = ChatColor.RED + "" + ChatColor.BOLD + "\u2718";

    private final GameMap gMap;
    // Holograms are keyed by chest block so a chest is never decorated twice.
    private final Map<CoordLoc, ArmorStand> timerStands = new HashMap<>();
    private final Map<CoordLoc, ArmorStand> emptyStands = new HashMap<>();
    // Chests whose lid is held open so players can see they were already searched.
    private final Set<CoordLoc> forcedOpenChests = new HashSet<>();

    private int secondsRemaining;
    // Runs only while at least one chest has to be held open.
    private BukkitRunnable lidTask;

    ChestRefillManager(GameMap gMap) {
        this.gMap = gMap;
        this.secondsRemaining = getInterval();
    }

    // ------------------------------------------------------------------ timing

    /** Refill interval in seconds, as configured for the whole server. */
    public int getInterval() {
        int configured = SkyWarsReloaded.getCfg().getChestRefillInterval();
        return configured > 0 ? configured : FALLBACK_INTERVAL;
    }

    public int getSecondsRemaining() {
        return secondsRemaining;
    }

    /** Remaining time as "mm:ss", matching the {time} scoreboard placeholder. */
    public String getFormattedTimeLeft() {
        int seconds = Math.max(secondsRemaining, 0);
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * Advances the countdown by one second. Called once per second from the
     * match tick; does nothing unless the match is actually being played.
     */
    public void tick() {
        if (!SkyWarsReloaded.getCfg().isChestRefillEnabled()) return;
        if (gMap.getMatchState() != MatchState.PLAYING) return;

        if (secondsRemaining > 0) {
            secondsRemaining--;
        }

        if (secondsRemaining <= 0) {
            refill();
            secondsRemaining = getInterval();
        } else {
            refreshTimerHolograms();
        }
    }

    /** Resets the countdown and drops every hologram. Used when an arena resets. */
    public void reset() {
        secondsRemaining = getInterval();
        clearHolograms();
        // The arena world is about to be replaced: drop the task and forget the
        // lids without touching blocks that may already be gone.
        if (lidTask != null) {
            lidTask.cancel();
            lidTask = null;
        }
        forcedOpenChests.clear();
    }

    // ----------------------------------------------------------------- refilling

    /** Re-populates every chest of the arena and announces it to the players. */
    public void refill() {
        World world = gMap.getCurrentWorld();
        if (world == null) return;

        // Reuse the voted chest type so a refill keeps the loot table of the match.
        gMap.getChestOption().completeOption();

        // The holograms belong to the previous fill: let them be recreated on the
        // next chest interaction instead of showing a stale "empty" marker.
        clearHolograms();

        // A refilled chest is worth visiting again, so let every lid close.
        closeForcedOpenChests();

        MatchManager.get().message(gMap, new Messaging.MessageFormatter().format("game.chest-refill.broadcast"));

        if (SkyWarsReloaded.getCfg().titlesEnabled()) {
            String title = new Messaging.MessageFormatter().format("titles.chest-refill-title");
            String subtitle = new Messaging.MessageFormatter().format("titles.chest-refill-subtitle");
            for (Player player : gMap.getAlivePlayers()) {
                if (player != null) {
                    Util.get().sendTitle(player, 2, 30, 2, title, subtitle);
                }
            }
        }
    }

    // ---------------------------------------------------------------- holograms

    /** True when the given block is one of the arena's registered chests. */
    public boolean isArenaChest(CoordLoc loc) {
        return gMap.getChests().contains(loc) || gMap.getCenterChests().contains(loc);
    }

    /**
     * Shows (or updates) the countdown hologram of a chest. Mirrors the reference
     * behaviour: nothing floats above a chest until a player has opened it.
     */
    public void showHologram(CoordLoc loc) {
        if (!SkyWarsReloaded.getCfg().isChestRefillEnabled()) return;
        if (!SkyWarsReloaded.getCfg().isChestRefillHologramEnabled()) return;
        if (gMap.getMatchState() != MatchState.PLAYING) return;
        if (!isArenaChest(loc)) return;

        World world = gMap.getCurrentWorld();
        if (world == null) return;

        ArmorStand timer = timerStands.get(loc);
        if (timer == null || timer.isDead()) {
            timer = spawnStand(world, loc, 0.8D, timerText());
            if (timer == null) return;
            timerStands.put(loc, timer);
        } else {
            timer.setCustomName(timerText());
        }

        updateEmptyHologram(loc);
    }

    /**
     * Keeps a visited chest visually open until the next refill, so players can
     * see from a distance which chests have already been searched.
     * <p>
     * Only available on 1.8: the effect relies on re-sending the chest lid block
     * action every tick, which is a no-op on clients that animate the lid from
     * their own block-entity state.
     */
    public void holdChestOpen(CoordLoc loc) {
        if (!SkyWarsReloaded.getCfg().isChestRefillEnabled()) return;
        if (!SkyWarsReloaded.getCfg().isChestRefillKeepOpen()) return;
        if (!ChestLidPacket.isSupported()) return;
        if (gMap.getMatchState() != MatchState.PLAYING) return;
        if (!isArenaChest(loc)) return;

        forcedOpenChests.add(loc);
        startLidTask();
    }

    /**
     * Re-sends the "lid open" action for every searched chest, every tick.
     * <p>
     * The reference implementation runs an unconditional every-tick task for the
     * whole server; this one only exists while the arena actually has a searched
     * chest and only targets the players inside that arena, which produces the
     * same picture without touching players who cannot see these chests.
     */
    private void startLidTask() {
        if (lidTask != null) return;

        lidTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!refreshForcedOpenChests()) {
                    // Nothing left to hold open: stop until the next chest visit.
                    lidTask = null;
                    cancel();
                }
            }
        };
        lidTask.runTaskTimer(SkyWarsReloaded.get(), 0L, 1L);
    }

    /** @return false once the task has no reason to keep running */
    private boolean refreshForcedOpenChests() {
        if (forcedOpenChests.isEmpty()) return false;
        if (!SkyWarsReloaded.getCfg().isChestRefillKeepOpen()) return false;
        if (gMap.getMatchState() != MatchState.PLAYING) return false;

        World world = gMap.getCurrentWorld();
        if (world == null) return false;

        Collection<? extends Player> viewers = world.getPlayers();
        if (viewers.isEmpty()) return true;

        for (CoordLoc loc : new ArrayList<>(forcedOpenChests)) {
            // Drop chests that are no longer there (broken, world edited) so the
            // task can retire instead of failing forever.
            if (!sendLidState(world, viewers, loc, true)) {
                forcedOpenChests.remove(loc);
            }
        }
        return !forcedOpenChests.isEmpty();
    }

    /** Lets every held-open lid close again, e.g. after a refill or a match. */
    public void closeForcedOpenChests() {
        if (lidTask != null) {
            lidTask.cancel();
            lidTask = null;
        }
        if (forcedOpenChests.isEmpty()) return;

        World world = gMap.getCurrentWorld();
        if (world != null) {
            Collection<? extends Player> viewers = world.getPlayers();
            for (CoordLoc loc : forcedOpenChests) {
                sendLidState(world, viewers, loc, false);
            }
        }
        forcedOpenChests.clear();
    }

    /**
     * Sends the chest lid block action for a single chest.
     *
     * @return true when the block really is a chest and the packet was sent
     */
    private boolean sendLidState(World world, Collection<? extends Player> viewers, CoordLoc loc, boolean open) {
        Block block = new Location(world, loc.getX(), loc.getY(), loc.getZ()).getBlock();
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) return false;

        return ChestLidPacket.send(viewers, block, open);
    }

    /** Adds or removes the "empty" marker depending on the chest contents. */
    public void updateEmptyHologram(CoordLoc loc) {
        if (!timerStands.containsKey(loc)) return;

        World world = gMap.getCurrentWorld();
        if (world == null) return;

        ArmorStand empty = emptyStands.get(loc);
        if (isChestEmpty(world, loc)) {
            if (empty == null || empty.isDead()) {
                ArmorStand spawned = spawnStand(world, loc, 1.1D, EMPTY_LABEL);
                if (spawned != null) emptyStands.put(loc, spawned);
            }
        } else if (empty != null) {
            despawn(empty);
            emptyStands.remove(loc);
        }
    }

    /** Drops both holograms of a single chest, e.g. when the chest is broken. */
    public void removeHologram(CoordLoc loc) {
        despawn(timerStands.remove(loc));
        despawn(emptyStands.remove(loc));
        forcedOpenChests.remove(loc);
    }

    /**
     * Drops the holograms of every chest in the given list. Used for explosions,
     * which destroy many blocks at once and never fire a break event per block.
     *
     * @param blocks blocks about to be destroyed; non-chests are ignored
     */
    public void removeHologramsOf(Collection<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return;
        if (timerStands.isEmpty() && emptyStands.isEmpty() && forcedOpenChests.isEmpty()) return;

        for (Block block : blocks) {
            if (block == null) continue;
            if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) continue;
            removeHologram(new CoordLoc(block.getLocation()));
        }
    }

    /**
     * Drops holograms whose chest is no longer in the world.
     * <p>
     * This is the safety net behind the explicit break and explosion handling: a
     * chest can also disappear without any event this plugin sees, for example
     * through WorldEdit, a custom ability or another plugin removing the block.
     */
    private void dropOrphanedHolograms(World world) {
        if (timerStands.isEmpty()) return;

        for (CoordLoc loc : new ArrayList<>(timerStands.keySet())) {
            if (isChestPresent(world, loc)) continue;
            removeHologram(loc);
        }
    }

    private boolean isChestPresent(World world, CoordLoc loc) {
        Material type = new Location(world, loc.getX(), loc.getY(), loc.getZ()).getBlock().getType();
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
    }

    /** Drops every hologram of the arena. */
    public void clearHolograms() {
        for (ArmorStand stand : new ArrayList<>(timerStands.values())) {
            despawn(stand);
        }
        for (ArmorStand stand : new ArrayList<>(emptyStands.values())) {
            despawn(stand);
        }
        timerStands.clear();
        emptyStands.clear();
    }

    /**
     * Removes a hologram. The arena world is deleted and re-created between
     * matches, so a stand reference can already point into an unloaded world.
     */
    private void despawn(ArmorStand stand) {
        if (stand == null) return;
        try {
            if (!stand.isDead()) stand.remove();
        } catch (IllegalStateException ignored) {
            // The world holding this entity is gone; nothing left to clean up.
        }
    }

    private void refreshTimerHolograms() {
        if (timerStands.isEmpty()) return;

        World world = gMap.getCurrentWorld();
        if (world == null) return;

        // Once per second, before repainting the countdown, forget the chests that
        // no longer exist. Without this a chest blown up by TNT leaves its
        // hologram floating until the match ends.
        dropOrphanedHolograms(world);
        if (timerStands.isEmpty()) return;

        String text = timerText();
        for (Map.Entry<CoordLoc, ArmorStand> entry : new ArrayList<>(timerStands.entrySet())) {
            ArmorStand stand = entry.getValue();
            if (stand == null || stand.isDead()) {
                timerStands.remove(entry.getKey());
                continue;
            }
            stand.setCustomName(text);
        }
    }

    private String timerText() {
        return ChatColor.GREEN + getFormattedTimeLeft();
    }

    private ArmorStand spawnStand(World world, CoordLoc loc, double yOffset, String name) {
        Location standLocation = new Location(world, loc.getX() + 0.5D, loc.getY() + yOffset, loc.getZ() + 0.5D);
        try {
            ArmorStand stand = world.spawn(standLocation, ArmorStand.class);
            stand.setVisible(false);
            stand.setSmall(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setCanPickupItems(false);
            stand.setRemoveWhenFarAway(false);
            stand.setCustomName(name);
            stand.setCustomNameVisible(true);
            return stand;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            SkyWarsReloaded.get().getLogger().warning(
                    "Could not create a chest refill hologram in " + gMap.getName() + ": " + ex.getMessage());
            return null;
        }
    }

    private boolean isChestEmpty(World world, CoordLoc loc) {
        Location location = new Location(world, loc.getX(), loc.getY(), loc.getZ());
        if (!(location.getBlock().getState() instanceof Chest)) return true;

        Chest chest = (Chest) location.getBlock().getState();
        InventoryHolder holder = chest.getInventory().getHolder();
        Inventory inventory = holder instanceof DoubleChest
                ? ((DoubleChest) holder).getInventory()
                : chest.getInventory();

        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() != Material.AIR) return false;
        }
        return true;
    }

    /**
     * Resolves the chest blocks behind an inventory: a double chest reports a
     * single inventory but is registered as two separate arena chests.
     */
    public static List<CoordLoc> resolveChestBlocks(InventoryHolder holder) {
        List<CoordLoc> found = new ArrayList<>(2);
        if (holder instanceof Chest) {
            found.add(new CoordLoc(((Chest) holder).getLocation()));
        } else if (holder instanceof DoubleChest) {
            DoubleChest doubleChest = (DoubleChest) holder;
            InventoryHolder left = doubleChest.getLeftSide();
            InventoryHolder right = doubleChest.getRightSide();
            if (left instanceof Chest) found.add(new CoordLoc(((Chest) left).getLocation()));
            if (right instanceof Chest) found.add(new CoordLoc(((Chest) right).getLocation()));
        }
        return found;
    }
}
