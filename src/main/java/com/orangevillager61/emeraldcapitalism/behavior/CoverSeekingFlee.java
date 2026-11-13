package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Wraps one of vanilla's panic movers ({@code SetWalkTargetAwayFrom}) so that a villager facing a
 * ranged hostile first tries to get out of its sight, and otherwise flees exactly as vanilla does.
 *
 * <p>Caller: {@link VillagerGoalPackageIntegration#addPanicBehaviors}, which wraps the panic
 * package's two walk-away movers (from the nearest hostile and from the attacker). Vanilla's
 * mover writes a walk target only while none exists; this wrapper follows the same rule, so it
 * never fights another behavior for {@code WALK_TARGET}. It runs only inside the PANIC activity
 * and publishes one target at a time through {@link BrainMovementIntent}; vanilla
 * {@code MoveToTargetSink} walks it.
 *
 * <p>Cover is a standable block, reachable by walking through open space and doors within a short
 * flood fill, from which a ray to the hostile's eye is blocked. If no such block exists (a walled
 * cell with a gap, for example) the vanilla mover runs unchanged.
 */
public final class CoverSeekingFlee implements BehaviorControl<Villager> {

    /** Hostiles that hurt from a distance, so being out of sight matters more than being far away. */
    private static final Set<EntityType<?>> RANGED_HOSTILES = Set.of(EntityType.PILLAGER, EntityType.ILLUSIONER);

    private static final int SEARCH_DEPTH = 10;
    private static final int MAX_SEARCH_NODES = 400;
    private static final double EYE_HEIGHT = 1.62D;
    private static final long REUSE_COVER_TICKS = 20L;
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final MemoryModuleType<? extends LivingEntity> threatMemory;
    private final BehaviorControl<Villager> vanilla;
    private final float speedModifier;
    private final BrainMovementIntent intent =
            new BrainMovementIntent("emeraldcapitalism:ranged_cover", true, 100L);
    @Nullable
    private BlockPos cover;
    private long coverChosenAt = Long.MIN_VALUE;

    /**
     * @param threatMemory the memory vanilla's mover flees from
     * @param vanilla      vanilla's own mover, run whenever cover is not being sought
     * @param speedModifier the speed vanilla's mover uses, so cover is reached at panic speed
     */
    public CoverSeekingFlee(MemoryModuleType<? extends LivingEntity> threatMemory,
                            BehaviorControl<Villager> vanilla, float speedModifier) {
        this.threatMemory = threatMemory;
        this.vanilla = vanilla;
        this.speedModifier = speedModifier;
    }

    @Override
    public Behavior.Status getStatus() {
        return vanilla.getStatus();
    }

    @Override
    public boolean tryStart(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        Brain<Villager> brain = villager.getBrain();
        LivingEntity threat = brain.getMemory(threatMemory).orElse(null);
        if (!Config.enableRangedHostileCover || threat == null || !RANGED_HOSTILES.contains(threat.getType())
                || !threat.isAlive() || !villager.hasLineOfSight(threat)) {
            return vanilla.tryStart(level, villager, gameTime);
        }
        // Like vanilla's mover, never replace a walk target some other behavior published.
        Optional<?> existing = brain.getMemory(MemoryModuleType.WALK_TARGET);
        if (existing.isPresent() && !intent.ownsWalkTarget(villager)) {
            return false;
        }
        if (cover == null || gameTime - coverChosenAt >= REUSE_COVER_TICKS || !isStillHidden(level, threat)) {
            cover = findCover(level, villager, threat);
            coverChosenAt = gameTime;
        }
        if (cover == null) {
            intent.release(villager);
            return vanilla.tryStart(level, villager, gameTime);
        }
        intent.walkTo(villager, cover, speedModifier, 0, gameTime);
        return true;
    }

    @Override
    public void tickOrStop(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        vanilla.tickOrStop(level, villager, gameTime);
    }

    @Override
    public void doStop(@Nonnull ServerLevel level, @Nonnull Villager villager, long gameTime) {
        intent.release(villager);
        cover = null;
        vanilla.doStop(level, villager, gameTime);
    }

    @Override
    public String debugString() {
        return "CoverSeekingFlee(" + vanilla.debugString() + ")";
    }

    private boolean isStillHidden(ServerLevel level, LivingEntity threat) {
        return cover != null && !seesFrom(level, threat, cover);
    }

    /** The nearest reachable block hidden from {@code threat}, or null if there is none. */
    @Nullable
    private static BlockPos findCover(ServerLevel level, Villager villager, LivingEntity threat) {
        BlockPos start = villager.blockPosition();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        depths.add(0);
        seen.add(start);
        BlockPos best = null;
        double bestThreatDistance = -1.0D;
        int bestDepth = Integer.MAX_VALUE;
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_SEARCH_NODES) {
            BlockPos pos = queue.poll();
            int depth = depths.poll();
            visited++;
            if (depth > bestDepth) {
                break;
            }
            if (depth > 0 && !seesFrom(level, threat, pos)) {
                double away = pos.distSqr(threat.blockPosition());
                if (depth < bestDepth || away > bestThreatDistance) {
                    best = pos;
                    bestDepth = depth;
                    bestThreatDistance = away;
                }
            }
            if (depth >= SEARCH_DEPTH) {
                continue;
            }
            for (int[] step : STEPS) {
                for (int dy = 1; dy >= -1; dy--) {
                    BlockPos next = pos.offset(step[0], dy, step[1]);
                    if (!seen.contains(next) && canStandAndEnter(level, pos, next, dy)) {
                        seen.add(next);
                        queue.add(next);
                        depths.add(depth + 1);
                        break;
                    }
                }
            }
        }
        return best;
    }

    private static boolean seesFrom(ServerLevel level, LivingEntity threat, BlockPos pos) {
        Vec3 from = threat.getEyePosition();
        Vec3 to = new Vec3(pos.getX() + 0.5D, pos.getY() + EYE_HEIGHT, pos.getZ() + 0.5D);
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, threat))
                .getType() == HitResult.Type.MISS;
    }

    private static boolean canStandAndEnter(ServerLevel level, BlockPos from, BlockPos to, int dy) {
        if (!passable(level, to) || !passable(level, to.above())) {
            return false;
        }
        BlockPos floor = to.below();
        BlockState floorState = level.getBlockState(floor);
        if (floorState.getCollisionShape(level, floor).isEmpty() || !floorState.getFluidState().isEmpty()) {
            return false;
        }
        // Stepping up needs headroom over the block being left; stepping down needs the corridor open.
        return dy > 0 ? passable(level, from.above(2)) : dy == 0 || passable(level, from.above());
    }

    private static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty()
                || state.is(BlockTags.DOORS) || state.is(BlockTags.FENCE_GATES);
    }
}
