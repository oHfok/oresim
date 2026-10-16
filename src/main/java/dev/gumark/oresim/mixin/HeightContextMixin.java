package dev.gumark.oresim.mixin;

import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@link WorldGenerationContext} requires a {@link ChunkGenerator}, but the client
 * level does not expose one. The simulation only needs the height range supplied
 * through the {@link net.minecraft.world.level.LevelHeightAccessor} parameter, so
 * the generator calls are made null-safe here.
 */
@Mixin(WorldGenerationContext.class)
public abstract class HeightContextMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;getMinY()I"))
    private int oresim$minY(ChunkGenerator instance) {
        return instance == null ? -9999999 : instance.getMinY();
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;getGenDepth()I"))
    private int oresim$genDepth(ChunkGenerator instance) {
        return instance == null ? 100000000 : instance.getGenDepth();
    }

    // 26.1.x's constructor never reads the sea level, so this target only exists on newer versions.
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;getSeaLevel()I"), require = 0)
    private int oresim$seaLevel(ChunkGenerator instance) {
        return instance == null ? 0 : instance.getSeaLevel();
    }
}
