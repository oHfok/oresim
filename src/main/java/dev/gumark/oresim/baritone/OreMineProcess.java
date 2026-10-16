package dev.gumark.oresim.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import dev.gumark.oresim.OreSim;
import dev.gumark.oresim.OreType;
import dev.gumark.oresim.OverlayToggles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Baritone process that mines the ores predicted by {@link OreSim}.
 *
 * <p>Baritone's own {@code #mine} only targets ores the client can see, which
 * anti-xray hides. This process instead hands Baritone the nearest simulated
 * positions as goals; standing in a position means its block was dug out.
 * Drops of the wanted ores are picked up before moving on, and when no
 * predicted ore is left in range it tunnels onward so new chunks get simulated.
 *
 * <p>Only referenced when Baritone is installed, so the mod still loads without it.
 */
public final class OreMineProcess implements IBaritoneProcess {
    /** How many of the nearest predicted ores Baritone may choose between at once. */
    private static final int GOAL_COUNT = 16;
    /** How far around the player dropped ores are collected. */
    private static final double PICKUP_RADIUS = 12;
    /** Give up on a drop that hasn't been collected after this long (stuck in a wall, lava, ...). */
    private static final int PICKUP_TIMEOUT_TICKS = 200;
    /** Distance of each exploration leg. */
    private static final int EXPLORE_STEP = 48;
    /** While exploring, how often to look for newly simulated ores. */
    private static final int EXPLORE_RESCAN_TICKS = 20;
    /** Visible ores are searched for in a cube of this radius around the player. */
    private static final int VISIBLE_RADIUS = 10;
    /** While mining, how often to look for newly visible ores. */
    private static final int VISIBLE_RESCAN_TICKS = 20;
    /**
     * Visible ores are certain, predictions are not, so a visible ore is
     * treated as if it were half as far away (a quarter of the squared distance).
     */
    private static final double VISIBLE_DIST_SQR_FACTOR = 0.25;
    /** Half-size of the cube searched for ores to break in place (reach is about 4.5 blocks). */
    private static final int REACH_SCAN = 5;

    private enum Mode { ORES, PICKUP, EXPLORE }

    private static OreMineProcess instance;

    private final Minecraft mc = Minecraft.getInstance();
    private final IBaritone baritone;
    private final OreSim oreSim;
    /** Positions Baritone couldn't reach; cleared when the level changes. */
    private final Set<Long> blacklist = new HashSet<>();
    /** Dropped item entity id &rarr; tick it was first targeted. */
    private final Map<Integer, Integer> pickupSince = new HashMap<>();
    private final Set<Integer> ignoredDrops = new HashSet<>();
    /** The ore goal set Baritone is currently pathing to; kept until one of them is resolved. */
    private List<Candidate> targets = List.of();
    private List<ItemEntity> drops = List.of();
    /** Ore types to mine, or {@code null} to follow the overlay toggles live. */
    private Set<OreType> types;
    private Mode mode = Mode.ORES;
    private Direction exploreDir;
    private int exploreY;
    private BlockPos exploreGoal;
    private int exploreFailures;
    private int lastOreScan;
    private int lastVisibleScan;
    private int tick;
    private ClientLevel level;
    private boolean active;
    /** Whether this process is holding the attack key to break an ore in reach. */
    private boolean clicking;

    private OreMineProcess(IBaritone baritone, OreSim oreSim) {
        this.baritone = baritone;
        this.oreSim = oreSim;
    }

    /**
     * Starts mining the given ore types, or whatever the overlay shows when
     * {@code types} is {@code null}; returns a message describing the outcome.
     */
    public static String start(OreSim oreSim, Set<OreType> types) {
        if (!oreSim.isActive()) {
            oreSim.setActive(true);
            if (!oreSim.isActive()) return "OreSim: no seed set. Use /setoresimseed <seed> first.";
        }
        if (instance == null) {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            instance = new OreMineProcess(baritone, oreSim);
            baritone.getPathingControlManager().registerProcess(instance);
        }
        instance.reset();
        instance.types = types;
        // Explore along the way the player faces, at the depth they started at.
        instance.exploreDir = instance.mc.player.getDirection();
        instance.exploreY = instance.mc.player.blockPosition().getY();
        instance.active = true;
        String what = types == null
            ? "the ores shown in /oresim"
            : types.stream().map(type -> type.label).collect(Collectors.joining(", "));
        return "OreSim: mining " + what + " (exploring " + instance.exploreDir.getName()
            + " at Y " + instance.exploreY + " when none are in range). Stop with /oresim stop or #stop.";
    }

    /** Stops mining; returns a message describing the outcome. */
    public static String stop() {
        if (instance == null || !instance.active) return "OreSim: not mining.";
        instance.baritone.getPathingBehavior().cancelEverything();
        instance.onLostControl();
        return "OreSim: stopped mining.";
    }

    private void reset() {
        blacklist.clear();
        pickupSince.clear();
        ignoredDrops.clear();
        targets = List.of();
        drops = List.of();
        mode = Mode.ORES;
        exploreGoal = null;
        exploreFailures = 0;
    }

    @Override
    public boolean isActive() {
        return active && mc.player != null && mc.level != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        tick++;
        if (!oreSim.isActive()) {
            return finish("OreSim: overlay was turned off, stopped mining.");
        }
        if (mc.level != level) {
            level = mc.level;
            reset();
        }
        if (calcFailed && !handleFailure()) {
            return finish("OreSim: stuck, Baritone can't explore in any direction. Stopped mining.");
        }

        Set<OreType> wanted = types != null ? types : OverlayToggles.enabledTypes();

        PathingCommand breaking = breakOreInReach(wanted, isSafeToCancel);
        if (breaking != null) return breaking;

        // Collect drops first, so leftovers aren't abandoned when the next ore is picked.
        drops = nearbyDrops(wanted);
        if (!drops.isEmpty()) {
            mode = Mode.PICKUP;
            Goal[] goals = drops.stream().map(item -> new GoalBlock(item.blockPosition())).toArray(Goal[]::new);
            return new PathingCommand(new GoalComposite(goals), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        // A vein's remaining blocks become visible as it is dug into (and the
        // prediction may have missed some), so check for closer visible ores.
        if (mode == Mode.ORES && !targets.isEmpty() && tick - lastVisibleScan >= VISIBLE_RESCAN_TICKS
            && hasCloserVisibleOre(wanted)) {
            targets = List.of();
        }

        boolean rescan = mode != Mode.EXPLORE || tick - lastOreScan >= EXPLORE_RESCAN_TICKS;
        // Keep the goal set stable while Baritone works on it, so it doesn't
        // abandon a path every time the "nearest" ordering shifts by a block.
        if (rescan && (targets.isEmpty() || targets.stream().anyMatch(this::isSettled))) {
            targets = nearestTargets(wanted);
            lastOreScan = tick;
        }
        if (!targets.isEmpty()) {
            mode = Mode.ORES;
            exploreGoal = null;
            exploreFailures = 0;
            Goal[] goals = targets.stream().map(target -> new GoalTwoBlocks(target.pos)).toArray(Goal[]::new);
            return new PathingCommand(new GoalComposite(goals), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        return explore();
    }

    /**
     * Breaks a wanted ore the player can reach and see from where it stands,
     * like Baritone's own {@code #mine}. Walking into an ore isn't always
     * possible (one in the ceiling would need blocks to pillar up to), and an
     * ore in reach shouldn't be left behind because the pathing gave up on it,
     * so the blacklist doesn't apply here.
     */
    private PathingCommand breakOreInReach(Set<OreType> wanted, boolean isSafeToCancel) {
        releaseClick();
        if (!isSafeToCancel || !mc.player.onGround()) return null;

        IPlayerContext ctx = baritone.getPlayerContext();
        BlockPos feet = mc.player.blockPosition();
        BlockPos below = feet.below();
        BlockPos best = null;
        Rotation bestRotation = null;
        int bestSlot = -1;
        double bestDistSqr = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-REACH_SCAN, -REACH_SCAN, -REACH_SCAN),
            feet.offset(REACH_SCAN, REACH_SCAN + 1, REACH_SCAN))) {
            BlockState state = mc.level.getBlockState(pos);
            if (!isWanted(state.getBlock(), wanted) || !isExposed(pos)) continue;
            // Breaking the block underfoot could drop the player into a cave or
            // lava; leave that one to the pathing, which knows what's below.
            if (pos.equals(below) || isNextToLava(pos)) continue;
            int slot = toolSlotFor(state);
            if (slot < 0) continue;
            double distSqr = mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
            if (distSqr >= bestDistSqr) continue;
            BlockPos ore = pos.immutable();
            Optional<Rotation> rotation = RotationUtils.reachable(ctx, ore);
            if (rotation.isEmpty()) continue;
            best = ore;
            bestRotation = rotation.get();
            bestSlot = slot;
            bestDistSqr = distSqr;
        }
        if (best == null) return null;

        mc.player.getInventory().setSelectedSlot(bestSlot);
        baritone.getLookBehavior().updateTarget(bestRotation, true);
        if (ctx.isLookingAt(best) || ctx.playerRotations().isReallyCloseTo(bestRotation)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            clicking = true;
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private void releaseClick() {
        if (clicking) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            clicking = false;
        }
    }

    private static boolean isWanted(Block block, Set<OreType> wanted) {
        for (OreType type : wanted) {
            if (type.isOre(block)) return true;
        }
        return false;
    }

    private boolean isNextToLava(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (mc.level.getFluidState(pos.relative(direction)).is(FluidTags.LAVA)) return true;
        }
        return false;
    }

    /**
     * The hotbar slot with the fastest tool that still gets the drop, or -1.
     * Without a good enough pickaxe (e.g. stone on diamond) the ore would be
     * destroyed for nothing, so it isn't broken here at all.
     */
    private int toolSlotFor(BlockState state) {
        Inventory inventory = mc.player.getInventory();
        int bestSlot = -1;
        float bestSpeed = 0;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /** Reacts to Baritone failing to path to the current goal; false means give up entirely. */
    private boolean handleFailure() {
        switch (mode) {
            case PICKUP -> drops.forEach(item -> ignoredDrops.add(item.getId()));
            case ORES -> {
                for (Candidate target : targets) blacklist.add(target.pos.asLong());
                targets = List.of();
            }
            case EXPLORE -> {
                if (++exploreFailures >= 4) return false;
                exploreDir = exploreDir.getClockWise();
                exploreGoal = null;
                message("OreSim: path blocked, exploring " + exploreDir.getName() + " instead.");
            }
        }
        return true;
    }

    private PathingCommand explore() {
        if (mode != Mode.EXPLORE) {
            mode = Mode.EXPLORE;
            message("OreSim: no predicted ores in range, exploring " + exploreDir.getName() + ".");
        }
        BlockPos feet = mc.player.blockPosition();
        if (exploreGoal == null || horizontalDistSqr(feet, exploreGoal) < 8 * 8) {
            exploreGoal = new BlockPos(feet.getX(), exploreY, feet.getZ()).relative(exploreDir, EXPLORE_STEP);
        }
        return new PathingCommand(new GoalNear(exploreGoal, 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private static int horizontalDistSqr(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private PathingCommand finish(String message) {
        onLostControl();
        message(message);
        return null;
    }

    private void message(String message) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(message));
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    @Override
    public void onLostControl() {
        releaseClick();
        active = false;
        targets = List.of();
        drops = List.of();
    }

    @Override
    public String displayName0() {
        return "OreSim mine";
    }

    private List<ItemEntity> nearbyDrops(Set<OreType> wanted) {
        Set<Item> wantedItems = new HashSet<>();
        for (OreType type : wanted) wantedItems.addAll(dropsOf(type));

        List<ItemEntity> found = mc.level.getEntitiesOfClass(
            ItemEntity.class,
            mc.player.getBoundingBox().inflate(PICKUP_RADIUS),
            item -> item.isAlive() && item.onGround() && wantedItems.contains(item.getItem().getItem())
                && !ignoredDrops.contains(item.getId())
        );
        List<ItemEntity> result = new ArrayList<>();
        for (ItemEntity item : found) {
            int since = pickupSince.computeIfAbsent(item.getId(), id -> tick);
            if (tick - since >= PICKUP_TIMEOUT_TICKS) {
                ignoredDrops.add(item.getId());
            } else {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * An ore to go for: either predicted by the simulation or seen in the world.
     * {@code sortKey} is the squared distance, scaled down for visible ores.
     */
    private record Candidate(BlockPos pos, OreType type, double sortKey, boolean visible) {
    }

    private List<Candidate> nearestTargets(Set<OreType> wanted) {
        BlockPos player = mc.player.blockPosition();
        List<Candidate> candidates = visibleOres(wanted);
        oreSim.forEachOre(wanted, (type, packed) -> {
            if (blacklist.contains(packed)) return;
            BlockPos pos = BlockPos.of(packed);
            candidates.add(new Candidate(pos, type, player.distSqr(pos), false));
        });
        candidates.sort((a, b) -> Double.compare(a.sortKey, b.sortKey));

        List<Candidate> nearest = new ArrayList<>(GOAL_COUNT);
        Set<Long> chosen = new HashSet<>();
        for (Candidate candidate : candidates) {
            long packed = candidate.pos.asLong();
            // Wrong predictions are disproven (and dropped) by OreSim itself once
            // anti-xray has had time to reveal them; until then a prediction that
            // looks wrong is most likely the next block of a vein, so keep it.
            if (chosen.contains(packed) || !isWorthMining(candidate.pos)) continue;
            nearest.add(candidate);
            chosen.add(packed);
            if (nearest.size() == GOAL_COUNT) break;
        }
        return nearest;
    }

    /**
     * Wanted ores the player can actually see. Only exposed blocks count:
     * anti-xray never disguises those, but may plant fake ores in enclosed ones.
     */
    private List<Candidate> visibleOres(Set<OreType> wanted) {
        lastVisibleScan = tick;
        Map<Block, OreType> typeOfBlock = new HashMap<>();
        for (OreType type : wanted) {
            for (Block ore : type.blocks()) typeOfBlock.put(ore, type);
        }

        BlockPos player = mc.player.blockPosition();
        List<Candidate> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
            player.offset(-VISIBLE_RADIUS, -VISIBLE_RADIUS, -VISIBLE_RADIUS),
            player.offset(VISIBLE_RADIUS, VISIBLE_RADIUS, VISIBLE_RADIUS))) {
            OreType type = typeOfBlock.get(mc.level.getBlockState(pos).getBlock());
            if (type == null || blacklist.contains(pos.asLong()) || !isExposed(pos)) continue;
            BlockPos ore = pos.immutable();
            found.add(new Candidate(ore, type, player.distSqr(ore) * VISIBLE_DIST_SQR_FACTOR, true));
        }
        return found;
    }

    /** Whether a visible ore not already targeted would rank ahead of the current goal set. */
    private boolean hasCloserVisibleOre(Set<OreType> wanted) {
        double worstKey = 0;
        Set<Long> targeted = new HashSet<>();
        for (Candidate target : targets) {
            worstKey = Math.max(worstKey, target.sortKey);
            targeted.add(target.pos.asLong());
        }
        for (Candidate ore : visibleOres(wanted)) {
            if (!targeted.contains(ore.pos.asLong()) && ore.sortKey < worstKey) return true;
        }
        return false;
    }

    /** Whether a target needs no more work: dug out, or a prediction OreSim has disproven. */
    private boolean isSettled(Candidate target) {
        return !isWorthMining(target.pos) || (!target.visible && oreSim.isResolved(target.pos.asLong()));
    }

    /** False once the block has been dug out (or replaced by a fluid). */
    private boolean isWorthMining(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    /** Anti-xray never disguises blocks next to air (or other see-through blocks). */
    private boolean isExposed(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (!mc.level.getBlockState(pos.relative(direction)).canOcclude()) return true;
        }
        return false;
    }

    /** What mining the ore can leave on the ground: its normal drop, or the block itself with Silk Touch. */
    private static List<Item> dropsOf(OreType type) {
        List<Item> items = new ArrayList<>();
        for (Block ore : type.blocks()) items.add(ore.asItem());
        switch (type) {
            case COAL -> items.add(Items.COAL);
            case IRON -> items.add(Items.RAW_IRON);
            case GOLD -> {
                items.add(Items.RAW_GOLD);
                items.add(Items.GOLD_NUGGET);
            }
            case REDSTONE -> items.add(Items.REDSTONE);
            case DIAMOND -> items.add(Items.DIAMOND);
            case LAPIS -> items.add(Items.LAPIS_LAZULI);
            case COPPER -> items.add(Items.RAW_COPPER);
            case EMERALD -> items.add(Items.EMERALD);
            case QUARTZ -> items.add(Items.QUARTZ);
            case DEBRIS -> {
            }
        }
        return items;
    }
}
