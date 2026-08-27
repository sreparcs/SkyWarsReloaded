package com.walrusone.skywarsreloaded.game;

import com.walrusone.skywarsreloaded.SkyWarsReloaded;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Sends the 1.8 chest lid block action straight to a set of players.
 * <p>
 * {@code NMS.playChestAction} routes through {@code World#playBlockAction},
 * which only reaches players within 64 blocks of the chest and collapses
 * repeated actions inside a single tick. That is not enough to advertise a
 * looted chest across the arena, so this helper builds the packet itself and
 * targets the players explicitly, the way the reference implementation does.
 * <p>
 * Everything is resolved reflectively against {@code v1_8_R3} and the whole
 * class disables itself on any other server version, so no NMS module has to
 * be touched and nothing here is ever executed on 1.9+.
 */
final class ChestLidPacket {

    /** The block action id that carries the chest viewer count on 1.8. */
    private static final int CHEST_LID_ACTION = 1;

    private static boolean resolved;
    private static boolean supported;

    private static Constructor<?> blockPositionCtor;
    private static Constructor<?> packetCtor;
    private static Class<?> packetInterface;
    private static Object chestBlock;
    private static Object trappedChestBlock;
    private static Method getHandle;
    private static Field playerConnection;
    private static Method sendPacket;

    private ChestLidPacket() {
    }

    /** True when this server is a 1.8 server whose NMS layout we could read. */
    static boolean isSupported() {
        resolve();
        return supported;
    }

    /**
     * Sends the lid state of one chest to the given players.
     *
     * @param open true opens the lid, false lets it close again
     * @return false when the packet could not be built or sent
     */
    static boolean send(Collection<? extends Player> players, Block block, boolean open) {
        if (players.isEmpty()) return false;
        if (!isSupported()) return false;

        Object nmsBlock = block.getType() == Material.TRAPPED_CHEST ? trappedChestBlock : chestBlock;
        if (nmsBlock == null) return false;

        try {
            Object blockPosition = blockPositionCtor.newInstance(block.getX(), block.getY(), block.getZ());
            // 1.8 signature: PacketPlayOutBlockAction(BlockPosition, Block, int, int)
            // The last argument is the viewer count that drives the lid animation.
            Object packet = packetCtor.newInstance(blockPosition, nmsBlock, CHEST_LID_ACTION, open ? 1 : 0);

            for (Player player : players) {
                if (player == null || !player.isOnline()) continue;
                Object handle = getHandle.invoke(player);
                Object connection = playerConnection.get(handle);
                sendPacket.invoke(connection, packet);
            }
            return true;
        } catch (Throwable ex) {
            // A single failure means the layout guess was wrong: stop trying so a
            // broken assumption cannot flood the console once per tick.
            supported = false;
            SkyWarsReloaded.get().getLogger().warning(
                    "Disabling the chest lid effect, the server did not accept the block action packet: " + ex);
            return false;
        }
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;

        if (SkyWarsReloaded.getNMS().getVersion() != 8) return;

        try {
            String nms = "net.minecraft.server.v1_8_R3.";
            Class<?> packetClass = Class.forName(nms + "PacketPlayOutBlockAction");
            Class<?> blockPositionClass = Class.forName(nms + "BlockPosition");
            Class<?> blockClass = Class.forName(nms + "Block");
            Class<?> blocksClass = Class.forName(nms + "Blocks");
            packetInterface = Class.forName(nms + "Packet");

            blockPositionCtor = blockPositionClass.getConstructor(int.class, int.class, int.class);
            packetCtor = packetClass.getConstructor(blockPositionClass, blockClass, int.class, int.class);

            // Blocks.CHEST / Blocks.TRAPPED_CHEST are the registered singletons.
            chestBlock = blocksClass.getField("CHEST").get(null);
            trappedChestBlock = blocksClass.getField("TRAPPED_CHEST").get(null);

            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer");
            getHandle = craftPlayer.getMethod("getHandle");
            playerConnection = getHandle.getReturnType().getField("playerConnection");
            sendPacket = playerConnection.getType().getMethod("sendPacket", packetInterface);

            supported = true;
        } catch (Throwable ex) {
            SkyWarsReloaded.get().getLogger().warning(
                    "The chest lid effect is unavailable on this server: " + ex);
        }
    }
}
