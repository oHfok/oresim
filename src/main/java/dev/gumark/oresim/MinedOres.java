package dev.gumark.oresim;

import com.google.gson.Gson;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers predicted ore positions that have been mined out, so the overlay
 * and the miner stop showing/targeting them, across sessions too.
 *
 * <p>Positions are grouped per world ({@link #worldKey}) and stored packed with
 * {@link net.minecraft.core.BlockPos#asLong()} in {@code config/oresim-mined.json}.
 * Like {@link ServerSeedMemory}, it loads lazily and a missing or corrupt file
 * only costs the remembered positions.
 */
public final class MinedOres {
    private static final Path FILE = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("oresim-mined.json");
    private static final Gson GSON = new Gson();
    /** Minimum time between two saves while mining; the rest is flushed on disconnect. */
    private static final long SAVE_INTERVAL_MS = 10_000;

    private static final Map<String, LongOpenHashSet> worlds = new HashMap<>();
    private static boolean loaded;
    private static boolean dirty;
    private static long lastSave;

    private MinedOres() {
    }

    /**
     * Identifies one dimension of one world: the server address (or
     * {@code singleplayer}), the seed and the dimension id.
     */
    public static String worldKey(long seed, Identifier dimension) {
        String server = SeedStore.currentServerAddress();
        return (server != null ? server : "singleplayer") + "|" + seed + "|" + dimension;
    }

    /** The live set of mined positions for a world; changes must be followed by {@link #markDirty}. */
    public static LongOpenHashSet forWorld(String key) {
        load();
        return worlds.computeIfAbsent(key, k -> new LongOpenHashSet());
    }

    public static void markDirty() {
        dirty = true;
    }

    /** Saves pending changes, at most once per {@link #SAVE_INTERVAL_MS} unless forced. */
    public static void saveIfDirty(boolean force) {
        if (!dirty) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastSave < SAVE_INTERVAL_MS) return;
        lastSave = now;
        dirty = false;
        save();
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(FILE)) return;
        try {
            MinedOresFile parsed = GSON.fromJson(Files.readString(FILE), MinedOresFile.class);
            if (parsed != null && parsed.worlds != null) {
                parsed.worlds.forEach((key, positions) -> worlds.put(key, new LongOpenHashSet(positions)));
            }
        } catch (Exception e) {
            // Start empty; the next save rewrites the file.
        }
    }

    private static void save() {
        MinedOresFile file = new MinedOresFile();
        worlds.forEach((key, positions) -> {
            if (!positions.isEmpty()) file.worlds.put(key, positions.toLongArray());
        });
        try {
            Path parent = FILE.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(file), StandardCharsets.UTF_8);
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Non-fatal: positions stay remembered for this session.
        }
    }

    /** JSON shape of the file. */
    private static final class MinedOresFile {
        Map<String, long[]> worlds = new LinkedHashMap<>();
    }
}
