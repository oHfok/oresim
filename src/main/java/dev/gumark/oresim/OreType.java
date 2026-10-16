package dev.gumark.oresim;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Everything the overlay can show; all types come from the seed simulation. */
public enum OreType {
    COAL("Coal", 0xFF4A4A4A),
    IRON("Iron", 0xFFECAD77),
    GOLD("Gold", 0xFFF7E51E),
    REDSTONE("Redstone", 0xFFF50717),
    DIAMOND("Diamond", 0xFF21F4FF),
    LAPIS("Lapis", 0xFF2D5CFF),
    COPPER("Copper", 0xFFEF9700),
    EMERALD("Emerald", 0xFF1BD12D),
    QUARTZ("Quartz", 0xFFCDCDCD),
    DEBRIS("Ancient Debris", 0xFFD11BF5);

    public final String label;
    public final int color;
    /** Resolved lazily: {@link Blocks} must not load before the game bootstraps. */
    private Block[] blocks;

    OreType(String label, int color) {
        this.label = label;
        this.color = color;
    }

    /** The blocks this ore type generates as. */
    public Block[] blocks() {
        if (blocks == null) {
            blocks = switch (this) {
                case COAL -> new Block[]{Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE};
                case IRON -> new Block[]{Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE};
                case GOLD -> new Block[]{Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE};
                case REDSTONE -> new Block[]{Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE};
                case DIAMOND -> new Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE};
                case LAPIS -> new Block[]{Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE};
                case COPPER -> new Block[]{Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE};
                case EMERALD -> new Block[]{Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE};
                case QUARTZ -> new Block[]{Blocks.NETHER_QUARTZ_ORE};
                case DEBRIS -> new Block[]{Blocks.ANCIENT_DEBRIS};
            };
        }
        return blocks;
    }

    public boolean isOre(Block block) {
        for (Block ore : blocks()) {
            if (ore == block) return true;
        }
        return false;
    }
}
