package dev.gumark.oresim;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;

/**
 * Replays vanilla ore decoration for one chunk from the world seed, against
 * the blocks a client can see. Independent of where those blocks come from
 * ({@link WorldView}), so the client overlay and the accuracy tester run the
 * very same simulation.
 *
 * <p>Derived from the OreSim module of
 * <a href="https://github.com/AntiCope/meteor-rejects">meteor-rejects</a>.
 */
public final class OreSimulator {
    /** The world as the simulation reads it. */
    public interface WorldView {
        BlockState getBlockState(BlockPos pos);

        int getHeight(Heightmap.Types type, int x, int z);

        boolean isOutsideBuildHeight(int y);

        /** The biome vanilla's {@code BiomeFilter} would see at a block position. */
        Holder<Biome> getBiome(BlockPos pos);

        /**
         * Estimates the {@code OCEAN_FLOOR_WG} heightmap vanilla checks before
         * placing a vein: the top of the terrain when ores generated. Clients
         * don't receive that heightmap, so it is found by scanning down from
         * the top, skipping water and lava (the heightmap ignores fluids) and
         * what grew after the ores (trees) or froze over (ice).
         */
        default int getOreSurfaceHeight(int x, int z) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
            while (!isOutsideBuildHeight(pos.getY())) {
                BlockState state = getBlockState(pos);
                // What the heightmap's blocksMotion() test is (that method is gone in 26.3).
                boolean blocksMotion = state.isSolid() && !state.is(Blocks.COBWEB) && !state.is(Blocks.BAMBOO_SAPLING);
                if (blocksMotion && state.getFluidState().isEmpty()
                    && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS) && !state.is(Blocks.ICE)) {
                    return pos.getY() + 1;
                }
                pos.move(Direction.DOWN);
            }
            return pos.getY() + 1;
        }
    }

    /**
     * Corrections over the original meteor-rejects simulation; all on in the
     * mod, switchable so the accuracy tester can measure each one.
     *
     * @param trackPlacedOres   an ore placed earlier in the chunk (earlier
     *                          feature or vein) can't be replaced, in vanilla
     *                          feature order
     * @param laterReplacements sculk/moss/dripstone count as the stone they
     *                          replaced after the ores generated
     * @param oreSurfaceHeight  check veins against {@link WorldView#getOreSurfaceHeight}
     *                          instead of the {@code MOTION_BLOCKING} heightmap
     *                          (which counts water and leaves)
     */
    public record Options(boolean trackPlacedOres, boolean laterReplacements, boolean oreSurfaceHeight) {
        public static final Options CURRENT = new Options(true, true, true);
    }

    private final long seed;
    private final Map<ResourceKey<Biome>, List<Ore>> oreConfig;
    /** Every ore of the dimension, in vanilla decoration order (step, then feature index). */
    private final List<Ore> dimensionOres;
    private final LongPredicate minedOre;
    private final Options options;
    /** Ore positions the chunk being simulated has placed so far. */
    private final LongOpenHashSet placedThisChunk = new LongOpenHashSet();
    /** Surface height per column, for the chunk being simulated. */
    private final Long2IntOpenHashMap surfaceHeights = new Long2IntOpenHashMap();
    private WorldView world;

    /**
     * @param minedOre positions known to have held an ore that was mined out
     *                 since; they were valid targets when the world generated
     */
    public OreSimulator(long seed, Map<ResourceKey<Biome>, List<Ore>> oreConfig, LongPredicate minedOre, Options options) {
        this.seed = seed;
        this.oreConfig = oreConfig;
        this.minedOre = minedOre;
        this.options = options;
        // Vanilla decorates in step, then feature-index order, and an ore
        // placed earlier blocks later ones, so the order matters.
        this.dimensionOres = oreConfig.values().stream().flatMap(List::stream).distinct()
            .sorted(Comparator.comparingInt((Ore ore) -> ore.step).thenComparingInt(ore -> ore.index))
            .collect(Collectors.toList());
    }

    /**
     * Simulates the ore decoration of one chunk. Veins reach into the
     * neighbouring chunks, so those must be readable through {@code world}.
     * Not thread-safe: one chunk at a time.
     */
    public Map<Ore, Set<BlockPos>> simulateChunk(ChunkPos chunkPos, WorldView world) {
        this.world = world;
        placedThisChunk.clear();
        surfaceHeights.clear();

        int chunkX = chunkPos.x() << 4;
        int chunkZ = chunkPos.z() << 4;
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long populationSeed = random.setDecorationSeed(seed, chunkX, chunkZ);

        Map<Ore, Set<BlockPos>> result = new HashMap<>();
        // Every ore of this dimension is attempted; the biome check below
        // filters each candidate position exactly, so scanning the full biome
        // palettes of nine chunks up front is unnecessary.
        for (Ore ore : dimensionOres) {
            HashSet<BlockPos> ores = new HashSet<>();
            random.setFeatureSeed(populationSeed, ore.index, ore.step);

            int repeat = ore.count.sample(random);
            for (int i = 0; i < repeat; i++) {
                if (ore.rarity != 1F && random.nextFloat() >= 1 / ore.rarity) {
                    continue;
                }
                int x = random.nextInt(16) + chunkX;
                int z = random.nextInt(16) + chunkZ;
                int y = ore.heightProvider.sample(random, ore.heightContext);
                BlockPos origin = new BlockPos(x, y, z);

                ResourceKey<Biome> biome = world.getBiome(origin).unwrapKey().orElse(null);
                if (biome == null || !oresOf(biome).contains(ore)) {
                    continue;
                }

                if (ore.scattered) {
                    ores.addAll(generateHidden(random, origin, ore));
                } else {
                    ores.addAll(generateNormal(random, origin, ore));
                }
            }
            if (!ores.isEmpty()) {
                result.put(ore, ores);
            }
        }
        this.world = null;
        return result;
    }

    private List<Ore> oresOf(ResourceKey<Biome> biome) {
        List<Ore> ores = oreConfig.get(biome);
        // Biomes without a specific list (e.g. from datapacks) get every ore.
        return ores != null ? ores : dimensionOres;
    }

    // ====================================
    // Vanilla ore generation
    // ====================================

    private List<BlockPos> generateNormal(WorldgenRandom random, BlockPos blockPos, Ore ore) {
        int veinSize = ore.size;
        float f = random.nextFloat() * 3.1415927F;
        float g = (float) veinSize / 8.0F;
        int i = Mth.ceil(((float) veinSize / 16.0F * 2.0F + 1.0F) / 2.0F);
        double d = (double) blockPos.getX() + Math.sin(f) * (double) g;
        double e = (double) blockPos.getX() - Math.sin(f) * (double) g;
        double h = (double) blockPos.getZ() + Math.cos(f) * (double) g;
        double j = (double) blockPos.getZ() - Math.cos(f) * (double) g;
        double l = (blockPos.getY() + random.nextInt(3) - 2);
        double m = (blockPos.getY() + random.nextInt(3) - 2);
        int n = blockPos.getX() - Mth.ceil(g) - i;
        int o = blockPos.getY() - 2 - i;
        int p = blockPos.getZ() - Mth.ceil(g) - i;
        int q = 2 * (Mth.ceil(g) + i);
        int r = 2 * (2 + i);
        // Vanilla only places the vein if the terrain reaches its bottom in some
        // column; skipping it leaves the random sequence untouched, so getting
        // this wrong (e.g. counting ocean water as terrain) shifts every later
        // vein of the feature in this chunk.
        for (int s = n; s <= n + q; ++s) {
            for (int t = p; t <= p + q; ++t) {
                if (o <= surfaceHeight(s, t)) {
                    return generateVeinPart(random, veinSize, d, e, h, j, l, m, n, o, p, q, r, ore);
                }
            }
        }
        return new ArrayList<>();
    }

    private int surfaceHeight(int x, int z) {
        if (!options.oreSurfaceHeight()) return world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        long column = BlockPos.asLong(x, 0, z);
        if (!surfaceHeights.containsKey(column)) surfaceHeights.put(column, world.getOreSurfaceHeight(x, z));
        return surfaceHeights.get(column);
    }

    private List<BlockPos> generateVeinPart(WorldgenRandom random, int veinSize, double startX, double endX, double startZ, double endZ, double startY, double endY, int x, int y, int z, int size, int i, Ore ore) {
        BitSet bitSet = new BitSet(size * i * size);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        double[] ds = new double[veinSize * 4];
        List<BlockPos> poses = new ArrayList<>();
        int n;
        double p;
        double q;
        double r;
        double s;
        for (n = 0; n < veinSize; ++n) {
            float f = (float) n / (float) veinSize;
            p = Mth.lerp(f, startX, endX);
            q = Mth.lerp(f, startY, endY);
            r = Mth.lerp(f, startZ, endZ);
            s = random.nextDouble() * (double) veinSize / 16.0D;
            double m = ((double) (Mth.sin(3.1415927F * f) + 1.0F) * s + 1.0D) / 2.0D;
            ds[n * 4] = p;
            ds[n * 4 + 1] = q;
            ds[n * 4 + 2] = r;
            ds[n * 4 + 3] = m;
        }
        for (n = 0; n < veinSize - 1; ++n) {
            if (!(ds[n * 4 + 3] <= 0.0D)) {
                for (int o = n + 1; o < veinSize; ++o) {
                    if (!(ds[o * 4 + 3] <= 0.0D)) {
                        p = ds[n * 4] - ds[o * 4];
                        q = ds[n * 4 + 1] - ds[o * 4 + 1];
                        r = ds[n * 4 + 2] - ds[o * 4 + 2];
                        s = ds[n * 4 + 3] - ds[o * 4 + 3];
                        if (s * s > p * p + q * q + r * r) {
                            if (s > 0.0D) {
                                ds[o * 4 + 3] = -1.0D;
                            } else {
                                ds[n * 4 + 3] = -1.0D;
                            }
                        }
                    }
                }
            }
        }
        for (n = 0; n < veinSize; ++n) {
            double u = ds[n * 4 + 3];
            if (!(u < 0.0D)) {
                double v = ds[n * 4];
                double w = ds[n * 4 + 1];
                double aa = ds[n * 4 + 2];
                int ab = Math.max(Mth.floor(v - u), x);
                int ac = Math.max(Mth.floor(w - u), y);
                int ad = Math.max(Mth.floor(aa - u), z);
                int ae = Math.max(Mth.floor(v + u), ab);
                int af = Math.max(Mth.floor(w + u), ac);
                int ag = Math.max(Mth.floor(aa + u), ad);
                for (int ah = ab; ah <= ae; ++ah) {
                    double ai = ((double) ah + 0.5D - v) / u;
                    if (ai * ai < 1.0D) {
                        for (int aj = ac; aj <= af; ++aj) {
                            double ak = ((double) aj + 0.5D - w) / u;
                            if (ai * ai + ak * ak < 1.0D) {
                                for (int al = ad; al <= ag; ++al) {
                                    double am = ((double) al + 0.5D - aa) / u;
                                    if (ai * ai + ak * ak + am * am < 1.0D) {
                                        int an = ah - x + (aj - y) * size + (al - z) * size * i;
                                        if (!bitSet.get(an)) {
                                            bitSet.set(an);
                                            mutable.set(ah, aj, al);
                                            if (!world.isOutsideBuildHeight(aj) && canPlaceOre(random, mutable, ore)) {
                                                poses.add(mutable.immutable());
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return poses;
    }

    private List<BlockPos> generateHidden(WorldgenRandom random, BlockPos origin, Ore ore) {
        List<BlockPos> poses = new ArrayList<>();
        int count = random.nextInt(ore.size + 1);
        for (int j = 0; j < count; ++j) {
            int size = Math.min(j, 7);
            int x = randomCoord(random, size) + origin.getX();
            int y = randomCoord(random, size) + origin.getY();
            int z = randomCoord(random, size) + origin.getZ();
            BlockPos pos = new BlockPos(x, y, z);
            if (canPlaceOre(random, pos, ore)) {
                poses.add(pos);
            }
        }
        return poses;
    }

    private int randomCoord(WorldgenRandom random, int size) {
        return Math.round((random.nextFloat() - random.nextFloat()) * (float) size);
    }

    // ====================================
    // Vanilla placement decisions
    // ====================================

    /**
     * Mirrors {@code AbstractOreFeature#canPlaceOre}: the block must be a valid
     * replacement target (or already hold the ore, since the real world is
     * generated), then the air-exposure rule decides.
     */
    private boolean canPlaceOre(RandomSource random, BlockPos pos, Ore ore) {
        long packed = pos.asLong();
        // Already turned into ore by an earlier feature or vein: not replaceable
        // (and, like vanilla, no random number is drawn).
        if (options.trackPlacedOres() && placedThisChunk.contains(packed)) return false;

        boolean placed = false;
        BlockState state = world.getBlockState(pos);
        // A mined-out ore, or stone that a later feature replaced, was a valid
        // target when the ores generated; treating it as one keeps the random
        // sequence, and so the rest of the vein, in sync.
        if (minedOre.test(packed) || (options.laterReplacements() && isLaterReplacement(state))) {
            placed = shouldPlace(random, pos, ore.discardOnAirChance);
        } else {
            for (Target target : ore.targets) {
                if (target.test().test(state, pos, random) || state.equals(target.state())) {
                    placed = shouldPlace(random, pos, ore.discardOnAirChance);
                    break;
                }
            }
        }
        if (placed) placedThisChunk.add(packed);
        return placed;
    }

    /**
     * Blocks that features after the ore step place over stone: sculk patches
     * (deep dark), moss (lush caves) and dripstone clusters (dripstone caves).
     */
    private static boolean isLaterReplacement(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.SCULK || block == Blocks.MOSS_BLOCK || block == Blocks.DRIPSTONE_BLOCK;
    }

    /** Mirrors vanilla's {@code shouldSkipAirCheck} + {@code isAdjacentToAir} logic. */
    private boolean shouldPlace(RandomSource random, BlockPos pos, float discardOnAir) {
        if (discardOnAir <= 0F) return true;
        if (discardOnAir < 1F && random.nextFloat() >= discardOnAir) return true;
        return !isAdjacentToAir(pos);
    }

    private boolean isAdjacentToAir(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.offset(direction.getUnitVec3i());
            // Mined-out ores were solid when the world generated.
            if (world.getBlockState(neighbor).isAir() && !minedOre.test(neighbor.asLong())) {
                return true;
            }
        }
        return false;
    }
}
