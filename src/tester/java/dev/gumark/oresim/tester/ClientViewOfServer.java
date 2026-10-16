package dev.gumark.oresim.tester;

import dev.gumark.oresim.OreSimulator;
import dev.gumark.oresim.OreType;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A generated server world as a client would see it, for the simulation to read.
 *
 * @param antiXray    ores fully enclosed by solid blocks show as the
 *                    surrounding stone, like Paper's anti-xray engine mode 1
 * @param legacyBiome the original simulation's biome lookup (block coordinates
 *                    passed as biome-cell coordinates), for comparison
 * @param tunnels     positions dug out after generation; they read as air
 */
record ClientViewOfServer(ServerLevel level, boolean antiXray, boolean legacyBiome, LongSet tunnels)
    implements OreSimulator.WorldView {

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = realState(pos);
        if (antiXray && isOre(state) && isEnclosed(pos)) {
            return disguise(pos);
        }
        return state;
    }

    private BlockState realState(BlockPos pos) {
        return tunnels.contains(pos.asLong()) ? Blocks.AIR.defaultBlockState() : level.getBlockState(pos);
    }

    private static boolean isOre(BlockState state) {
        for (OreType type : OreType.values()) {
            if (type.isOre(state.getBlock())) return true;
        }
        return false;
    }

    private boolean isEnclosed(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (!realState(pos.relative(direction)).canOcclude()) return false;
        }
        return true;
    }

    private BlockState disguise(BlockPos pos) {
        if (level.dimension() == Level.NETHER) return Blocks.NETHERRACK.defaultBlockState();
        return (pos.getY() < 0 ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState();
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return level.getHeight(type, x, z);
    }

    @Override
    public boolean isOutsideBuildHeight(int y) {
        return level.isOutsideBuildHeight(y);
    }

    @Override
    public Holder<Biome> getBiome(BlockPos pos) {
        if (legacyBiome) {
            return level.getChunk(pos.getX() >> 4, pos.getZ() >> 4).getNoiseBiome(pos.getX(), pos.getY(), pos.getZ());
        }
        return level.getBiome(pos);
    }
}
