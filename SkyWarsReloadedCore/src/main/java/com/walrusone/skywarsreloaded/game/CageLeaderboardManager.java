package com.walrusone.skywarsreloaded.game;

import com.walrusone.skywarsreloaded.SkyWarsReloaded;
import com.walrusone.skywarsreloaded.enums.LeaderType;
import com.walrusone.skywarsreloaded.enums.MatchState;
import com.walrusone.skywarsreloaded.managers.Leaderboard;
import com.walrusone.skywarsreloaded.utilities.Messaging;
import com.walrusone.skywarsreloaded.utilities.placeholders.LeaderPrefixResolver;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shows four floating leaderboards around a player while they wait inside their
 * start cage: WINS in front, KILLS behind, DEATHS to the left and XP to the right,
 * all laid out relative to the direction the player faces on arrival.
 * <p>
 * The boards belong to the cage phase only. They appear when the player is
 * teleported to their spawn and are dropped when the match starts, when the
 * player leaves the arena, or when the arena is refreshed between matches.
 */
public class CageLeaderboardManager {

    /** Which stat goes in which direction, in the order the four sides are built. */
    private static final LeaderType[] SIDES = {
            LeaderType.WINS,    // in front of the player
            LeaderType.KILLS,   // behind
            LeaderType.DEATHS,  // to the left
            LeaderType.XP       // to the right
    };

    /** How often the board text is refreshed while players wait, in ticks. */
    private static final long REFRESH_TICKS = 20L;

    /** The layout entry that expands into the ranked rows. */
    private static final String RANKS_TOKEN = "{ranks}";

    private final GameMap gMap;

    /** Every board currently shown, so a leave can drop exactly that player's. */
    private final Map<UUID, List<Board>> boards = new HashMap<>();

    /** Repaints the text while boards exist; stops as soon as they are all gone. */
    private BukkitRunnable refreshTask;

    CageLeaderboardManager(GameMap gMap) {
        this.gMap = gMap;
    }

    // ------------------------------------------------------------------- lifecycle

    /**
     * Builds the four boards around the player's current position, using their
     * current facing as "front". Any boards already shown for them are dropped
     * first, so calling this twice cannot leave duplicates floating.
     *
     * @param player the caged player, may be null
     */
    public void show(Player player) {
        if (player == null || !player.isOnline()) return;
        if (!SkyWarsReloaded.getCfg().isCageLeaderboardsEnabled()) {
            debug("disabled in the config");
            return;
        }
        // Only meaningful while the player is standing in a cage.
        if (gMap.getMatchState() != MatchState.WAITINGSTART) {
            debug("match state is " + gMap.getMatchState() + ", not WAITINGSTART");
            return;
        }

        World world = gMap.getCurrentWorld();
        if (world == null) {
            debug("the arena world is not loaded");
            return;
        }
        if (player.getWorld() != world) {
            debug(player.getName() + " is in world " + player.getWorld().getName()
                    + " instead of the arena world " + world.getName());
            return;
        }

        hide(player.getUniqueId());

        Location center = player.getLocation();
        Vector forward = horizontalFacing(center);
        // In Minecraft a facing of (x, z) has its left at (z, -x): yaw 0 faces south
        // (+Z) and the player's left hand then points east (+X).
        Vector left = new Vector(forward.getZ(), 0.0D, -forward.getX());

        Vector[] directions = {
                forward,
                forward.clone().multiply(-1.0D),
                left,
                left.clone().multiply(-1.0D)
        };

        double distance = SkyWarsReloaded.getCfg().getCageLeaderboardDistance();
        List<Board> created = new ArrayList<>(SIDES.length);

        for (int i = 0; i < SIDES.length; i++) {
            Location anchor = center.clone().add(directions[i].clone().multiply(distance));
            Board board = buildBoard(world, anchor, SIDES[i]);
            if (board != null) created.add(board);
        }

        if (created.isEmpty()) {
            debug("not a single board could be spawned for " + player.getName());
            return;
        }
        boards.put(player.getUniqueId(), created);
        startRefreshTask();
        debug("created " + created.size() + " boards for " + player.getName());
    }

    /** Explains a skipped or partial creation, but only when debugging is on. */
    private void debug(String reason) {
        if (!SkyWarsReloaded.getCfg().debugEnabled()) return;
        SkyWarsReloaded.get().getLogger().info(
                "CageLeaderboardManager[" + gMap.getName() + "]: " + reason);
    }

    /**
     * Drops the boards of a single player, e.g. when they leave the arena.
     *
     * @param uuid the player whose boards should go, may be null
     */
    public void hide(UUID uuid) {
        if (uuid == null) return;
        List<Board> playerBoards = boards.remove(uuid);
        if (playerBoards == null) return;
        for (Board board : playerBoards) {
            board.destroy();
        }
    }

    /** Drops every board in this arena: the match started, or the arena was reset. */
    public void clearAll() {
        for (List<Board> playerBoards : new ArrayList<>(boards.values())) {
            for (Board board : playerBoards) {
                board.destroy();
            }
        }
        boards.clear();
        stopRefreshTask();
    }

    // ------------------------------------------------------------------- refreshing

    /**
     * Leaderboard data is loaded asynchronously and refreshed on its own schedule,
     * so a player who reaches their cage first would otherwise stare at a board
     * that says "no data" for the whole countdown. Repainting once a second keeps
     * the text current without ever respawning the stands.
     */
    private void startRefreshTask() {
        if (refreshTask != null) return;

        refreshTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (boards.isEmpty() || gMap.getMatchState() != MatchState.WAITINGSTART) {
                    refreshTask = null;
                    cancel();
                    return;
                }
                for (List<Board> playerBoards : boards.values()) {
                    for (Board board : playerBoards) {
                        board.repaint();
                    }
                }
            }
        };
        refreshTask.runTaskTimer(SkyWarsReloaded.get(), REFRESH_TICKS, REFRESH_TICKS);
    }

    private void stopRefreshTask() {
        if (refreshTask == null) return;
        refreshTask.cancel();
        refreshTask = null;
    }

    // -------------------------------------------------------------------- internals

    /**
     * Renders one board top-down at the anchor. The stands are ordinary name-tag
     * holders, so the text always turns to face whoever is looking at it and the
     * board needs no rotation of its own.
     *
     * @return the finished board, or null when not a single line could be spawned
     */
    private Board buildBoard(World world, Location anchor, LeaderType type) {
        List<String> lines = renderLines(type);
        // Leaderboard data arrives asynchronously, so the first render is usually
        // the short "no data" board. Spawning room for the full board right away
        // means the ranks can appear later without respawning any stands.
        int capacity = Math.max(boardCapacity(), lines.size());
        List<ArmorStand> stands = new ArrayList<>(capacity);

        double spacing = SkyWarsReloaded.getCfg().getCageLeaderboardLineSpacing();
        double top = anchor.getY() + SkyWarsReloaded.getCfg().getCageLeaderboardHeight();

        for (int i = 0; i < capacity; i++) {
            Location lineLocation = new Location(world, anchor.getX(), top - (i * spacing), anchor.getZ());
            ArmorStand stand = spawnStand(lineLocation, i < lines.size() ? lines.get(i) : "");
            if (stand != null) stands.add(stand);
        }

        return stands.isEmpty() ? null : new Board(type, stands);
    }

    /**
     * How many lines a fully populated board needs: every fixed layout row plus
     * the ranked rows the token expands into.
     */
    private int boardCapacity() {
        int fixed = 0;
        for (String entry : layout()) {
            if (!RANKS_TOKEN.equals(entry.trim())) fixed++;
        }
        // At least one rank row, so a board is never all spacers.
        return fixed + Math.max(SkyWarsReloaded.getCfg().getCageLeaderboardSize(), 1);
    }

    /**
     * The finished text of one board. The row order comes from the
     * {@code cageboard.layout} list in messages.yml, where {@value #RANKS_TOKEN}
     * stands for the ranked rows and an empty entry is a blank spacer line. An
     * absent or empty layout falls back to a title directly above the ranks.
     */
    private List<String> renderLines(LeaderType type) {
        List<String> layout = layout();
        List<String> lines = new ArrayList<>(layout.size() + 1);

        for (String entry : layout) {
            if (RANKS_TOKEN.equals(entry.trim())) {
                lines.addAll(renderRanks(type));
            } else {
                // A blank entry stays blank: it is a spacer, not a template.
                lines.add(entry.isEmpty() ? "" : format(entry, type, null, 0, null));
            }
        }
        return lines;
    }

    /**
     * The board layout, read fresh so an edited messages.yml applies on the next
     * repaint. A layout without {@value #RANKS_TOKEN} would render a board with no
     * players at all, so that case is treated as unconfigured.
     */
    private List<String> layout() {
        List<String> layout = SkyWarsReloaded.getMessaging().getFile().getStringList("cageboard.layout");

        boolean hasRanks = false;
        if (layout != null) {
            for (String entry : layout) {
                if (entry != null && RANKS_TOKEN.equals(entry.trim())) {
                    hasRanks = true;
                    break;
                }
            }
        }
        if (!hasRanks) {
            return Arrays.asList("cageboard.title", RANKS_TOKEN);
        }

        // getStringList drops nulls, but an entry could still be null in theory.
        List<String> safe = new ArrayList<>(layout.size());
        for (String entry : layout) {
            safe.add(entry == null ? "" : entry);
        }
        return safe;
    }

    /** The ranked rows on their own, or the single "no data" row. */
    private List<String> renderRanks(LeaderType type) {
        Leaderboard lbManager = SkyWarsReloaded.getLB();
        List<Leaderboard.LeaderData> topList = lbManager == null ? null : lbManager.getTopList(type);

        if (topList == null || topList.isEmpty()) {
            return Collections.singletonList(format("cageboard.no-data", type, null, 0, null));
        }

        int size = Math.min(SkyWarsReloaded.getCfg().getCageLeaderboardSize(), topList.size());
        List<String> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rows.add(format("cageboard.line", type, topList.get(i), i + 1, rankSymbol(i + 1)));
        }
        return rows;
    }

    /**
     * Fills one message template. The rank prefix is resolved through the same
     * helper the hologram leaderboards use, so a player's LuckPerms title shows
     * up identically in both places.
     */
    private String format(String key, LeaderType type, Leaderboard.LeaderData data, int rank, String symbol) {
        Messaging.MessageFormatter formatter = new Messaging.MessageFormatter()
                .setVariable("type", typeName(type))
                .setVariable("rank", rank <= 0 ? "" : String.valueOf(rank))
                .setVariable("symbol", symbol == null ? "" : symbol);

        if (data != null) {
            formatter.setVariable("player", data.getName())
                    .setVariable("value", String.valueOf(valueOf(type, data)))
                    .setVariable("prefix", LeaderPrefixResolver.getPrefix(data.getUUID()))
                    .setVariable("namecolor", LeaderPrefixResolver.getNameColor(data.getUUID()));
        }
        return formatter.format(key);
    }

    /** The display name of a stat, taken from messages.yml so it can be translated. */
    private String typeName(LeaderType type) {
        return new Messaging.MessageFormatter().format("cageboard.types." + type.toString().toLowerCase());
    }

    private int valueOf(LeaderType type, Leaderboard.LeaderData data) {
        switch (type) {
            case WINS:
                return data.getWins();
            case LOSSES:
                return data.getLoses();
            case KILLS:
                return data.getKills();
            case DEATHS:
                return data.getDeaths();
            case XP:
                return data.getXp();
            default:
                return 0;
        }
    }

    /** Circled digits for the top ten, matching the hologram leaderboard style. */
    private String rankSymbol(int rank) {
        switch (rank) {
            case 1: return "\u2776";
            case 2: return "\u2777";
            case 3: return "\u2778";
            case 4: return "\u2779";
            case 5: return "\u277A";
            case 6: return "\u277B";
            case 7: return "\u277C";
            case 8: return "\u277D";
            case 9: return "\u277E";
            case 10: return "\u277F";
            default: return String.valueOf(rank);
        }
    }

    /**
     * The player's facing flattened onto the ground. Looking straight up or down
     * leaves no horizontal component, so that case falls back to due south, which
     * is what an unrotated teleport produces anyway.
     */
    private Vector horizontalFacing(Location location) {
        Vector direction = location.getDirection();
        direction.setY(0.0D);
        if (direction.lengthSquared() < 1.0E-6D) {
            return new Vector(0.0D, 0.0D, 1.0D);
        }
        return direction.normalize();
    }

    private ArmorStand spawnStand(Location location, String text) {
        try {
            ArmorStand stand = location.getWorld().spawn(location, ArmorStand.class);
            stand.setVisible(false);
            stand.setSmall(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setCanPickupItems(false);
            stand.setRemoveWhenFarAway(false);
            applyText(stand, text);
            return stand;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            SkyWarsReloaded.get().getLogger().warning(
                    "Could not create a cage leaderboard line in " + gMap.getName() + ": " + ex.getMessage());
            return null;
        }
    }

    private static void applyText(ArmorStand stand, String text) {
        String colored = ChatColor.translateAlternateColorCodes('&', text);
        stand.setCustomName(colored);
        stand.setCustomNameVisible(!ChatColor.stripColor(colored).trim().isEmpty());
    }

    /** One of the four boards: its stat and the stands making up its lines. */
    private final class Board {
        private final LeaderType type;
        private final List<ArmorStand> stands;

        private Board(LeaderType type, List<ArmorStand> stands) {
            this.type = type;
            this.stands = stands;
        }

        /**
         * Rewrites the text of the existing lines. Only as many lines as were
         * originally spawned can be filled, so the board never grows or shrinks
         * while a player is looking at it.
         */
        private void repaint() {
            List<String> lines = renderLines(type);

            for (int i = 0; i < stands.size(); i++) {
                ArmorStand stand = stands.get(i);
                if (stand == null || stand.isDead()) continue;
                // Past the end of the new text the remaining lines are blanked
                // instead of being removed, which keeps the stand list stable.
                applyText(stand, i < lines.size() ? lines.get(i) : "");
            }
        }

        private void destroy() {
            for (ArmorStand stand : stands) {
                despawn(stand);
            }
            stands.clear();
        }

        /**
         * Removes one line. The arena world is deleted and re-created between
         * matches, so a reference can already point into an unloaded world.
         */
        private void despawn(ArmorStand stand) {
            if (stand == null) return;
            try {
                if (!stand.isDead()) stand.remove();
            } catch (IllegalStateException ignored) {
                // The world holding this entity is gone; nothing left to clean up.
            }
        }
    }
}
