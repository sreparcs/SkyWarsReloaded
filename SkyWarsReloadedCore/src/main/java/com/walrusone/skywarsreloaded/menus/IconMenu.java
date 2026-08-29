package com.walrusone.skywarsreloaded.menus;

import com.walrusone.skywarsreloaded.SkyWarsReloaded;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;

public class IconMenu {
    private ArrayList<Inventory> invs;
    private OptionClickEventHandler handler;
    private Runnable update;

    public IconMenu(ArrayList<Inventory> invs, OptionClickEventHandler optionClickEventHandler) {
        this.invs = invs;
        for (int i = 0; i < invs.size(); i++) {
            addExitItem((Inventory) invs.get(i));
            if ((invs.size() > 0) && (i + 1 < invs.size())) {
                addNextItem((Inventory) invs.get(i));
            }
            if ((i > 0) && (i < invs.size())) {
                addPrevItem((Inventory) invs.get(i));
            }
        }
        handler = optionClickEventHandler;
    }

    private void addPrevItem(Inventory inventory) {
        inventory.setItem(inventory.getSize() - 9, SkyWarsReloaded.getIM().getItem("prevPageItem"));
    }

    private void addNextItem(Inventory inventory) {
        inventory.setItem(inventory.getSize() - 1, SkyWarsReloaded.getIM().getItem("nextPageItem"));
    }

    private void addExitItem(Inventory inventory) {
        inventory.setItem(inventory.getSize() - 5, SkyWarsReloaded.getIM().getItem("exitMenuItem"));
    }

    public void update() {
        if (SkyWarsReloaded.get().isEnabled()) {
            org.bukkit.Bukkit.getScheduler().scheduleSyncDelayedTask(SkyWarsReloaded.get(), update);
        }
    }

    public void setUpdate(Runnable update2) {
        update = update2;
    }

    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        int index = invs.lastIndexOf(event.getInventory());
        if (index == -1) {
            return;
        }

        event.setCancelled(true);

        Inventory inv = invs.get(index);
        if (inv == null) {
            return;
        }

        // Only the menu's own slots are ours to handle. A raw slot at or above the
        // menu size belongs to the player's own inventory, and a negative one is
        // the click-outside-the-window slot (-999).
        int slot = event.getRawSlot();
        if ((slot < 0) || (slot >= inv.getSize())) {
            return;
        }

        // The item the handler acts on has to be the one this event reports, not a
        // second lookup: on a click in the player's own inventory the two are
        // different stacks, and the name would then belong to the wrong item.
        ItemStack clicked = event.getCurrentItem();
        if ((clicked == null) || clicked.getType().equals(Material.AIR)) {
            return;
        }

        String name = SkyWarsReloaded.getNMS().getItemName(clicked);
        if ((name == null) || name.isEmpty()) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        if (name.equalsIgnoreCase(SkyWarsReloaded.getNMS().getItemName(SkyWarsReloaded.getIM().getItem("prevPageItem")))) {
            openInventory(player, index - 1);
            return;
        }
        if ((name.equalsIgnoreCase(SkyWarsReloaded.getNMS().getItemName(SkyWarsReloaded.getIM().getItem("nextPageItem")))) && (index + 1 < invs.size())) {
            openInventory(player, index + 1);
            return;
        }
        handler.onOptionClick(new OptionClickEvent(player, name, event.getClick(), clicked.clone(), slot));
    }

    public Inventory getInventory(int index) {
        if ((index < 0) || (index >= invs.size())) {
            return null;
        }
        return invs.get(index);
    }

    public ArrayList<Inventory> getInventories() {
        return invs;
    }


    /**
     * Opens one page of this menu.
     * <p>
     * The actual open is delayed by one tick: this is regularly called from
     * inside an {@link InventoryClickEvent}, and swapping the window while the
     * server is still handling a click for the old one leaves the client sending
     * clicks against a container the server has already replaced. On 1.8 that
     * desync surfaces as an unchecked {@code Container.getSlot} out-of-bounds
     * crash in the packet handler, far away from this plugin.
     *
     * @param index page to open; out-of-range values are ignored
     */
    public void openInventory(final Player player, int index) {
        if (player == null) {
            return;
        }
        if ((index < 0) || (index >= invs.size())) {
            return;
        }

        // Resolve the page now: by the next tick this menu may have been replaced
        // for that player, and the click that got us here meant *this* page.
        final Inventory inv = invs.get(index);
        if (inv == null) {
            return;
        }

        // No scheduler while the plugin is shutting down: open straight away
        // rather than losing the menu entirely.
        if (!SkyWarsReloaded.get().isEnabled()) {
            player.openInventory(inv);
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    player.openInventory(inv);
                }
            }
        }.runTaskLater(SkyWarsReloaded.get(), 1L);
    }

    public static abstract interface OptionClickEventHandler {
        public abstract void onOptionClick(OptionClickEvent paramOptionClickEvent);
    }

    public static class OptionClickEvent {
        private Player player;
        private String name;
        private ClickType clickType;
        private ItemStack item;
        private int slot;

        OptionClickEvent(Player player, String name, ClickType clickType, ItemStack itemStack, int slot) {
            this.player = player;
            this.name = name;
            this.clickType = clickType;
            item = itemStack;
            this.slot = slot;
        }

        public Player getPlayer() {
            return player;
        }

        public String getName() {
            return name;
        }

        ClickType getClick() {
            return clickType;
        }

        public ItemStack getItem() {
            return item;
        }

        public int getSlot() {
            return slot;
        }
    }
}
