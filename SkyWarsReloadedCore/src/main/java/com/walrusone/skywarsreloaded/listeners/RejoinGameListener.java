package com.walrusone.skywarsreloaded.listeners;

import com.walrusone.skywarsreloaded.SkyWarsReloaded;
import com.walrusone.skywarsreloaded.enums.GameType;
import com.walrusone.skywarsreloaded.events.SkyWarsWinEvent;
import com.walrusone.skywarsreloaded.game.GameMap;
import com.walrusone.skywarsreloaded.managers.MatchManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Provides an in-server quick-play action after elimination or victory. */
public class RejoinGameListener implements Listener {
    private static final String REJOIN_ITEM_NAME = ChatColor.AQUA + "" + ChatColor.BOLD + "再玩一局";
    private static final String REJOIN_ITEM_LORE = ChatColor.GRAY + "右键以开始下一把游戏！";
    private final Set<UUID> pendingRejoins = new HashSet<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWin(SkyWarsWinEvent event) {
        Player player = event.getPlayer();
        if (player != null && player.isOnline()) {
            giveRejoinItem(player);
            player.sendMessage(ChatColor.GOLD + "你赢了！" + ChatColor.YELLOW + " 使用背包中的" + REJOIN_ITEM_NAME + ChatColor.YELLOW + "开始下一局。");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEliminated(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() != GameMode.SPECTATOR) return;
        GameMap map = MatchManager.get().getSpectatorMap(event.getPlayer());
        if (map != null) {
            giveRejoinItem(event.getPlayer());
            event.getPlayer().sendMessage(ChatColor.RED + "你已被淘汰。" + ChatColor.YELLOW + " 使用背包中的" + REJOIN_ITEM_NAME + ChatColor.YELLOW + "直接加入新游戏。");
        }
    }

    public void giveRejoinItem(Player player) {
        ItemStack item = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(REJOIN_ITEM_NAME);
        meta.setLore(Arrays.asList(REJOIN_ITEM_LORE));
        item.setItemMeta(meta);
        player.getInventory().setItem(7, item);
        player.updateInventory();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (!isRejoinItem(event.getItem())) return;
        event.setCancelled(true);
        scheduleRejoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player) || !isRejoinItem(event.getCurrentItem())) return;
        event.setCancelled(true);
        event.getWhoClicked().closeInventory();
        scheduleRejoin((Player) event.getWhoClicked());
    }

    private void scheduleRejoin(final Player player) {
        if (!pendingRejoins.add(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(SkyWarsReloaded.get(), () -> {
            try {
                tryRejoin(player);
            } finally {
                pendingRejoins.remove(player.getUniqueId());
            }
        });
    }

    public boolean tryRejoin(Player player) {
        if (!player.isOnline()) return false;
        if (!hasAvailableGame(player)) {
            player.sendMessage(ChatColor.RED + "当前没有可加入的新游戏。");
            return false;
        }
        GameMap currentMap = MatchManager.get().getPlayerMap(player);
        if (currentMap != null) {
            // Do not call PlayerManager.removePlayer here. It restores PlayerData from the
            // completed match and can send Bungee players back to the configured lobby.
            // Removing this UUID from the old map is enough to free it for the new match.
            currentMap.removePlayer(player.getUniqueId());
        }
        clearRejoinItems(player);
        GameMap joinedMap = null;
        for (int attempt = 0; attempt < 4 && joinedMap == null && player.isOnline(); attempt++) {
            joinedMap = MatchManager.get().joinGame(player, GameType.ALL);
        }
        if (joinedMap == null) {
            player.sendMessage(ChatColor.RED + "新游戏已满，请稍后再试。");
            return false;
        }
        player.sendMessage(ChatColor.GREEN + "已直接加入新游戏：" + joinedMap.getDisplayName());
        return true;
    }

    private boolean hasAvailableGame(Player player) {
        for (GameMap map : SkyWarsReloaded.getGameMapMgr().getPlayableArenas(GameType.ALL)) {
            if (map.canAddPlayer(player)) return true;
        }
        return false;
    }

    private void clearRejoinItems(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (isRejoinItem(player.getInventory().getItem(slot))) player.getInventory().setItem(slot, null);
        }
        player.updateInventory();
    }

    private boolean isRejoinItem(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasDisplayName() && REJOIN_ITEM_NAME.equals(meta.getDisplayName())
                && meta.hasLore() && meta.getLore().contains(REJOIN_ITEM_LORE);
    }
}

