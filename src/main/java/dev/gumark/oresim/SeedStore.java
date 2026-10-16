package dev.gumark.oresim;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * Holds the world seed used by the ore simulation.
 *
 * <p>On singleplayer worlds the seed is read directly from the integrated server.
 * On multiplayer servers it must be provided by the player with
 * <code>/setoresimseed &lt;seed&gt;</code>; the value is remembered per server
 * address (see {@link ServerSeedMemory}) so rejoining does not ask for it again.
 */
public final class SeedStore {
    /**
     * Seed explicitly set this session. It sticks until the current server
     * disconnects, at which point it is persisted (once) by
     * {@link #rememberCurrentServerSeed} and dropped so it cannot leak into
     * the next server the player joins.
     */
    private static Long manualSeed = null;
    /** Server address {@link #manualSeed} belongs to; used for persistence and for reuse across reconnects. */
    private static String manualSeedServer = null;

    private SeedStore() {
    }

    /** Sets the seed manually (from {@code /setoresimseed}); only meaningful on multiplayer. */
    public static void setSeed(long seed) {
        manualSeed = seed;
        manualSeedServer = currentServerAddress();
    }

    /** Clears the manually set seed. */
    public static void clearSeed() {
        manualSeed = null;
        manualSeedServer = null;
    }

    /** The seed to simulate with, or {@code null} if none is known. */
    public static Long getSeed() {
        if (manualSeed != null) return manualSeed;
        MinecraftServer integrated = Minecraft.getInstance().getSingleplayerServer();
        if (integrated != null) return integrated.overworld().getSeed();
        String address = currentServerAddress();
        if (address == null) return null;
        return ServerSeedMemory.get(address);
    }

    /** Persists {@link #manualSeed} for its server, then discards it for the next connection. */
    public static void rememberCurrentServerSeed() {
        if (manualSeed != null && manualSeedServer != null) {
            ServerSeedMemory.remember(manualSeedServer, manualSeed);
        }
        clearSeed();
    }

    /** The seed remembered from a previous visit to the currently joined server, or {@code null}. */
    public static Long restoredSeedOrNull() {
        String address = currentServerAddress();
        if (address == null) return null;
        return ServerSeedMemory.get(address);
    }

    /** Resolves the joined server's address, in a singleplayer-safe way; {@code null} outside servers. */
    public static String currentServerAddress() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null && !mc.getCurrentServer().ip.isBlank()) {
            return mc.getCurrentServer().ip;
        }
        return null;
    }

    /**
     * Parses a seed the same way vanilla does: numeric text is a literal seed,
     * anything else is hashed with {@link String#hashCode()}.
     */
    public static long parseSeed(String input) {
        String stripped = input.strip();
        try {
            return Long.parseLong(stripped);
        } catch (NumberFormatException e) {
            return stripped.hashCode();
        }
    }
}
