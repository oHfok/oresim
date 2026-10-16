package dev.gumark.oresim.tester;

import dev.gumark.oresim.Ore;
import dev.gumark.oresim.OreSimulator;
import dev.gumark.oresim.OreType;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Measures how well OreSim predicts ores: generates a world on a real
 * dedicated server, runs the mod's own {@link OreSimulator} against what a
 * client would see, and compares with where the ores actually are.
 *
 * <p>Runs once the server has started, writes {@code oresim-accuracy-<seed>.md}
 * into the server directory and stops the server. Settings (system properties):
 * {@code oresim.test.radius} (chunks around 0,0; default 8),
 * {@code oresim.test.dimensions} (default {@code overworld,nether}) and
 * {@code oresim.test.tunnelSpacing} (default 8).
 */
public final class AccuracyTester implements DedicatedServerModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("OreSim accuracy");
    /** Feet and head level of the simulated strip mines (diamond level). */
    private static final int TUNNEL_Y = -59;

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                run(server);
            } catch (Throwable t) {
                LOG.error("Accuracy test failed", t);
            } finally {
                server.halt(false);
            }
        });
    }

    private static void run(MinecraftServer server) throws IOException {
        long seed = server.overworld().getSeed();
        int radius = Integer.getInteger("oresim.test.radius", 8);
        int tunnelSpacing = Integer.getInteger("oresim.test.tunnelSpacing", 8);
        String[] dimensions = System.getProperty("oresim.test.dimensions", "overworld,nether").split(",");

        int side = 2 * radius + 1;
        StringBuilder report = new StringBuilder();
        report.append("# OreSim accuracy, seed ").append(seed).append("\n\n");
        report.append("Minecraft 26.1.2. Evaluated area: chunks ").append(-radius).append("..").append(radius)
            .append(" around 0,0 (").append(side * side).append(" chunks, ").append(side * 16).append("×")
            .append(side * 16).append(" blocks).\n\n");
        report.append("- **Precision**: share of predicted positions that really hold that ore (low = boxes on stone).\n");
        report.append("- **Recall**: share of real ores that were predicted (low = ores without a box).\n");
        report.append("- **Anti-xray**: the client sees ores enclosed in stone as stone (Paper engine mode 1).\n\n");

        for (String name : dimensions) {
            ResourceKey<Level> key = switch (name.trim()) {
                case "nether" -> Level.NETHER;
                case "end" -> Level.END;
                default -> Level.OVERWORLD;
            };
            ServerLevel level = server.getLevel(key);
            if (level == null) continue;
            testDimension(report, level, seed, radius, tunnelSpacing);
        }

        Path output = server.getServerDirectory().resolve("oresim-accuracy-" + seed + ".md");
        Files.writeString(output, report, StandardCharsets.UTF_8);
        LOG.info("Report written to {}\n{}", output.toAbsolutePath(), report);
    }

    private record Scenario(String name, boolean antiXray, boolean legacyBiome, OreSimulator.Options options) {
    }

    private static void testDimension(StringBuilder report, ServerLevel level, long seed, int radius, int tunnelSpacing) {
        String dimension = level.dimension().identifier().getPath();
        long start = System.currentTimeMillis();
        // The simulated chunks reach one chunk past the evaluated area, and
        // their veins read one chunk further: generate that much.
        int generated = 0;
        for (int cx = -radius - 2; cx <= radius + 2; cx++) {
            for (int cz = -radius - 2; cz <= radius + 2; cz++) {
                level.getChunk(cx, cz);
                generated++;
            }
        }
        LOG.info("{}: generated {} chunks in {} s", dimension, generated, (System.currentTimeMillis() - start) / 1000);

        WorldGenerationContext heightContext = new WorldGenerationContext(level.getChunkSource().getGenerator(), level);
        Map<ResourceKey<Biome>, List<Ore>> oreConfig = Ore.getRegistry(level.dimension(), heightContext);
        Map<OreType, LongOpenHashSet> real = scanRealOres(level, radius);
        LongSet noTunnels = new LongOpenHashSet();
        LongPredicate nothingMined = pos -> false;

        List<Scenario> scenarios = new ArrayList<>();
        OreSimulator.Options original = new OreSimulator.Options(false, false, false);
        for (boolean antiXray : new boolean[]{true, false}) {
            scenarios.add(new Scenario("Original (meteor-rejects port)", antiXray, true, original));
            scenarios.add(new Scenario("**Current**", antiXray, false, OreSimulator.Options.CURRENT));
            scenarios.add(new Scenario("Current without surface-height fix", antiXray, false, new OreSimulator.Options(true, true, false)));
            scenarios.add(new Scenario("Current without ore-blocking fix", antiXray, false, new OreSimulator.Options(false, true, true)));
            scenarios.add(new Scenario("Current without sculk/moss/dripstone fix", antiXray, false, new OreSimulator.Options(true, false, true)));
            scenarios.add(new Scenario("Current without biome fix", antiXray, true, OreSimulator.Options.CURRENT));
        }

        report.append("## ").append(dimension).append("\n\n");
        report.append("| Scenario | Client view | Diamond precision | Diamond recall | All ores precision | All ores recall |\n");
        report.append("|---|---|---|---|---|---|\n");
        Score current = null;
        Score originalScore = null;
        Map<OreType, LongOpenHashSet> currentPredictions = null;
        for (Scenario scenario : scenarios) {
            ClientViewOfServer view = new ClientViewOfServer(level, scenario.antiXray, scenario.legacyBiome, noTunnels);
            Map<OreType, LongOpenHashSet> predicted = simulate(seed, oreConfig, scenario.options, nothingMined, view, radius);
            Score score = Score.compare(predicted, real, noTunnels);
            row(report, scenario.name, scenario.antiXray ? "anti-xray" : "no anti-xray", score);
            if (scenario.antiXray && scenario.options == OreSimulator.Options.CURRENT && !scenario.legacyBiome) {
                current = score;
                currentPredictions = predicted;
            }
            if (scenario.antiXray && scenario.options == original) originalScore = score;
        }

        if (level.dimension() == Level.OVERWORLD) {
            // Strip mines at diamond level: dug before the client simulates.
            LongOpenHashSet tunnels = new LongOpenHashSet();
            int min = -radius * 16;
            int max = (radius + 1) * 16 - 1;
            for (int x = min; x <= max; x++) {
                for (int z = min; z <= max; z++) {
                    if (Math.floorMod(z, tunnelSpacing) != 0) continue;
                    tunnels.add(BlockPos.asLong(x, TUNNEL_Y, z));
                    tunnels.add(BlockPos.asLong(x, TUNNEL_Y + 1, z));
                }
            }
            ClientViewOfServer tunnelView = new ClientViewOfServer(level, true, false, tunnels);

            Map<OreType, LongOpenHashSet> byOthers = simulate(seed, oreConfig, OreSimulator.Options.CURRENT, nothingMined, tunnelView, radius);
            row(report, "Current, strip mines by other players", "anti-xray", Score.compare(byOthers, real, tunnels));

            // Digging your own tunnels, OreSim records the predicted ores you dug through.
            LongOpenHashSet minedPredictions = new LongOpenHashSet();
            for (LongOpenHashSet positions : currentPredictions.values()) {
                for (long pos : positions) {
                    if (tunnels.contains(pos)) minedPredictions.add(pos);
                }
            }
            Map<OreType, LongOpenHashSet> own = simulate(seed, oreConfig, OreSimulator.Options.CURRENT, minedPredictions::contains, tunnelView, radius);
            row(report, "Current, own strip mines (mined ores remembered)", "anti-xray", Score.compare(own, real, tunnels));
            report.append("\nStrip mines: 1×2 tunnels at Y ").append(TUNNEL_Y).append(" every ").append(tunnelSpacing)
                .append(" blocks across the whole area; dug-out positions are left out of the comparison.\n");
        }

        report.append("\n### Per ore type (anti-xray)\n\n");
        report.append("| Ore | Real | Original precision | Original recall | Current precision | Current recall |\n");
        report.append("|---|---|---|---|---|---|\n");
        for (OreType type : OreType.values()) {
            if (current.real(type) == 0 && current.predicted(type) == 0) continue;
            report.append("| ").append(type.label).append(" | ").append(current.real(type)).append(" | ")
                .append(originalScore.precision(type)).append(" | ").append(originalScore.recall(type)).append(" | ")
                .append(current.precision(type)).append(" | ").append(current.recall(type)).append(" |\n");
        }
        if (level.dimension() == Level.OVERWORLD) {
            report.append("\nIron and copper also generate in large noise-based ore veins, which aren't ore features "
                + "and aren't simulated; they lower the recall for those two.\n");
        }
        report.append("\n");
        LOG.info("{}: done in {} s", dimension, (System.currentTimeMillis() - start) / 1000);
    }

    private static void row(StringBuilder report, String name, String view, Score score) {
        report.append("| ").append(name).append(" | ").append(view).append(" | ")
            .append(score.precision(OreType.DIAMOND)).append(" | ").append(score.recall(OreType.DIAMOND)).append(" | ")
            .append(score.totalPrecision()).append(" | ").append(score.totalRecall()).append(" |\n");
    }

    /** Runs the simulation over the evaluated area plus one chunk around it, keeping positions inside the area. */
    private static Map<OreType, LongOpenHashSet> simulate(long seed, Map<ResourceKey<Biome>, List<Ore>> oreConfig,
                                                         OreSimulator.Options options, LongPredicate mined,
                                                         ClientViewOfServer view, int radius) {
        OreSimulator simulator = new OreSimulator(seed, oreConfig, mined, options);
        Map<OreType, LongOpenHashSet> result = new EnumMap<>(OreType.class);
        int min = -radius * 16;
        int max = (radius + 1) * 16 - 1;
        for (int cx = -radius - 1; cx <= radius + 1; cx++) {
            for (int cz = -radius - 1; cz <= radius + 1; cz++) {
                for (Map.Entry<Ore, Set<BlockPos>> entry : simulator.simulateChunk(new ChunkPos(cx, cz), view).entrySet()) {
                    LongOpenHashSet positions = result.computeIfAbsent(entry.getKey().type, type -> new LongOpenHashSet());
                    for (BlockPos pos : entry.getValue()) {
                        if (pos.getX() >= min && pos.getX() <= max && pos.getZ() >= min && pos.getZ() <= max) {
                            positions.add(pos.asLong());
                        }
                    }
                }
            }
        }
        return result;
    }

    /** Every ore block in the evaluated area, by type. */
    private static Map<OreType, LongOpenHashSet> scanRealOres(ServerLevel level, int radius) {
        Map<OreType, LongOpenHashSet> result = new EnumMap<>(OreType.class);
        OreType[] types = OreType.values();
        for (int cx = -radius; cx <= radius; cx++) {
            for (int cz = -radius; cz <= radius; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                LevelChunkSection[] sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    LevelChunkSection section = sections[index];
                    if (section.hasOnlyAir() || !section.maybeHas(AccuracyTester::isAnyOre)) continue;
                    int baseY = chunk.getMinY() + index * 16;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState state = section.getBlockState(x, y, z);
                                for (OreType type : types) {
                                    if (type.isOre(state.getBlock())) {
                                        result.computeIfAbsent(type, t -> new LongOpenHashSet())
                                            .add(BlockPos.asLong((cx << 4) + x, baseY + y, (cz << 4) + z));
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private static boolean isAnyOre(BlockState state) {
        for (OreType type : OreType.values()) {
            if (type.isOre(state.getBlock())) return true;
        }
        return false;
    }
}
