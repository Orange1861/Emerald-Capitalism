package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.util.DoorPairingUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brain behavior that allows villagers to open and close path openables while navigating.
 *
 * <p>This behavior is modeled after the vanilla {@code InteractWithDoor} behavior.
 * It is injected into the villager's core activity package so it always runs,
 * regardless of the villager's current activity (working, idling, etc.).</p>
 *
 * <h2>Behavior Flow:</h2>
 * <ol>
 *   <li>Each tick, examine the villager's current navigation path</li>
 *   <li>Look at the current and upcoming path nodes for openable blocks</li>
 *   <li>Open any closed emerald doors / fence gates the villager is approaching</li>
 *   <li>Close any previously opened blocks once the villager has moved away</li>
 * </ol>
 *
 * <h2>Design Notes:</h2>
 * <ul>
 *   <li>Opened gates are tracked per-villager via instance state</li>
 *   <li>Gates are closed when the villager moves more than {@value #CLOSE_DISTANCE_SQ}
 *       blocks away (squared distance) or when the behavior stops</li>
 *   <li>Only blocks opened by this behavior are tracked and closed, so
 *       player-opened blocks are never interfered with</li>
 * </ul>
 *
 * @see net.minecraft.world.entity.ai.behavior.InteractWithDoor
 */
public class InteractWithFenceGateBehavior extends Behavior<Mob> {

    /** Reuses one nearby-mob query when several villagers inspect the same gate in one tick. */
    private static final Map<ResourceKey<Level>, GateUseCache> GATE_USE_CACHES = new HashMap<>();

    /**
     * Squared distance threshold for closing a gate the villager has passed through.
     * At 4 blocks (16 squared), the villager is safely clear of the gate.
     */
    private static final double CLOSE_DISTANCE_SQ = 16.0;

    /**
     * How many path nodes ahead of the current node to scan for fence gates.
     */
    private static final int LOOK_AHEAD_NODES = 2;

    /**
     * Squared distance from a path node at which we open the fence gate.
     * At ~2.25 blocks the villager is close enough to reach through.
     */
    private static final double OPEN_DISTANCE_SQ = 5.0;

    /**
     * Positions of openable blocks that this behavior has opened and is responsible for closing.
     * Uses immutable BlockPos copies to avoid mutable coordinate issues.
     */
    private final Set<BlockPos> openedPositions = new HashSet<>();

    public InteractWithFenceGateBehavior() {
        super(Map.of(
                MemoryModuleType.PATH, MemoryStatus.VALUE_PRESENT
        ), 1, 100);
    }

    @Override
    protected void start(@Nonnull ServerLevel level, @Nonnull Mob entity, long gameTime) {
        if (!Config.enableFenceGateInteraction) {
            closeAllTrackedGates(level, entity);
            return;
        }
        handleOpenables(level, entity);
    }

    @Override
    protected void tick(@Nonnull ServerLevel level, @Nonnull Mob entity, long gameTime) {
        if (!Config.enableFenceGateInteraction) {
            closeAllTrackedGates(level, entity);
            return;
        }
        handleOpenables(level, entity);
    }

    @Override
    protected void stop(@Nonnull ServerLevel level, @Nonnull Mob entity, long gameTime) {
        closeAllTrackedGates(level, entity);
    }

    @Override
    protected boolean canStillUse(@Nonnull ServerLevel level, @Nonnull Mob entity, long gameTime) {
        return entity.getBrain().hasMemoryValue(MemoryModuleType.PATH);
    }

    /**
     * Core logic: opens openables ahead on the path and closes them behind.
     */
    private void handleOpenables(@Nonnull ServerLevel level, @Nonnull Mob entity) {
        Path path = entity.getNavigation().getPath();
        if (path == null || path.isDone()) {
            closeAllTrackedGates(level, entity);
            return;
        }

        openUpcomingOpenables(level, entity, path);
        closePassedGates(level, entity);
    }

    /**
     * Scans the current and upcoming path nodes for closed openables and opens them.
     */
    private void openUpcomingOpenables(@Nonnull ServerLevel level, @Nonnull Mob entity, @Nonnull Path path) {
        int currentIndex = path.getNextNodeIndex();
        int endIndex = Math.min(currentIndex + LOOK_AHEAD_NODES, path.getNodeCount());

        for (int i = Math.max(0, currentIndex - 1); i < endIndex; i++) {
            Node node = path.getNode(i);
            BlockPos nodePos = node.asBlockPos();

            // Check this position and one above (gates can be at foot or head height on slopes)
            tryOpenAt(level, entity, nodePos);
            tryOpenAt(level, entity, nodePos.above());
        }
    }

    /**
     * Attempts to open an interactable block at the given position if one exists and is closed.
     */
    private void tryOpenAt(@Nonnull ServerLevel level, @Nonnull Mob entity, @Nonnull BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isVillagerOpenable(state)) {
            return;
        }

        // Only open if the villager is close enough
        if (entity.blockPosition().distSqr(pos) > OPEN_DISTANCE_SQ) {
            return;
        }

        if (!state.getValue(BlockStateProperties.OPEN)) {
            setOpenState(level, pos, state, true);
            playOpenSound(level, pos, state);
            level.gameEvent(entity, GameEvent.BLOCK_OPEN, pos);

            openedPositions.add(pos.immutable());
        }
    }

    /**
     * Closes any tracked gates that the villager has moved far enough away from.
     * Also evicts positions where the fence gate block was replaced while the villager
     * was still nearby: those can never be closed and should not remain in the set.
     */
    private void closePassedGates(@Nonnull ServerLevel level, @Nonnull Mob entity) {
        Iterator<BlockPos> iterator = openedPositions.iterator();
        while (iterator.hasNext()) {
            BlockPos gatePos = iterator.next();

            BlockState state = level.getBlockState(gatePos);
            if (!isVillagerOpenable(state)) {
                iterator.remove();
                continue;
            }

            if (entity.blockPosition().distSqr(gatePos) > CLOSE_DISTANCE_SQ
                    && !isOpenableInUse(level, entity, gatePos)) {
                closeGateAt(level, entity, gatePos);
                iterator.remove();
            }
        }
    }

    /**
     * Closes all tracked gates. Called when the behavior stops or the path ends.
     */
    private void closeAllTrackedGates(@Nonnull ServerLevel level, @Nonnull Mob entity) {
        for (BlockPos gatePos : openedPositions) {
            if (!isOpenableInUse(level, entity, gatePos)) {
                closeGateAt(level, entity, gatePos);
            }
        }
        openedPositions.clear();
    }

    /** Keeps a gate open while another mob is near it or has it on its path. */
    private static boolean isOpenableInUse(@Nonnull ServerLevel level, @Nonnull Mob owner,
                                           @Nonnull BlockPos gatePos) {
        for (Mob mob : nearbyMobs(level, gatePos)) {
            if (mob == owner) {
                continue;
            }
            if (mob.blockPosition().distSqr(gatePos) <= CLOSE_DISTANCE_SQ
                    || pathContains(mob.getNavigation().getPath(), gatePos)) {
                return true;
            }
        }
        return false;
    }

    private static List<Mob> nearbyMobs(ServerLevel level, BlockPos gatePos) {
        ResourceKey<Level> dimension = level.dimension();
        GateUseCache cache = GATE_USE_CACHES.computeIfAbsent(dimension, ignored -> new GateUseCache());
        long gameTime = level.getGameTime();
        if (cache.tick != gameTime) {
            cache.tick = gameTime;
            cache.mobsByGate.clear();
        }
        BlockPos immutableGatePos = gatePos.immutable();
        return cache.mobsByGate.computeIfAbsent(immutableGatePos, pos -> {
            AABB area = new AABB(pos).inflate(6.0D);
            return List.copyOf(level.getEntitiesOfClass(Mob.class, area, Mob::isAlive));
        });
    }

    /** Clears cached entity lists when a server or level lifecycle ends. */
    public static void clearGateUseCache() {
        GATE_USE_CACHES.clear();
    }

    private static final class GateUseCache {
        private long tick = Long.MIN_VALUE;
        private final Map<BlockPos, List<Mob>> mobsByGate = new HashMap<>();
    }

    private static boolean pathContains(@javax.annotation.Nullable Path path, BlockPos target) {
        if (path == null || path.isDone()) {
            return false;
        }
        int start = path.getNextNodeIndex();
        int end = Math.min(start + LOOK_AHEAD_NODES + 2, path.getNodeCount());
        for (int i = start; i < end; i++) {
            if (path.getNode(i).asBlockPos().distSqr(target) <= 2.25D) {
                return true;
            }
        }
        return false;
    }

    /**
     * Closes a fence gate at the given position if it is currently open.
     */
    private static void closeGateAt(@Nonnull ServerLevel level, @Nonnull Mob entity, @Nonnull BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isVillagerOpenable(state)) {
            return; // Block was replaced; nothing to close
        }
        if (!state.getValue(BlockStateProperties.OPEN)) {
            return; // Already closed (player or another villager closed it)
        }

        setOpenState(level, pos, state, false);
        playCloseSound(level, pos, state);
        level.gameEvent(entity, GameEvent.BLOCK_CLOSE, pos);
    }

    private static boolean isVillagerOpenable(@Nonnull BlockState state) {
        if (state.getBlock() instanceof FenceGateBlock) {
            return true;
        }
        if (!(state.getBlock() instanceof DoorBlock) || !state.hasProperty(BlockStateProperties.OPEN)) {
            return false;
        }
        return state.is(ECAPBlocks.EMERALD_DOOR.get()) || state.is(ECAPBlocks.REGULAR_EMERALD_DOOR.get());
    }

    private static void setOpenState(@Nonnull ServerLevel level, @Nonnull BlockPos pos,
                                     @Nonnull BlockState state, boolean open) {
        if (state.getBlock() instanceof DoorBlock) {
            BlockPos lowerPos = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
            DoorPairingUtils.setDoorAndPairedOpen(level, lowerPos, open, 10);
            return;
        }

        level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), 10);
    }

    private static void playOpenSound(@Nonnull ServerLevel level, @Nonnull BlockPos pos, @Nonnull BlockState state) {
        if (state.getBlock() instanceof FenceGateBlock) {
            level.playSound(null, pos, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS,
                    1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
            return;
        }
        level.playSound(null, pos, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS,
                1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
    }

    private static void playCloseSound(@Nonnull ServerLevel level, @Nonnull BlockPos pos, @Nonnull BlockState state) {
        if (state.getBlock() instanceof FenceGateBlock) {
            level.playSound(null, pos, SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS,
                    1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
            return;
        }
        level.playSound(null, pos, SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS,
                1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
    }
}
