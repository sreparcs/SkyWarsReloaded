package com.walrusone.skywarsreloaded.utilities.placeholders;

import com.walrusone.skywarsreloaded.SkyWarsReloaded;
import com.walrusone.skywarsreloaded.utilities.VaultUtils;
import me.clip.placeholderapi.PlaceholderAPI;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves a player's rank title (LuckPerms prefix) for leaderboard displays.
 * <p>
 * Three sources are tried in order, so the same code works whatever the server
 * has installed:
 * <ol>
 *   <li>The LuckPerms API directly, which is exact and needs no other plugin.</li>
 *   <li>PlaceholderAPI's {@code %luckperms_prefix%}.</li>
 *   <li>Vault's chat service, for servers on a different permission plugin.</li>
 * </ol>
 * Results are cached briefly: one leaderboard refresh resolves up to ten players
 * at once, and it runs on the main thread.
 */
public final class LeaderPrefixResolver {

    /** The legacy colour marker, in case a source hands back translated text. */
    private static final char SECTION_SIGN = '\u00A7';

    /** Colour codes a name colour may be taken from. */
    private static final String COLOR_CODES = "0123456789abcdef";

    /** How long a resolved title stays valid, in milliseconds. */
    private static final long CACHE_TTL = 60_000L;

    private static final Map<UUID, CachedPrefix> CACHE = new HashMap<>();

    private LeaderPrefixResolver() {
    }

    /**
     * The player's rank title, ready to be dropped into a hologram or board line.
     * A non-empty title gets a reset code and a space appended so the name that
     * follows does not inherit the rank's colour or bold state.
     *
     * @param uuid the player to look up, may be null
     * @return the title with '&amp;' colour codes, or an empty string when unavailable
     */
    public static String getPrefix(UUID uuid) {
        String raw = getRawPrefix(uuid);
        return raw.isEmpty() ? "" : raw + "&r ";
    }

    /**
     * The rank colour of the player, so their name can be tinted the same way
     * their rank is.
     *
     * @param uuid the player to look up, may be null
     * @return a two character colour code such as "&amp;c", defaulting to white
     */
    public static String getNameColor(UUID uuid) {
        String prefix = getRawPrefix(uuid);
        // Take the FIRST colour code of the title: "&8[&cVIP&8]" should tint the
        // name with the rank's own colour, not with the trailing bracket colour.
        for (int i = 0; i + 1 < prefix.length(); i++) {
            char marker = prefix.charAt(i);
            if (marker != '&' && marker != SECTION_SIGN) continue;
            char code = Character.toLowerCase(prefix.charAt(i + 1));
            // Skip the dark grey that usually only wraps the brackets.
            if (code == '8' || code == '7') continue;
            if (COLOR_CODES.indexOf(code) >= 0) return "&" + code;
        }
        return "&f";
    }

    /** Drops every cached title, e.g. on a plugin reload. */
    public static void clearCache() {
        CACHE.clear();
    }

    // ------------------------------------------------------------------ internals

    /** The title exactly as the permission plugin reports it, never null. */
    private static String getRawPrefix(UUID uuid) {
        if (uuid == null) return "";

        long now = System.currentTimeMillis();
        CachedPrefix cached = CACHE.get(uuid);
        if (cached != null && now - cached.resolvedAt < CACHE_TTL) {
            return cached.prefix;
        }

        String prefix = resolve(uuid);
        CACHE.put(uuid, new CachedPrefix(prefix, now));
        return prefix;
    }

    private static String resolve(UUID uuid) {
        String fromLuckPerms = resolveWithLuckPerms(uuid);
        if (isUsable(fromLuckPerms)) return fromLuckPerms.trim();

        String fromPapi = resolveWithPlaceholderAPI(uuid);
        if (isUsable(fromPapi)) return fromPapi.trim();

        String fromVault = resolveWithVault(uuid);
        if (isUsable(fromVault)) return fromVault.trim();

        return "";
    }

    private static boolean isUsable(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * Reads the title straight from LuckPerms. Only players LuckPerms already has
     * in memory are considered: a leaderboard refresh runs on the main thread and
     * loading an offline user hits storage.
     */
    private static String resolveWithLuckPerms(UUID uuid) {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return null;

        try {
            LuckPerms api = LuckPermsProvider.get();
            User user = api.getUserManager().getUser(uuid);
            if (user == null) return null;
            return user.getCachedData().getMetaData().getPrefix();
        } catch (Throwable t) {
            // NoClassDefFoundError when LuckPerms is absent, IllegalStateException
            // when its API is not registered yet.
            logFailure("LuckPerms", t);
            return null;
        }
    }

    private static String resolveWithPlaceholderAPI(UUID uuid) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return null;

        try {
            Player online = Bukkit.getPlayer(uuid);
            String result;
            if (online != null) {
                result = PlaceholderAPI.setPlaceholders(online, "%luckperms_prefix%");
            } else {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                result = PlaceholderAPI.setPlaceholders(offline, "%luckperms_prefix%");
            }
            // PlaceholderAPI returns the text unchanged when nothing handles it.
            if (result == null || result.contains("%luckperms_prefix%")) return null;
            return result;
        } catch (Throwable t) {
            logFailure("PlaceholderAPI", t);
            return null;
        }
    }

    private static String resolveWithVault(UUID uuid) {
        // Vault only exposes prefixes for online players, and VaultUtils logs an
        // error if Vault itself is missing, so check the plugin before touching it.
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return null;

        try {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) return null;
            Chat chat = VaultUtils.get().getChat();
            if (chat == null) return null;
            return chat.getPlayerPrefix(online);
        } catch (Throwable t) {
            logFailure("Vault", t);
            return null;
        }
    }

    private static void logFailure(String source, Throwable t) {
        if (SkyWarsReloaded.getCfg() != null && SkyWarsReloaded.getCfg().debugEnabled()) {
            SkyWarsReloaded.get().getLogger().warning(
                    "LeaderPrefixResolver: " + source + " lookup failed: " + t.getMessage());
        }
    }

    private static final class CachedPrefix {
        private final String prefix;
        private final long resolvedAt;

        private CachedPrefix(String prefix, long resolvedAt) {
            this.prefix = prefix;
            this.resolvedAt = resolvedAt;
        }
    }
}
