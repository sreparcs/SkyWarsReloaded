package com.walrusone.skywarsreloaded.menus.gameoptions;

import com.walrusone.skywarsreloaded.SkyWarsReloaded;
import com.walrusone.skywarsreloaded.enums.Vote;
import com.walrusone.skywarsreloaded.game.GameMap;
import com.walrusone.skywarsreloaded.managers.MatchManager;
import com.walrusone.skywarsreloaded.menus.IconMenu;
import com.walrusone.skywarsreloaded.utilities.Messaging;
import com.walrusone.skywarsreloaded.utilities.Util;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;

public class VotingMenu {

    private static final String menuName = new Messaging.MessageFormatter().format("menu.options-menu-title");

    public VotingMenu(final Player player) {
        int menuSize = 27;
        GameMap gMap = MatchManager.get().getPlayerMap(player);
        if (gMap != null && player != null) {
            Inventory inv = Bukkit.createInventory(null, menuSize + 9, menuName);

            if (SkyWarsReloaded.getCfg().isChestVoteEnabled()) {
                if (player.hasPermission("sw.chestvote")) {
                    inv.setItem(SkyWarsReloaded.getCfg().getChestVotePos(), SkyWarsReloaded.getIM().getItem("chestvote"));
                } else {
                    inv.setItem(SkyWarsReloaded.getCfg().getChestVotePos(), SkyWarsReloaded.getIM().getItem("nopermission"));
                }
            }
            if (SkyWarsReloaded.getCfg().isHealthVoteEnabled()) {
                if (player.hasPermission("sw.healthvote")) {
                    inv.setItem(SkyWarsReloaded.getCfg().getHealthVotePos(), SkyWarsReloaded.getIM().getItem("healthvote"));
                } else {
                    inv.setItem(SkyWarsReloaded.getCfg().getHealthVotePos(), SkyWarsReloaded.getIM().getItem("nopermission"));
                }
            }
            if (SkyWarsReloaded.getCfg().isTimeVoteEnabled()) {
                if (player.hasPermission("sw.timevote")) {
                    inv.setItem(SkyWarsReloaded.getCfg().getTimeVotePos(), SkyWarsReloaded.getIM().getItem("timevote"));
                } else {
                    inv.setItem(SkyWarsReloaded.getCfg().getTimeVotePos(), SkyWarsReloaded.getIM().getItem("nopermission"));
                }
            }
            if (SkyWarsReloaded.getCfg().isWeatherVoteEnabled()) {
                if (player.hasPermission("sw.weathervote")) {
                    inv.setItem(SkyWarsReloaded.getCfg().getWeatherVotePos(), SkyWarsReloaded.getIM().getItem("weathervote"));
                } else {
                    inv.setItem(SkyWarsReloaded.getCfg().getWeatherVotePos(), SkyWarsReloaded.getIM().getItem("nopermission"));
                }
            }
            if (SkyWarsReloaded.getCfg().isModifierVoteEnabled()) {
                if (player.hasPermission("sw.modifiervote")) {
                    inv.setItem(SkyWarsReloaded.getCfg().getModifierVotePos(), SkyWarsReloaded.getIM().getItem("modifiervote"));
                } else {
                    inv.setItem(SkyWarsReloaded.getCfg().getModifierVotePos(), SkyWarsReloaded.getIM().getItem("nopermission"));
                }
            }

            ArrayList<Inventory> invs = new ArrayList<>();
            invs.add(inv);

            SkyWarsReloaded.getIC().create(player, invs, event -> {
                String name = event.getName();

                if (name.equalsIgnoreCase(new Messaging.MessageFormatter().format("items.chest-item"))) {
                    openVoteMenu(player, gMap.getChestOption().getKey(),
                            gMap.getChestOption().getVote(gMap.getPlayerCard(player)),
                            Vote.CHESTBASIC, Vote.CHESTNORMAL, Vote.CHESTOP, Vote.CHESTSCAVENGER,
                            SkyWarsReloaded.getCfg().getOpenChestMenuSound());
                } else if (name.equalsIgnoreCase(new Messaging.MessageFormatter().format("items.health-item"))) {
                    openVoteMenu(player, gMap.getHealthOption().getKey(),
                            gMap.getHealthOption().getVote(gMap.getPlayerCard(player)),
                            Vote.HEALTHFIVE, Vote.HEALTHTEN, Vote.HEALTHFIFTEEN, Vote.HEALTHTWENTY,
                            SkyWarsReloaded.getCfg().getOpenHealthMenuSound());
                } else if (name.equalsIgnoreCase(new Messaging.MessageFormatter().format("items.time-item"))) {
                    openVoteMenu(player, gMap.getTimeOption().getKey(),
                            gMap.getTimeOption().getVote(gMap.getPlayerCard(player)),
                            Vote.TIMEDAWN, Vote.TIMENOON, Vote.TIMEDUSK, Vote.TIMEMIDNIGHT,
                            SkyWarsReloaded.getCfg().getOpenTimeMenuSound());
                } else if (name.equalsIgnoreCase(new Messaging.MessageFormatter().format("items.weather-item"))) {
                    openVoteMenu(player, gMap.getWeatherOption().getKey(),
                            gMap.getWeatherOption().getVote(gMap.getPlayerCard(player)),
                            Vote.WEATHERSUN, Vote.WEATHERRAIN, Vote.WEATHERTHUNDER, Vote.WEATHERSNOW,
                            SkyWarsReloaded.getCfg().getOpenWeatherMenuSound());
                } else if (name.equalsIgnoreCase(new Messaging.MessageFormatter().format("items.modifier-item"))) {
                    openVoteMenu(player, gMap.getModifierOption().getKey(),
                            gMap.getModifierOption().getVote(gMap.getPlayerCard(player)),
                            Vote.MODIFIERSPEED, Vote.MODIFIERJUMP, Vote.MODIFIERSTRENGTH, Vote.MODIFIERNONE,
                            SkyWarsReloaded.getCfg().getOpenModifierMenuSound());
                } else if (name.equalsIgnoreCase(new Messaging.MessageFormatter().format("items.exit-menu-item"))) {
                    player.closeInventory();
                }
            });

            SkyWarsReloaded.getIC().show(player, null);
        }

    }

    /**
     * Opens one of the five vote sub-menus and highlights the option this player
     * has already voted for.
     * <p>
     * The highlight is applied to the sub-menu's own inventory rather than to
     * whatever the player currently has open: the window itself is swapped one
     * tick later, so at this point the player is still looking at this menu.
     *
     * @param voted the player's current vote, or null when they have not voted
     */
    private void openVoteMenu(Player player, String key, Vote voted,
                              Vote slotEleven, Vote slotThirteen, Vote slotFifteen, Vote slotSeventeen,
                              String openSound) {
        SkyWarsReloaded.getIC().show(player, key);

        IconMenu menu = SkyWarsReloaded.getIC().getMenu(key);
        if (menu != null && voted != null) {
            Inventory target = menu.getInventory(0);
            if (voted == slotEleven) Util.get().glowItem(target, 11);
            else if (voted == slotThirteen) Util.get().glowItem(target, 13);
            else if (voted == slotFifteen) Util.get().glowItem(target, 15);
            else if (voted == slotSeventeen) Util.get().glowItem(target, 17);
        }

        Util.get().playSound(player, player.getLocation(), openSound, 1, 1);
    }
}