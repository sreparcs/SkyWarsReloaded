package com.walrusone.skywarsreloaded.commands.player;

import com.walrusone.skywarsreloaded.commands.BaseCmd;
import com.walrusone.skywarsreloaded.listeners.RejoinGameListener;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Puts the sender straight into a new match. This is the command behind the
 * clickable "play again" chat message shown after a death or a win.
 */
public class SWRejoinCmd extends BaseCmd {

    public SWRejoinCmd(String t) {
        type = t;
        forcePlayer = true;
        cmdName = "rejoin";
        alias = new String[]{"again", "rj"};
        argLength = 1;
    }

    @Override
    public boolean run(CommandSender sender, Player player, String[] args) {
        RejoinGameListener listener = RejoinGameListener.get();
        if (listener == null) {
            // The listener is registered during onEnable, so this only happens if
            // the command is somehow reached before the plugin finished loading.
            return true;
        }
        listener.tryRejoin(player);
        return true;
    }
}
