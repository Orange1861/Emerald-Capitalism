package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.entity.ai.VillagerLadderClaims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for {@link Villager} to enable ladder climbing and descent.
 *
 * <p>Villagers already recognize climbable blocks via {@code LivingEntity.onClimbable()},
 * but they lack the player-like input to actually move up or down ladders.
 * This mixin injects vertical velocity when the villager is on a climbable
 * block and their path requires vertical movement at this position.</p>
 *
 * <p>Climb direction is determined from path nodes at the villager's current
 * XZ column. Once set, the direction persists even after the path is cleared
 * (which happens because same-XZ ladder nodes are "reached" instantly by the
 * navigation tick). Direction is cleared when the villager steps off the
 * climbable block.</p>
 *
 * <p>A global occupancy map ensures only one villager climbs a given ladder
 * column at a time. Others wait at the base/top until the ladder is free.</p>
 */
@Mixin(Villager.class)
public abstract class VillagerClimbMixin {

    /** -1 = descending, 0 = not climbing, 1 = ascending. */
    @Unique
    private int emeraldcapitalism$climbDirection = 0;

    /** Exact claim key, so horizontal dismount drift cannot leave an old entry behind. */
    @Unique
    private VillagerLadderClaims.LadderColumn emeraldcapitalism$claimedLadder;

    /** The clear floor block this villager is moving toward after ladder travel ends. */
    @Unique
    private BlockPos emeraldcapitalism$ladderExitTarget;

    /** Prevents a completed dismount from being immediately reselected by a new brain path. */
    @Unique
    private int emeraldcapitalism$ladderExitCooldownUntil;

    /** The route-derived ladder column; nearby unrelated ladders are never substituted. */
    @Unique
    private BlockPos emeraldcapitalism$activeLadderColumn;

    @Unique
    private int emeraldcapitalism$ladderExitStartedTick;

    @Unique
    private double emeraldcapitalism$ladderExitLastDistanceSq = Double.MAX_VALUE;

    @Unique
    private int emeraldcapitalism$ladderExitStagnantTicks;

    @Unique
    private double emeraldcapitalism$lastLadderProgressY;

    private static final int EMERALDCAPITALISM_MAX_LADDER_EXIT_TICKS = 40;
    private static final int EMERALDCAPITALISM_MAX_LADDER_EXIT_STAGNANT_TICKS = 10;
    private static final double EMERALDCAPITALISM_EXIT_PROGRESS_SQ = 0.0025D;

    /**
     * Attempts to claim a ladder column for this villager.
     * @return true if the column is now claimed by this villager
     */
    @Unique
    private boolean emeraldcapitalism$tryClaimLadder(Villager villager, BlockPos pos) {
        VillagerLadderClaims.LadderColumn column = VillagerLadderClaims.column(villager, pos);
        if (emeraldcapitalism$claimedLadder != null
                && !emeraldcapitalism$claimedLadder.equals(column)) {
            VillagerLadderClaims.release(villager, emeraldcapitalism$claimedLadder);
            emeraldcapitalism$claimedLadder = null;
        }
        if (!VillagerLadderClaims.tryClaim(villager, column)) {
            return false;
        }
        emeraldcapitalism$claimedLadder = column;
        emeraldcapitalism$activeLadderColumn = pos.immutable();
        return true;
    }

    /** Releases this villager's current ladder claim. */
    @Unique
    private void emeraldcapitalism$releaseLadder(Villager villager) {
        VillagerLadderClaims.release(villager, emeraldcapitalism$claimedLadder);
        emeraldcapitalism$claimedLadder = null;
    }

    /**
     * After the brain-based AI tick, checks if the villager is on a climbable
     * block and adjusts vertical velocity based on the path's vertical nodes
     * at this XZ position.
     */
    @SuppressWarnings("DataFlowIssue")
    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void onCustomServerAiStep(CallbackInfo ci) {
        if (!com.orangevillager61.emeraldcapitalism.Config.enableLadderTraversal) {
            Villager villager = (Villager) (Object) this;
            emeraldcapitalism$releaseLadder(villager);
            emeraldcapitalism$climbDirection = 0;
            emeraldcapitalism$ladderExitTarget = null;
            emeraldcapitalism$activeLadderColumn = null;
            return;
        }

        Villager villager = (Villager) (Object) this;
        boolean onClimbable = villager.onClimbable();

        if (emeraldcapitalism$ladderExitTarget != null) {
            BlockPos target = emeraldcapitalism$ladderExitTarget;
            if (!emeraldcapitalism$isSafeExitForVillager(villager, target)) {
                emeraldcapitalism$recoverFromLadderExit(villager);
                return;
            }

            double distanceSq = emeraldcapitalism$distanceToExitSq(villager, target);
            if (distanceSq + EMERALDCAPITALISM_EXIT_PROGRESS_SQ
                    < emeraldcapitalism$ladderExitLastDistanceSq) {
                emeraldcapitalism$ladderExitStagnantTicks = 0;
            } else {
                emeraldcapitalism$ladderExitStagnantTicks++;
            }
            emeraldcapitalism$ladderExitLastDistanceSq = distanceSq;
            if (villager.tickCount - emeraldcapitalism$ladderExitStartedTick
                    >= EMERALDCAPITALISM_MAX_LADDER_EXIT_TICKS
                    || emeraldcapitalism$ladderExitStagnantTicks
                    >= EMERALDCAPITALISM_MAX_LADDER_EXIT_STAGNANT_TICKS) {
                emeraldcapitalism$recoverFromLadderExit(villager);
                return;
            }

            // The brain may recreate a walk target after the path reaches its
            // final ladder node. Keep the completed route stopped until the
            // entity has actually cleared the column, but leave WALK_TARGET
            // intact so recovery can let navigation retry.
            villager.getNavigation().stop();
            villager.getMoveControl().setWantedPosition(
                    target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D);
            if (distanceSq <= 1.25D
                    && villager.tickCount > emeraldcapitalism$ladderExitStartedTick) {
                // Once the clear exit is close, correct the small collision
                // drift directly. Otherwise gravity can pull the villager
                // back onto the last rung before the exit block gains ground.
                villager.setPos(target.getX() + 0.5D,
                        target.getY(), target.getZ() + 0.5D);
                if (!emeraldcapitalism$isSafeExitForVillager(villager, target)) {
                    emeraldcapitalism$recoverFromLadderExit(villager);
                    return;
                }
                emeraldcapitalism$releaseLadder(villager);
                emeraldcapitalism$ladderExitTarget = null;
                emeraldcapitalism$activeLadderColumn = null;
                emeraldcapitalism$climbDirection = 0;
                emeraldcapitalism$ladderExitCooldownUntil = villager.tickCount + 20;
                emeraldcapitalism$resetLadderExitProgress();
                villager.getNavigation().stop();
                villager.setDeltaMovement(0.0D, 0.0D, 0.0D);
                villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                return;
            }
            double pullX = (target.getX() + 0.5D - villager.getX()) * 0.25D;
            double pullZ = (target.getZ() + 0.5D - villager.getZ()) * 0.25D;
            double upwardPull = target.getY() > villager.getY() + 0.1D ? 0.2D : 0.0D;
            villager.setDeltaMovement(pullX, upwardPull, pullZ);
            return;
        }

        // Determine direction from the next vertical route segment. The final
        // node is often the landing, so it cannot describe the direction of a
        // ladder in the middle of the route.
        BlockPos pos = villager.blockPosition();
        PathNavigation navigation = villager.getNavigation();
        Path path = navigation.getPath();

        if (path != null && path.getNodeCount() > 0
                && villager.tickCount >= emeraldcapitalism$ladderExitCooldownUntil) {
            int routeDirection = emeraldcapitalism$scanPathForLadderDirection(villager, pos, path);
            if (routeDirection != 0) {
                emeraldcapitalism$climbDirection = routeDirection;
            }
        } else if (path == null && emeraldcapitalism$climbDirection != 0) {
            // Navigation can clear a same-column path node as soon as the
            // villager enters the ladder. Keep the route direction only while
            // the villager is still on that exact ladder and a walk target is
            // still present; a cancelled route releases it immediately.
            boolean routeStillRequested = villager.getBrain()
                    .hasMemoryValue(MemoryModuleType.WALK_TARGET);
            boolean movedSinceRouteObservation = Math.abs(villager.getY()
                    - emeraldcapitalism$lastLadderProgressY) >= 0.05D;
            if (!routeStillRequested || !onClimbable
                    || !emeraldcapitalism$isOnActiveLadderColumn(villager, pos)) {
                emeraldcapitalism$clearClimbRoute(villager);
            } else if (movedSinceRouteObservation) {
                emeraldcapitalism$lastLadderProgressY = villager.getY();
            }
        }

        if (emeraldcapitalism$climbDirection == 0) {
            // On climbable but no route direction: walking over, don't
            // interfere, and do not retain a claim from an aborted dismount.
            if (!onClimbable || !villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
                emeraldcapitalism$releaseLadder(villager);
                emeraldcapitalism$activeLadderColumn = null;
            }
            return;
        }

        // At the top of a ladder, the entity's block position can advance into
        // the air while its feet still overlap the last ladder block. Resolve
        // that ladder block before reading its facing or checking the next rung.
        BlockPos ladderPos = emeraldcapitalism$resolveNearbyLadder(villager, pos);
        if (ladderPos == null) {
            emeraldcapitalism$clearClimbRoute(villager);
            return;
        }

        // Check ladder occupancy: wait if another villager is climbing this column
        if (!emeraldcapitalism$tryClaimLadder(villager, ladderPos)) {
            // Hold position without cancelling the route, so it can retry when the ladder is free.
            villager.setDeltaMovement(0, 0, 0);
            return;
        }

        // Check whether climbing can continue.
        boolean canContinue;
        if (emeraldcapitalism$climbDirection == 1) {
            canContinue = villager.level().getBlockState(ladderPos.above()).is(BlockTags.CLIMBABLE);
        } else {
            canContinue = villager.level().getBlockState(ladderPos.below()).is(BlockTags.CLIMBABLE);
        }

        // Center on the ladder block
        double centerX = ladderPos.getX() + 0.5;
        double centerZ = ladderPos.getZ() + 0.5;
        double pullX = (centerX - villager.getX()) * 0.2;
        double pullZ = (centerZ - villager.getZ()) * 0.2;

        if (!canContinue) {
            // Reached end of ladder: help dismount
            if (emeraldcapitalism$climbDirection == 1) {
                BlockPos exitPos = ladderPos.above();
                boolean clearVerticalExit = emeraldcapitalism$isClearForVillager(villager, exitPos);
                if (!clearVerticalExit) {
                    emeraldcapitalism$recoverFromLadderExit(villager);
                    return;
                }
                // Top of ladder: leave the column and take one walking step onto the floor.
                // Without the horizontal exit, the completed path can be replaced by a
                // route back down before the villager has cleared the ladder.
                BlockState state = villager.level().getBlockState(ladderPos);
                BlockPos walkExitPos = state.hasProperty(LadderBlock.FACING)
                        ? emeraldcapitalism$selectValidatedExit(
                        villager, ladderPos, state.getValue(LadderBlock.FACING))
                        : null;
                if (walkExitPos == null) {
                    emeraldcapitalism$recoverFromLadderExit(villager);
                    return;
                }
                emeraldcapitalism$beginLadderExit(villager, walkExitPos);
                villager.setDeltaMovement(
                        (walkExitPos.getX() + 0.5D - villager.getX()) * 0.2D,
                        0.08D,
                        (walkExitPos.getZ() + 0.5D - villager.getZ()) * 0.2D);
            } else {
                // Bottom of ladder: push off horizontally using ladder facing
                BlockState state = villager.level().getBlockState(ladderPos);
                BlockPos exitPos = state.hasProperty(LadderBlock.FACING)
                        ? emeraldcapitalism$selectValidatedExit(
                        villager, ladderPos, state.getValue(LadderBlock.FACING))
                        : null;
                if (exitPos == null) {
                    emeraldcapitalism$recoverFromLadderExit(villager);
                    return;
                }
                emeraldcapitalism$beginLadderExit(villager, exitPos);
                villager.setDeltaMovement(
                        (exitPos.getX() + 0.5D - villager.getX()) * 0.2D,
                        0.0D,
                        (exitPos.getZ() + 0.5D - villager.getZ()) * 0.2D);
            }
            return;
        }

        // Continue climbing
        if (emeraldcapitalism$climbDirection == 1) {
            villager.setDeltaMovement(pullX, 0.2, pullZ);
        } else {
            villager.setDeltaMovement(pullX, -0.15, pullZ);
        }
    }

    @Unique
    private static boolean emeraldcapitalism$isClearForVillager(Villager villager, BlockPos feetPos) {
        return villager.level().getBlockState(feetPos).getCollisionShape(villager.level(), feetPos).isEmpty()
                && villager.level().getBlockState(feetPos.above())
                .getCollisionShape(villager.level(), feetPos.above()).isEmpty();
    }

    @Unique
    private static boolean emeraldcapitalism$isSafeExitForVillager(Villager villager, BlockPos feetPos) {
        if (!emeraldcapitalism$isClearForVillager(villager, feetPos)) {
            return false;
        }

        BlockPos supportPos = feetPos.below();
        BlockState support = villager.level().getBlockState(supportPos);
        if (!support.isFaceSturdy(villager.level(), supportPos, Direction.UP)
                && !support.is(BlockTags.CLIMBABLE)) {
            return false;
        }

        AABB targetBox = villager.getBoundingBox().move(
                feetPos.getX() + 0.5D - villager.getX(),
                feetPos.getY() - villager.getBoundingBox().minY,
                feetPos.getZ() + 0.5D - villager.getZ());
        return villager.level().noCollision(villager, targetBox);
    }

    @Unique
    private static double emeraldcapitalism$distanceToExitSq(Villager villager, BlockPos target) {
        double dx = target.getX() + 0.5D - villager.getX();
        double dy = target.getY() - villager.getY();
        double dz = target.getZ() + 0.5D - villager.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    @Unique
    private void emeraldcapitalism$beginLadderExit(Villager villager, BlockPos target) {
        emeraldcapitalism$ladderExitTarget = target.immutable();
        emeraldcapitalism$climbDirection = 0;
        emeraldcapitalism$ladderExitStartedTick = villager.tickCount;
        emeraldcapitalism$ladderExitLastDistanceSq = emeraldcapitalism$distanceToExitSq(villager, target);
        emeraldcapitalism$ladderExitStagnantTicks = 0;
    }

    @Unique
    private void emeraldcapitalism$resetLadderExitProgress() {
        emeraldcapitalism$ladderExitStartedTick = 0;
        emeraldcapitalism$ladderExitLastDistanceSq = Double.MAX_VALUE;
        emeraldcapitalism$ladderExitStagnantTicks = 0;
    }

    /** Releases the claim while leaving the brain walk target available for a retry. */
    @Unique
    private void emeraldcapitalism$recoverFromLadderExit(Villager villager) {
        emeraldcapitalism$releaseLadder(villager);
        emeraldcapitalism$ladderExitTarget = null;
        emeraldcapitalism$activeLadderColumn = null;
        emeraldcapitalism$climbDirection = 0;
        emeraldcapitalism$resetLadderExitProgress();
        villager.getNavigation().stop();
        villager.setDeltaMovement(0.0D, 0.0D, 0.0D);
    }

    @Unique
    private void emeraldcapitalism$clearClimbRoute(Villager villager) {
        emeraldcapitalism$releaseLadder(villager);
        emeraldcapitalism$climbDirection = 0;
        emeraldcapitalism$activeLadderColumn = null;
    }

    @Unique
    private BlockPos emeraldcapitalism$selectValidatedExit(Villager villager, BlockPos ladderPos,
                                                              Direction preferred) {
        Direction[] candidates = {
                preferred,
                preferred.getOpposite(),
                preferred.getClockWise(),
                preferred.getCounterClockWise()
        };
        for (Direction direction : candidates) {
            BlockPos candidate = ladderPos.relative(direction);
            if (emeraldcapitalism$isSafeExitForVillager(villager, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @Unique
    private int emeraldcapitalism$scanPathForLadderDirection(Villager villager, BlockPos position,
                                                               Path path) {
        int start = Math.max(0, path.getNextNodeIndex() - 1);
        int end = path.getNodeCount();
        int columnX = emeraldcapitalism$activeLadderColumn == null
                ? position.getX() : emeraldcapitalism$activeLadderColumn.getX();
        int columnZ = emeraldcapitalism$activeLadderColumn == null
                ? position.getZ() : emeraldcapitalism$activeLadderColumn.getZ();

        for (int index = start; index + 1 < end; index++) {
            Node from = path.getNode(index);
            Node to = path.getNode(index + 1);
            if (from.x != to.x || from.z != to.z || from.y == to.y
                    || from.x != columnX || from.z != columnZ
                    || !emeraldcapitalism$hasClimbableSegment(villager, from, to)) {
                continue;
            }
            emeraldcapitalism$activeLadderColumn = new BlockPos(from.x, position.getY(), from.z);
            emeraldcapitalism$lastLadderProgressY = villager.getY();
            return to.y > from.y ? 1 : -1;
        }

        // A path can expose only the next ladder node after the previous
        // same-column node was consumed. It is still a route signal when that
        // node belongs to the active ladder column.
        for (int index = start; index < end; index++) {
            Node node = path.getNode(index);
            if (node.x == columnX && node.z == columnZ && node.y != position.getY()
                    && villager.level().getBlockState(new BlockPos(node.x, node.y, node.z))
                    .is(BlockTags.CLIMBABLE)) {
                emeraldcapitalism$activeLadderColumn = new BlockPos(node.x, position.getY(), node.z);
                emeraldcapitalism$lastLadderProgressY = villager.getY();
                return node.y > position.getY() ? 1 : -1;
            }
        }
        return 0;
    }

    @Unique
    private static boolean emeraldcapitalism$hasClimbableSegment(Villager villager, Node from, Node to) {
        int minY = Math.min(from.y, to.y);
        int maxY = Math.max(from.y, to.y);
        for (int y = minY; y <= maxY; y++) {
            if (villager.level().getBlockState(new BlockPos(from.x, y, from.z))
                    .is(BlockTags.CLIMBABLE)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private boolean emeraldcapitalism$isOnActiveLadderColumn(Villager villager, BlockPos position) {
        if (emeraldcapitalism$activeLadderColumn == null) {
            return false;
        }
        if (position.getX() != emeraldcapitalism$activeLadderColumn.getX()
                || position.getZ() != emeraldcapitalism$activeLadderColumn.getZ()) {
            return false;
        }
        for (int y = position.getY() - 2; y <= position.getY() + 2; y++) {
            if (villager.level().getBlockState(new BlockPos(
                    emeraldcapitalism$activeLadderColumn.getX(), y,
                    emeraldcapitalism$activeLadderColumn.getZ())).is(BlockTags.CLIMBABLE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Navigation can place the villager one block beside the ladder while its
     * feet still overlap the rung, especially at the top exit. Keep treating
     * that adjacent ladder as the active column so a small collision drift
     * cannot cancel the climb before the dismount logic runs.
     */
    @Unique
    private BlockPos emeraldcapitalism$resolveNearbyLadder(Villager villager, BlockPos pos) {
        int columnX = emeraldcapitalism$activeLadderColumn == null
                ? pos.getX() : emeraldcapitalism$activeLadderColumn.getX();
        int columnZ = emeraldcapitalism$activeLadderColumn == null
                ? pos.getZ() : emeraldcapitalism$activeLadderColumn.getZ();
        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (int y = pos.getY() - 2; y <= pos.getY() + 2; y++) {
            BlockPos candidate = new BlockPos(columnX, y, columnZ);
            if (villager.level().getBlockState(candidate).is(BlockTags.CLIMBABLE)) {
                double distance = Math.abs(y - villager.getY());
                if (distance < closestDistance) {
                    closest = candidate;
                    closestDistance = distance;
                }
            }
        }
        return closest;
    }
}
