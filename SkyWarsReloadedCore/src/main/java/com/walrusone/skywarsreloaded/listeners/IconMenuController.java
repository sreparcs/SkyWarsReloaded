package com.walrusone.skywarsreloaded.listeners;

import com.google.common.collect.Maps;
import com.walrusone.skywarsreloaded.SkyWarsReloaded;
import com.walrusone.skywarsreloaded.menus.IconMenu;
import com.walrusone.skywarsreloaded.menus.IconMenu.OptionClickEventHandler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitRunnable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Map;

public class IconMenuController
        implements Listener {
    private final Map<Player, IconMenu> menu = Maps.newHashMap();
    private final Map<String, IconMenu> persistantMenus = Maps.newHashMap();

    public IconMenuController() {
    }

    public void create(Player player, ArrayList<Inventory> invs, OptionClickEventHandler optionClickEventHandler) {
        if (player != null) {
            menu.put(player, new IconMenu(invs, optionClickEventHandler));
        }
    }

    public void create(String key, ArrayList<Inventory> invs, OptionClickEventHandler optionClickEventHandler) {
        if (key != null) {
            persistantMenus.put(key, new IconMenu(invs, optionClickEventHandler));
        }
    }

    public IconMenu getMenu(String string) {
        return (IconMenu) persistantMenus.get(string);
    }

    public boolean hasViewers(String key) {
        if (persistantMenus.get(key) != null) {
            for (Inventory inv : ((IconMenu) persistantMenus.get(key)).getInventories()) {
                if (!inv.getViewers().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Opens the first page of a menu.
     *
     * @param key persistent menu key, or null for the player's own menu
     */
    public void show(Player player, @Nullable String key) {
        IconMenu target = key != null ? persistantMenus.get(key) : menu.get(player);
        if (target == null) {
            return;
        }
        target.openInventory(player, 0);
    }

    private void destroy(Player key) {
        menu.remove(key);
    }

    public boolean has(Player player) {
        return menu.containsKey(player);
    }

    public boolean has(String key) {
        return persistantMenus.containsKey(key);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (((event.getWhoClicked() instanceof Player)) && (this.menu.containsKey(event.getWhoClicked()))) {
            ((IconMenu) this.menu.get(event.getWhoClicked())).onInventoryClick(event);
        }
        for (IconMenu menu : persistantMenus.values()) {
            if (menu.getInventories().contains(event.getInventory())) {
                menu.onInventoryClick(event);
                break;
            }
        }
    }

    /**
     * Forgets a player's temporary menu once they have really left it.
     * <p>
     * The check is delayed because closing one page to open the next also fires
     * this event: five ticks later the follow-up window is open and the player is
     * still inside the menu, so only a genuine exit reaches {@link #destroy}.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClose(final InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        if (!menu.containsKey(event.getPlayer())) return;

        new BukkitRunnable() {
            public void run() {
                Player player = (Player) event.getPlayer();
                IconMenu current = menu.get(player);
                // Already gone, or replaced by a menu this player never opened.
                if (current == null) return;

                if (!isViewing(player, current)) {
                    IconMenuController.this.destroy(player);
                }
            }
        }.runTaskLater(SkyWarsReloaded.get(), 5L);
    }

    /**
     * True while the player has one of the menu's pages open.
     * <p>
     * Compared against the top inventory of the open view: {@code getOpenInventory}
     * itself returns an {@link org.bukkit.inventory.InventoryView}, which can never
     * be an element of the page list.
     */
    private boolean isViewing(Player player, IconMenu iconMenu) {
        if (player.getOpenInventory() == null) return false;

        Inventory top = player.getOpenInventory().getTopInventory();
        if (top == null) return false;

        for (Inventory inv : iconMenu.getInventories()) {
            if (inv != null && inv.equals(top)) {
                return true;
            }
        }
        return false;
    }
}
