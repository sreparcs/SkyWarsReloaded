package com.walrusone.skywarsreloaded.listeners;

import com.walrusone.skywarsreloaded.SkyWarsReloaded;
import com.walrusone.skywarsreloaded.enums.GameType;
import com.walrusone.skywarsreloaded.events.SkyWarsWinEvent;
import com.walrusone.skywarsreloaded.game.GameMap;
import com.walrusone.skywarsreloaded.managers.MatchManager;
import com.walrusone.skywarsreloaded.utilities.Messaging;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Offers a one-click "play again" after an elimination or a win.
 * <p>
 * Two ways in, both ending in {@link #tryRejoin(Player)}:
 * <ul>
 *   <li>a paper item in the hotbar, right-clicked or clicked in the inventory;</li>
 *   <li>a clickable chat message running {@code /sw rejoin}.</li>
 * </ul>
 */
public class RejoinGameListener implements Listener {

    /** Hotbar slot of the paper. 8 is the exit door and 0-4 hold the vote items. */
    private static final int REJOIN_SLOT = 7;

    private static RejoinGameListener instance;

    /** Guards against two rejoin attempts running for the same player. */
    private final Set<UUID> pendingRejoins = new HashSet<>();

    public RejoinGameListener() {
        instance = this;
    }

    /** @return the registered listener, or null before the plugin finished loading */
    public static RejoinGameListener get() {
        return instance;
    }

    // --------------------------------------------------------------------- offering

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWin(SkyWarsWinEvent event) {
        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) return;

        // The win path may clear the inventory (game.win.clearInventory) right
        // before this event, so hand the paper out on the next tick.
        giveItemLater(player);
        sendOffer(player, "game.rejoin.won");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEliminated(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() != GameMode.SPECTATOR) return;
        // Only players who just got eliminated are in a match as spectator.
        if (MatchManager.get().getSpectatorMap(event.getPlayer()) == null) return;

        // addSpectator clears the inventory and fills it with player heads after
        // switching the game mode, so the paper has to come afterwards too.
        giveItemLater(event.getPlayer());
        sendOffer(event.getPlayer(), "game.rejoin.died");
    }

    /**
     * Places the paper one tick later, once whoever triggered this event has
     * finished rearranging the inventory.
     */
    private void giveItemLater(final Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) giveRejoinItem(player);
            }
        }.runTaskLater(SkyWarsReloaded.get(), 1L);
    }

    /** Puts the "play again" paper into the player's hotbar. */
    public void giveRejoinItem(Player player) {
        ItemStack item = buildRejoinItem();
        if (item == null) return;

        player.getInventory().setItem(REJOIN_SLOT, item);
        player.updateInventory();
    }

    /**
     * Builds the paper. Name and lore come from messages.yml so they can be
     * translated without touching the code.
     */
    private ItemStack buildRejoinItem() {
        ItemStack item = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        meta.setDisplayName(itemName());
        List<String> lore = new ArrayList<>(1);
        lore.add(itemLore());
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String itemName() {
        return new Messaging.MessageFormatter().format("game.rejoin.itemname");
    }

    private static String itemLore() {
        return new Messaging.MessageFormatter().format("game.rejoin.itemlore");
    }

    /**
     * Sends the clickable offer. Built as raw JSON and pushed through the NMS
     * layer because the plugin compiles against plain Bukkit, which has no
     * {@code Player#spigot} chat component API.
     */
    private void sendOffer(Player player, String messageKey) {
        String intro = new Messaging.MessageFormatter().format(messageKey);
        String action = new Messaging.MessageFormatter().format("game.rejoin.click-here");
        String hover = new Messaging.MessageFormatter().format("game.rejoin.hover");

        SkyWarsReloaded.getNMS().sendJSON(player, "[\"\",{\"text\":\"" + escape(intro) + "\"},"
                + "{\"text\":\"" + escape(action) + "\","
                + "\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/sw rejoin\"},"
                + "\"hoverEvent\":{\"action\":\"show_text\",\"value\":{\"text\":\"" + escape(hover) + "\"}}}]");
    }

    /** Escapes the characters that would break the raw chat JSON. */
    private String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------------------------------------------------------------------- clicking

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (!isRejoinItem(event.getItem())) return;

        event.setCancelled(true);
        scheduleRejoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        // Either the clicked slot or the item on the cursor can be the paper.
        if (!isRejoinItem(event.getCurrentItem()) && !isRejoinItem(event.getCursor())) return;

        event.setCancelled(true);
        event.getWhoClicked().closeInventory();
        scheduleRejoin((Player) event.getWhoClicked());
    }

    /**
     * Runs the rejoin on the next tick. A click arrives while Bukkit is still
     * inside the event, and joining a match moves the player and rewrites their
     * inventory, which is not safe to do from inside a click handler.
     */
    private void scheduleRejoin(final Player player) {
        if (!pendingRejoins.add(player.getUniqueId())) return;

        Bukkit.getScheduler().runTask(SkyWarsReloaded.get(), () -> {
            try {
                joinNewGame(player);
            } finally {
                pendingRejoins.remove(player.getUniqueId());
            }
        });
    }

    /**
     * True when this is the "play again" paper. Compared by material plus display
     * name so a plain piece of paper picked up in the arena is never mistaken for
     * it, and a renamed one cannot be forged into it.
     */
    private boolean isRejoinItem(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return false;

        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() && itemName().equals(meta.getDisplayName());
    }

    /** Removes every copy of the paper, so it cannot be carried into the new match. */
    private void clearRejoinItems(Player player) {
        boolean removed = false;
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (isRejoinItem(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
                removed = true;
            }
        }
        if (removed) player.updateInventory();
    }

    // ---------------------------------------------------------------------- rejoining

    /**
     * Moves the player into a new match. Public because {@code /sw rejoin} calls
     * straight into it.
     *
     * @return true when the player joined a new match
     */
    public boolean tryRejoin(Player player) {
        if (player == null || !player.isOnline()) return false;
        // The command and the item click can arrive in the same tick.
        if (!pendingRejoins.add(player.getUniqueId())) return false;

        try {
            return joinNewGame(player);
        } finally {
            pendingRejoins.remove(player.getUniqueId());
        }
    }

    private boolean joinNewGame(Player player) {
        if (!player.isOnline()) return false;

        if (!hasAvailableGame(player)) {
            player.sendMessage(new Messaging.MessageFormatter().format("game.rejoin.no-game"));
            return false;
        }

        // Free the UUID on the old match. Both lookups are needed: an eliminated
        // player keeps a dead card in the team AND sits in the spectator set, while
        // someone who only ever spectated is in the spectator set alone.
        // Do not call PlayerManager.removePlayer here. It restores PlayerData from
        // the completed match and can send Bungee players back to the configured
        // lobby, which would undo the join that follows.
        GameMap currentMap = MatchManager.get().getPlayerMap(player);
        if (currentMap != null) {
            currentMap.removePlayer(player.getUniqueId());
        }
        GameMap spectatingMap = MatchManager.get().getSpectatorMap(player);
        if (spectatingMap != null && spectatingMap != currentMap) {
            spectatingMap.removePlayer(player.getUniqueId());
        }

        clearRejoinItems(player);

        // A spectator is still in SPECTATOR mode here. joinGame teleports and then
        // sets ADVENTURE a few ticks later, so drop the flying state now to avoid a
        // player being stuck mid-air if that follow-up task is delayed.
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.ADVENTURE);
            player.setFlying(false);
            player.setAllowFlight(false);
        }

        GameMap joinedMap = null;
        for (int attempt = 0; attempt < 4 && joinedMap == null && player.isOnline(); attempt++) {
            joinedMap = MatchManager.get().joinGame(player, GameType.ALL);
        }

        if (joinedMap == null) {
            player.sendMessage(new Messaging.MessageFormatter().format("game.rejoin.full"));
            return false;
        }

        player.sendMessage(new Messaging.MessageFormatter()
                .setVariable("map", joinedMap.getDisplayName()).format("game.rejoin.joined"));
        return true;
    }

    private boolean hasAvailableGame(Player player) {
        for (GameMap map : SkyWarsReloaded.getGameMapMgr().getPlayableArenas(GameType.ALL)) {
            if (map.canAddPlayer(player)) return true;
        }
        return false;
    }
}
