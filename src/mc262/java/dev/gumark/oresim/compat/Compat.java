package dev.gumark.oresim.compat;

import com.mojang.blaze3d.platform.InputConstants;
import dev.gumark.oresim.Target;
import dev.gumark.oresim.mixin.CountPlacementModifierAccessor;
import dev.gumark.oresim.mixin.HeightRangePlacementModifierAccessor;
import dev.gumark.oresim.mixin.RarityFilterPlacementModifierAccessor;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.RarityFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Version-specific API access for Minecraft 26.1/26.2 (placement modifiers
 * hold private fields read through accessor mixins, and the ore configuration
 * lives on the {@link net.minecraft.world.level.levelgen.feature.ConfiguredFeature}).
 */
public final class Compat {
    private Compat() {
    }

    /** Ore parameters read from a vanilla placed feature. */
    public record OreData(float discardOnAirChance, int size, List<Target> targets, boolean scattered) {
    }

    public static OreData oreData(PlacedFeature feature) {
        var configured = feature.feature().value();
        // ScatteredOreFeature is a sibling of OreFeature here, not a subclass.
        if ((configured.feature() instanceof OreFeature || configured.feature() instanceof ScatteredOreFeature)
            && configured.config() instanceof OreConfiguration config) {
            List<Target> targets = new ArrayList<>();
            for (OreConfiguration.TargetBlockState target : config.targetStates) {
                targets.add(new Target((state, pos, random) -> target.target.test(state, random), target.state));
            }
            return new OreData(
                config.discardChanceOnAirExposure,
                config.size,
                targets,
                configured.feature() instanceof ScatteredOreFeature
            );
        }
        throw new IllegalStateException("config for " + feature + " is not an ore feature");
    }

    public static IntProvider countOf(CountPlacement placement) {
        return ((CountPlacementModifierAccessor) placement).oresim$count();
    }

    public static HeightProvider heightOf(HeightRangePlacement placement) {
        return ((HeightRangePlacementModifierAccessor) placement).oresim$height();
    }

    public static float chanceOf(RarityFilter filter) {
        return ((RarityFilterPlacementModifierAccessor) filter).oresim$chance();
    }

    public static HolderLookup.Provider worldLookup() {
        return VanillaRegistries.createLookup();
    }

    public static InputConstants.Type keyboardType() {
        return InputConstants.Type.KEYSYM;
    }
}
