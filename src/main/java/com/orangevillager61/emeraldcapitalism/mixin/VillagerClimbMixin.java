package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.entity.ai.VillagerLadderClaims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
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
            return;
        }

        Villager villager = (Villager) (Object) this;
        boolean onClimbable = villager.onClimbable();

        if (emeraldcapitalism$ladderExitTarget != null) {
            BlockPos target = emeraldcapitalism$ladderExitTarget;
            // The brain may recreate a walk target after the path reaches its
            // final ladder node. Keep the completed route stopped until the
            // entity has actually cleared the column.
            villager.getNavigation().stop();
            villager.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
            villager.getMoveControl().setWantedPosition(
                    target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 0.0D);
            double horizontalDistanceSq = villager.distanceToSqr(
                    target.getX() + 0.5D, villager.getY(), target.getZ() + 0.5D);
            if (horizontalDistanceSq < 1.0D && !villager.onClimbable()) {
                // Once the clear exit is close, correct the small collision
                // drift directly. Otherwise gravity can pull the villager
                // back onto the last rung before the exit block gains ground.
                villager.setPos(target.getX() + 0.5D,
                        Math.max(villager.getY(), target.getY()), target.getZ() + 0.5D);
                emeraldcapitalism$releaseLadder(villager);
                emeraldcapitalism$ladderExitTarget = null;
                emeraldcapitalism$ladderExitCooldownUntil = villager.tickCount + 20;
                villager.getNavigation().stop();
                villager.setDeltaMovement(0.0D, 0.0D, 0.0D);
                return;
            }
            if (horizontalDistanceSq < 1.0D) {
                // Do not clear the target merely because the entity is close:
                // it still overlaps the ladder and may be moved back by the
                // normal movement controller during this tick.
                villager.setPos(target.getX() + 0.5D,
                        Math.max(villager.getY(), target.getY()), target.getZ() + 0.5D);
                villager.setDeltaMovement(0.0D, 0.0D, 0.0D);
                return;
            }
            double pullX = (target.getX() + 0.5D - villager.getX()) * 0.25D;
            double pullZ = (target.getZ() + 0.5D - villager.getZ()) * 0.25D;
            double upwardPull = target.getY() > villager.getY() + 0.1D ? 0.2D : 0.0D;
            villager.setDeltaMovement(pullX, upwardPull, pullZ);
            return;
        }

        // Try to determine or update climb direction from path nodes at our XZ
        BlockPos pos = villager.blockPosition();
        PathNavigation navigation = villager.getNavigation();
        Path path = navigation.getPath();

        if (path != null && path.getNodeCount() > 0
                && villager.tickCount >= emeraldcapitalism$ladderExitCooldownUntil) {
            Node end = path.getEndNode();
            if (end.x == pos.getX() && end.z == pos.getZ() && end.y != pos.getY()) {
                // The next-node cursor can point behind the entity after a
                // same-column ladder node is marked reached. The path end is
                // stable and expresses the intended travel direction.
                emeraldcapitalism$climbDirection = end.y > pos.getY() ? 1 : -1;
            }
        }

        if (emeraldcapitalism$climbDirection == 0) {
            // On climbable but no direction: walking over, don't interfere
            if (!onClimbable) {
                emeraldcapitalism$releaseLadder(villager);
            }
            return;
        }

        // At the top of a ladder, the entity's block position can advance into
        // the air while its feet still overlap the last ladder block. Resolve
        // that ladder block before reading its facing or checking the next rung.
        BlockPos ladderPos = emeraldcapitalism$resolveNearbyLadder(villager, pos);
        if (ladderPos == null) {
            emeraldcapitalism$releaseLadder(villager);
            emeraldcapitalism$climbDirection = 0;
            return;
        }

        // Check ladder occupancy: wait if another villager is climbing this column
        if (!emeraldcapitalism$tryClaimLadder(villager, ladderPos)) {
            // Hold position without cancelling the route, so it can retry when the ladder is free.
            villager.setDeltaMovement(0, villager.getDeltaMovement().y, 0);
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
                    emeraldcapitalism$releaseLadder(villager);
                    emeraldcapitalism$climbDirection = 0;
                    villager.setDeltaMovement(0.0D, 0.0D, 0.0D);
                    villager.getNavigation().stop();
                    return;
                }
                // Top of ladder: leave the column and take one walking step onto the floor.
                // Without the horizontal exit, the completed path can be replaced by a
                // route back down before the villager has cleared the ladder.
                double exitPullX = pullX;
                double exitPullZ = pullZ;
                BlockState state = villager.level().getBlockState(ladderPos);
                if (state.hasProperty(LadderBlock.FACING)) {
                    Direction facing = state.getValue(LadderBlock.FACING);
                    // The villager's feet are already at the top ladder level.
                    // Use the adjacent block on that level as the walking exit;
                    // targeting one block higher can leave the entity suspended
                    // against the ladder and repeatedly reselected by navigation.
                    BlockPos walkExitPos = ladderPos.relative(facing);
                    boolean clearWalkExit = emeraldcapitalism$isClearForVillager(villager, walkExitPos);
                    if (clearWalkExit) {
                        emeraldcapitalism$ladderExitTarget = walkExitPos;
                        emeraldcapitalism$climbDirection = 0;
                        villager.getNavigation().stop();
                        exitPullX = (walkExitPos.getX() + 0.5D - villager.getX()) * 0.2D;
                        exitPullZ = (walkExitPos.getZ() + 0.5D - villager.getZ()) * 0.2D;
                    }
                }
                villager.setDeltaMovement(exitPullX, 0.08D, exitPullZ);
            } else {
                // Bottom of ladder: push off horizontally using ladder facing
                BlockState state = villager.level().getBlockState(ladderPos);
                if (state.hasProperty(LadderBlock.FACING)) {
                    Direction facing = state.getValue(LadderBlock.FACING);
                    BlockPos exitPos = ladderPos.relative(facing);
                    if (!emeraldcapitalism$isClearForVillager(villager, exitPos)) {
                        emeraldcapitalism$releaseLadder(villager);
                        emeraldcapitalism$climbDirection = 0;
                        villager.setDeltaMovement(0.0D, 0.0D, 0.0D);
                        villager.getNavigation().stop();
                        return;
                    }
                    emeraldcapitalism$ladderExitTarget = exitPos;
                    emeraldcapitalism$climbDirection = 0;
                    villager.setDeltaMovement(
                            facing.getStepX() * 0.2, 0.0, facing.getStepZ() * 0.2);
                }
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

    /**
     * Navigation can place the villager one block beside the ladder while its
     * feet still overlap the rung, especially at the top exit. Keep treating
     * that adjacent ladder as the active column so a small collision drift
     * cannot cancel the climb before the dismount logic runs.
     */
    @Unique
    private BlockPos emeraldcapitalism$resolveNearbyLadder(Villager villager, BlockPos pos) {
        if (villager.level().getBlockState(pos).is(BlockTags.CLIMBABLE)) {
            return pos;
        }

        // BlockPos floors the entity's Y coordinate. While climbing, the feet
        // can therefore overlap the rung immediately above or below that
        // floored position (most visibly at the top exit).
        BlockPos above = pos.above();
        if (villager.level().getBlockState(above).is(BlockTags.CLIMBABLE)) {
            return above;
        }
        BlockPos below = pos.below();
        if (villager.level().getBlockState(below).is(BlockTags.CLIMBABLE)) {
            return below;
        }

        for (int distance = 1; distance <= 2; distance++) {
            for (int dx = -distance; dx <= distance; dx++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != distance) {
                        continue;
                    }
                    BlockPos horizontal = pos.offset(dx, 0, dz);
                    if (villager.level().getBlockState(horizontal).is(BlockTags.CLIMBABLE)) {
                        return horizontal;
                    }
                }
            }
        }
        return null;
    }
}
