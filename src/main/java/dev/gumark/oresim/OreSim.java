package dev.gumark.oresim;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;

/**
 * Runs the ore simulation ({@link OreSimulator}) for the chunks around the
 * player and stores the resulting ore positions so they can be rendered as
 * boxes through walls.
 *
 * <p>Derived from the OreSim module of
 * <a href="https://github.com/AntiCope/meteor-rejects">meteor-rejects</a>, ported
 * to standalone Fabric on Minecraft 26.3.
 */
public class OreSim {
    /** How many chunks around the player are simulated and rendered. */
    public static final int CHUNK_RANGE = 5;

    private final Minecraft mc = Minecraft.getInstance();
    private final Map<Long, RenderChunk> chunkRenderers = new ConcurrentHashMap<>();
    /** Pre-built stroke style per {@link OreType#ordinal()}; render never allocates a style. */
    private static final GizmoStyle[] STYLES = new GizmoStyle[OreType.values().length];

    static {
        for (OreType type : OreType.values()) {
            STYLES[type.ordinal()] = GizmoStyle.stroke(type.color);
        }
    }

    /** Simulation for the current world and dimension; {@code null} while inactive. */
    private OreSimulator simulator;
    /** The client level as the simulation reads it. */
    private OreSimulator.WorldView clientView;
    private boolean active;
    private int lastPlayerChunkX, lastPlayerChunkZ;
    /** Mined-out predicted ores of the current world (live set from {@link MinedOres}). */
    private LongOpenHashSet mined = new LongOpenHashSet();
    /**
     * Predictions shown wrong once exposed (live set from {@link MinedOres}).
     * Kept apart from {@link #mined}: these are only hidden, the simulation
     * still reads whatever block is really there.
     */
    private LongOpenHashSet disproven = new LongOpenHashSet();
    /** Exposed predictions that don't show their ore yet, with the tick that was first seen. */
    private final Long2IntOpenHashMap wrongSince = new Long2IntOpenHashMap();
    private int ticks;
    private int resolveScanTicks;
    /** How often simulated positions are checked for having been mined or disproven. */
    private static final int RESOLVE_SCAN_TICKS = 20;
    /**
     * How long an exposed prediction may look wrong before it is written off.
     * Anti-xray reveals a block's real state a moment after it gets exposed.
     */
    private static final int REVEAL_GRACE_TICKS = 40;

    private ClientLevel lastLevel;
    private LocalPlayer lastPlayer;

    public boolean isActive() {
        return active;
    }

    /** Receives one simulated ore position, packed with {@link BlockPos#asLong()}. */
    @FunctionalInterface
    public interface OreConsumer {
        void accept(OreType type, long packedPos);
    }

    /**
     * Visits every simulated ore of the given types in the rendered range,
     * whether or not the overlay shows them (used by the Baritone miner).
     */
    public void forEachOre(Set<OreType> wanted, OreConsumer consumer) {
        if (!active || mc.player == null || simulator == null) return;
        OreType[] types = OreType.values();
        ChunkPos center = mc.player.chunkPosition();
        forEachChunkInRange(center.x(), center.z(), (x, z) -> {
            RenderChunk chunk = chunkRenderers.get(ChunkPos.pack(x, z));
            if (chunk == null || chunk.boxCount == 0) return;
            for (int typeIndex = 0; typeIndex < chunk.positionsByType.length; typeIndex++) {
                long[] positions = chunk.positionsByType[typeIndex];
                if (positions == null || !wanted.contains(types[typeIndex])) continue;
                for (long packed : positions) {
                    consumer.accept(types[typeIndex], packed);
                }
            }
        });
    }

    public void toggle() {
        setActive(!active);
    }

    public void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;

        if (active) {
            if (SeedStore.getSeed() == null) {
                this.active = false;
                if (mc.player != null) {
                    mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "OreSim: no seed set. Use /setoresimseed <seed> first."));
                }
                return;
            }
            reload();
        } else {
            clear();
        }
    }

    /** Clears all simulated data. */
    public void clear() {
        chunkRenderers.clear();
        simulator = null;
        clientView = null;
    }

    /** Called when the seed changes while the simulation may be running. */
    public void onSeedChanged() {
        if (active) reload();
    }

    /** Detects world changes (dimension switch, respawn) and reloads the simulation. */
    public void tick() {
        if (mc.level != lastLevel || mc.player != lastPlayer) {
            lastLevel = mc.level;
            lastPlayer = mc.player;
            if (active) reload();
            return;
        }
        if (!active || simulator == null || mc.player == null) return;
        ticks++;
        if (++resolveScanTicks >= RESOLVE_SCAN_TICKS) {
            resolveScanTicks = 0;
            detectResolved();
        }
        MinedOres.saveIfDirty(false);
        ChunkPos pos = mc.player.chunkPosition();
        if (pos.x() != lastPlayerChunkX || pos.z() != lastPlayerChunkZ) {
            lastPlayerChunkX = pos.x();
            lastPlayerChunkZ = pos.z();
            sweepLoadedChunks();
        }
    }

    /** Called for every chunk that finishes loading on the client. */
    public void onChunkLoad(LevelChunk chunk) {
        if (!active || simulator == null || mc.player == null) return;
        // Only chunks we actually render are simulated, so chunks streaming in
        // at the edge of the view distance cost nothing.
        ChunkPos center = mc.player.chunkPosition();
        ChunkPos pos = chunk.getPos();
        // A chunk is only simulated once its neighbours are loaded (veins
        // reach into them), so this chunk may complete one of theirs.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = pos.x() + dx;
                int z = pos.z() + dz;
                if (Math.abs(x - center.x()) + Math.abs(z - center.z()) > CHUNK_RANGE) continue;
                LevelChunk candidate = mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (candidate != null) doMathOnChunk(candidate);
            }
        }
    }

    /** Called when a chunk unloads; drops its cached simulation. */
    public void onChunkUnload(LevelChunk chunk) {
        chunkRenderers.remove(chunk.getPos().pack());
    }

    private void reload() {
        clear();
        Long seed = SeedStore.getSeed();
        ClientLevel world = mc.level;
        if (seed == null || world == null) return;

        String worldKey = MinedOres.worldKey(seed, world.dimension().identifier());
        mined = MinedOres.forWorld(worldKey);
        disproven = MinedOres.forWorld(worldKey + "|disproven");
        wrongSince.clear();
        var heightContext = Ore.clientHeightContext(world.getMinY(), world.dimensionType().logicalHeight());
        simulator = new OreSimulator(seed, Ore.getRegistry(world.dimension(), heightContext), mined::contains,
            OreSimulator.Options.CURRENT);
        clientView = new OreSimulator.WorldView() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                return world.getBlockState(pos);
            }

            @Override
            public int getHeight(Heightmap.Types type, int x, int z) {
                return world.getHeight(type, x, z);
            }

            @Override
            public boolean isOutsideBuildHeight(int y) {
                return world.isOutsideBuildHeight(y);
            }

            @Override
            public Holder<Biome> getBiome(BlockPos pos) {
                // Block position, with biome blending: the same lookup as vanilla's BiomeFilter.
                return world.getBiome(pos);
            }
        };

        if (mc.player != null) {
            ChunkPos center = mc.player.chunkPosition();
            lastPlayerChunkX = center.x();
            lastPlayerChunkZ = center.z();
        }
        sweepLoadedChunks();
    }

    /**
     * Forgets the mined and disproven positions of the current world and
     * re-simulates; returns how many were forgotten, or -1 when the overlay is off.
     */
    public int clearMined() {
        if (!active) return -1;
        int count = mined.size() + disproven.size();
        mined.clear();
        disproven.clear();
        MinedOres.markDirty();
        reload();
        return count;
    }

    /** Whether a predicted position was mined out or disproven, i.e. is no longer shown. */
    public boolean isResolved(long packedPos) {
        return mined.contains(packedPos) || disproven.contains(packedPos);
    }

    /**
     * Stops showing predictions that are settled:
     * <ul>
     *   <li>gone (air or fluid): mined. A simulated position always held a
     *       solid target block when simulated, so it only turns into air by
     *       being dug out.</li>
     *   <li>exposed but not the predicted ore: disproven. Anti-xray never
     *       disguises exposed blocks, so what the client sees there is real.</li>
     * </ul>
     */
    private void detectResolved() {
        ClientLevel world = mc.level;
        if (world == null || mc.player == null) return;
        boolean[] changed = {false};
        // Only the rendered range: nothing outside it is drawn or mined anyway.
        ChunkPos center = mc.player.chunkPosition();
        forEachChunkInRange(center.x(), center.z(), (x, z) -> {
            long key = ChunkPos.pack(x, z);
            RenderChunk chunk = chunkRenderers.get(key);
            if (chunk == null || chunk.boxCount == 0) return;
            RenderChunk pruned = chunk.prune((type, packed) -> {
                BlockPos pos = BlockPos.of(packed);
                BlockState state = world.getBlockState(pos);
                if (state.isAir() || !state.getFluidState().isEmpty()) {
                    mined.add(packed);
                    return true;
                }
                if (type.isOre(state.getBlock()) || !isExposed(world, pos)) {
                    wrongSince.remove(packed);
                    return false;
                }
                if (!wrongSince.containsKey(packed)) wrongSince.put(packed, ticks);
                if (ticks - wrongSince.get(packed) < REVEAL_GRACE_TICKS) return false;
                wrongSince.remove(packed);
                disproven.add(packed);
                return true;
            });
            if (pruned != chunk) {
                chunkRenderers.put(key, pruned);
                changed[0] = true;
            }
        });
        if (changed[0]) MinedOres.markDirty();
    }

    private static boolean isExposed(ClientLevel world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (!world.getBlockState(pos.relative(direction)).canOcclude()) return true;
        }
        return false;
    }

    /** Simulates every loaded chunk in range that has not been simulated yet. */
    private void sweepLoadedChunks() {
        if (mc.player == null || mc.level == null) return;
        ChunkPos center = mc.player.chunkPosition();
        forEachChunkInRange(center.x(), center.z(), (cx, cz) -> {
            LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk != null) doMathOnChunk(chunk);
        });
    }

    /** The diamond of chunks the overlay covers: {@code |dx| + |dz| <= CHUNK_RANGE}. */
    private static void forEachChunkInRange(int centerX, int centerZ, ChunkConsumer consumer) {
        for (int dz = -CHUNK_RANGE; dz <= CHUNK_RANGE; dz++) {
            int width = CHUNK_RANGE - Math.abs(dz);
            for (int dx = -width; dx <= width; dx++) {
                consumer.accept(centerX + dx, centerZ + dz);
            }
        }
    }

    @FunctionalInterface
    private interface ChunkConsumer {
        void accept(int x, int z);
    }

    /**
     * Everything one rendered chunk needs, pre-computed at simulation time so
     * the per-frame cost is a flat array walk with no map lookups, no toggle
     * checks outside the type loop, and no style allocation.
     */
    private static final class RenderChunk {
        /** Per {@link OreType#ordinal()}: packed positions, {@code null} when the chunk has none of that type. */
        private final long[][] positionsByType;
        /** Total number of boxes this chunk renders, for the cheap early-out. */
        private final int boxCount;

        RenderChunk(Map<Ore, Set<BlockPos>> ores, LongPredicate hidden) {
            // Deduplicate across placements of the same ore type (e.g. the
            // three iron placements) so overlapping veins don't draw twice.
            HashMap<OreType, PackedPositions> builders = new HashMap<>();
            for (Map.Entry<Ore, Set<BlockPos>> entry : ores.entrySet()) {
                PackedPositions list = builders.computeIfAbsent(entry.getKey().type, t -> new PackedPositions());
                for (BlockPos pos : entry.getValue()) {
                    long packed = pos.asLong();
                    if (!hidden.test(packed)) list.add(packed);
                }
            }
            long[][] byType = new long[STYLES.length][];
            int boxes = 0;
            for (Map.Entry<OreType, PackedPositions> entry : builders.entrySet()) {
                long[] positions = entry.getValue().toArray();
                byType[entry.getKey().ordinal()] = positions;
                boxes += positions.length;
            }
            this.positionsByType = byType;
            this.boxCount = boxes;
        }

        private RenderChunk(long[][] positionsByType, int boxCount) {
            this.positionsByType = positionsByType;
            this.boxCount = boxCount;
        }

        /** This chunk without the positions {@code gone} accepts; itself when nothing is removed. */
        RenderChunk prune(PositionTest gone) {
            OreType[] types = OreType.values();
            long[][] byType = null;
            int boxes = boxCount;
            for (int typeIndex = 0; typeIndex < positionsByType.length; typeIndex++) {
                long[] positions = positionsByType[typeIndex];
                if (positions == null) continue;
                OreType type = types[typeIndex];
                long[] kept = Arrays.stream(positions).filter(packed -> !gone.test(type, packed)).toArray();
                if (kept.length == positions.length) continue;
                if (byType == null) byType = positionsByType.clone();
                byType[typeIndex] = kept;
                boxes -= positions.length - kept.length;
            }
            return byType == null ? this : new RenderChunk(byType, boxes);
        }
    }

    @FunctionalInterface
    private interface PositionTest {
        boolean test(OreType type, long packedPos);
    }

    /** Tiny growable long list with a linear-scan "already present" check. */
    private static final class PackedPositions {
        private long[] values = new long[32];
        private int size;

        void add(long value) {
            for (int i = 0; i < size; i++) {
                if (values[i] == value) return;
            }
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        long[] toArray() {
            return size == values.length ? values : Arrays.copyOf(values, size);
        }
    }

    private void doMathOnChunk(ChunkAccess chunk) {
        var chunkPos = chunk.getPos();
        long chunkKey = chunkPos.pack();
        ClientLevel world = mc.level;
        if (world == null || simulator == null || chunkRenderers.containsKey(chunkKey)) return;
        // Veins reach into the neighbouring chunks; reading those while they
        // aren't loaded (as air) would derail the simulation. onChunkLoad
        // retries once they arrive.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (world.getChunkSource().getChunk(chunkPos.x() + dx, chunkPos.z() + dz, ChunkStatus.FULL, false) == null) {
                    return;
                }
            }
        }
        // Pre-bucket the simulated positions by ore type once, so each frame
        // only walks flat position arrays for the enabled types.
        chunkRenderers.put(chunkKey, new RenderChunk(simulator.simulateChunk(chunkPos, clientView), this::isResolved));
    }

    // ====================================
    // Rendering
    // ====================================

    /** Emits a gizmo box for every simulated ore; called right before vanilla collects its gizmos. */
    public void render() {
        if (!active || mc.player == null || mc.level == null || simulator == null) return;

        // One toggle snapshot per frame instead of an EnumSet lookup per
        // ore group; toggles change via the GUI, never between frames.
        boolean[] enabled = OverlayToggles.snapshot();
        ChunkPos center = mc.player.chunkPosition();
        forEachChunkInRange(center.x(), center.z(), (x, z) -> renderChunk(x, z, enabled));
    }

    private void renderChunk(int x, int z, boolean[] enabled) {
        RenderChunk chunk = chunkRenderers.get(ChunkPos.pack(x, z));
        if (chunk == null || chunk.boxCount == 0) return;

        long[][] positionsByType = chunk.positionsByType;
        for (int typeIndex = 0; typeIndex < positionsByType.length; typeIndex++) {
            long[] positions = positionsByType[typeIndex];
            if (positions == null || !enabled[typeIndex]) continue;
            GizmoStyle style = STYLES[typeIndex];
            // Purely seed-driven rendering: every simulated position is drawn
            // whether or not the player can already see it, and the client
            // world is never consulted — so anti-xray fake blocks can neither
            // suppress nor fake a box.
            for (long packed : positions) {
                Gizmos.cuboid(BlockPos.of(packed), style).setAlwaysOnTop();
            }
        }
    }
}
