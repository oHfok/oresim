package dev.gumark.oresim;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import dev.gumark.oresim.compat.Compat;
import net.minecraft.data.worldgen.placement.OrePlacements;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A single simulated ore placement, mirroring the vanilla placed-feature it was
 * built from (count, height range, rarity, vein size, air-exposure discard, ...).
 */
public class Ore {
    public int step;
    public int index;
    public IntProvider count = ConstantInt.of(1);
    public HeightProvider heightProvider;
    public WorldGenerationContext heightContext;
    public float rarity = 1;
    public float discardOnAirChance;
    public int size;
    public OreType type;
    public boolean scattered;
    public List<Target> targets;

    private Ore(PlacedFeature feature, int step, int index, OreType type, WorldGenerationContext heightContext) {
        this.step = step;
        this.index = index;
        this.type = type;
        this.heightContext = heightContext;

        for (PlacementModifier modifier : feature.placement()) {
            if (modifier instanceof CountPlacement countPlacement) {
                this.count = Compat.countOf(countPlacement);
            } else if (modifier instanceof HeightRangePlacement heightRangePlacement) {
                this.heightProvider = Compat.heightOf(heightRangePlacement);
            } else if (modifier instanceof RarityFilter rarityFilter) {
                this.rarity = Compat.chanceOf(rarityFilter);
            }
        }

        Compat.OreData oreData = Compat.oreData(feature);
        this.discardOnAirChance = oreData.discardOnAirChance();
        this.size = oreData.size();
        this.targets = oreData.targets();
        this.scattered = oreData.scattered();
    }

    private static void registerOre(
        WorldGenerationContext heightContext,
        Map<PlacedFeature, Ore> map,
        List<FeatureSorter.StepFeatureData> indexer,
        HolderLookup.RegistryLookup<PlacedFeature> oreRegistry,
        ResourceKey<PlacedFeature> oreKey,
        int genStep,
        OreType type
    ) {
        PlacedFeature orePlacement = oreRegistry.getOrThrow(oreKey).value();
        int index = indexer.get(genStep).indexMapping().applyAsInt(orePlacement);
        map.put(orePlacement, new Ore(orePlacement, genStep, index, type, heightContext));
    }

    /**
     * The height context the client simulates with: it has no chunk generator,
     * so {@code HeightContextMixin} makes the generator calls null-safe and the
     * height range comes from the level alone.
     */
    public static WorldGenerationContext clientHeightContext(int minY, int logicalHeight) {
        return new WorldGenerationContext(null, LevelHeightAccessor.create(minY, logicalHeight));
    }

    /**
     * Builds the biome &rarr; ores map for the given dimension from vanilla's
     * placed features; {@code heightContext} resolves their height ranges.
     */
    public static Map<ResourceKey<Biome>, List<Ore>> getRegistry(ResourceKey<Level> dimension, WorldGenerationContext heightContext) {
        HolderLookup.Provider registry = Compat.worldLookup();
        HolderLookup.RegistryLookup<PlacedFeature> features = registry.lookupOrThrow(Registries.PLACED_FEATURE);
        WorldPreset worldPreset = registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value();
        WorldDimensions dimensions = worldPreset.createWorldDimensions();

        ResourceKey<LevelStem> stemKey;
        if (dimension == Level.NETHER) stemKey = LevelStem.NETHER;
        else if (dimension == Level.END) stemKey = LevelStem.END;
        else stemKey = LevelStem.OVERWORLD;

        LevelStem stem = dimensions.get(stemKey).orElseThrow();

        List<Holder<Biome>> biomeList = stem.generator().getBiomeSource().possibleBiomes().stream().toList();
        List<FeatureSorter.StepFeatureData> indexer = FeatureSorter.buildFeaturesPerStep(
            biomeList, biomeEntry -> biomeEntry.value().getGenerationSettings().features(), true
        );

        Map<PlacedFeature, Ore> featureToOre = new HashMap<>();
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_COAL_LOWER, 6, OreType.COAL);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_COAL_UPPER, 6, OreType.COAL);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_IRON_MIDDLE, 6, OreType.IRON);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_IRON_SMALL, 6, OreType.IRON);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_IRON_UPPER, 6, OreType.IRON);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_GOLD, 6, OreType.GOLD);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_GOLD_LOWER, 6, OreType.GOLD);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_GOLD_EXTRA, 6, OreType.GOLD);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_GOLD_NETHER, 7, OreType.GOLD);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_GOLD_DELTAS, 7, OreType.GOLD);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_REDSTONE, 6, OreType.REDSTONE);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_REDSTONE_LOWER, 6, OreType.REDSTONE);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_DIAMOND, 6, OreType.DIAMOND);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_DIAMOND_BURIED, 6, OreType.DIAMOND);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_DIAMOND_LARGE, 6, OreType.DIAMOND);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_DIAMOND_MEDIUM, 6, OreType.DIAMOND);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_LAPIS, 6, OreType.LAPIS);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_LAPIS_BURIED, 6, OreType.LAPIS);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_COPPER, 6, OreType.COPPER);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_COPPER_LARGE, 6, OreType.COPPER);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_EMERALD, 6, OreType.EMERALD);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_QUARTZ_NETHER, 7, OreType.QUARTZ);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_QUARTZ_DELTAS, 7, OreType.QUARTZ);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_ANCIENT_DEBRIS_SMALL, 7, OreType.DEBRIS);
        registerOre(heightContext, featureToOre, indexer, features, OrePlacements.ORE_ANCIENT_DEBRIS_LARGE, 7, OreType.DEBRIS);

        Map<ResourceKey<Biome>, List<Ore>> biomeOreMap = new HashMap<>();
        biomeList.forEach(biome -> {
            ResourceKey<Biome> biomeKey = biome.unwrapKey().orElseThrow();
            List<Ore> ores = new ArrayList<>();
            biomeOreMap.put(biomeKey, ores);
            biome.value().getGenerationSettings().features().stream()
                .flatMap(HolderSet::stream)
                .map(Holder::value)
                .filter(featureToOre::containsKey)
                .forEach(feature -> ores.add(featureToOre.get(feature)));
        });
        return biomeOreMap;
    }
}
