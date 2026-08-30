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
            return;
        }

        Villager villager = (Villager) (Object) this;
        boolean onClimbable = villager.onClimbable();

        if (!onClimbable) {
            emeraldcapitalism$releaseLadder(villager);
            emeraldcapitalism$climbDirection = 0;
            return;
        }

        // Try to determine or update climb direction from path nodes at our XZ
        BlockPos pos = villager.blockPosition();
        PathNavigation navigation = villager.getNavigation();
        Path path = navigation.getPath();

        if (path != null && path.getNodeCount() > 0) {
            int nextIdx = Math.max(0, path.getNextNodeIndex() - 1);
            for (int i = nextIdx; i < path.getNodeCount(); i++) {
                Node n = path.getNode(i);
                if (n.x == pos.getX() && n.z == pos.getZ() && n.y != pos.getY()) {
                    emeraldcapitalism$climbDirection = n.y > pos.getY() ? 1 : -1;
                    break;
                }
            }
        }

        if (emeraldcapitalism$climbDirection == 0) {
            // On climbable but no direction: walking over, don't interfere
            return;
        }

        // Check ladder occupancy: wait if another villager is climbing this column
        if (!emeraldcapitalism$tryClaimLadder(villager, pos)) {
            // Stop the villager from moving into the occupied ladder
            villager.setDeltaMovement(0, villager.getDeltaMovement().y, 0);
            villager.getNavigation().stop();
            return;
        }

        // Check whether climbing can continue.
        boolean canContinue;
        if (emeraldcapitalism$climbDirection == 1) {
            canContinue = villager.level().getBlockState(pos.above()).is(BlockTags.CLIMBABLE);
        } else {
            canContinue = villager.level().getBlockState(pos.below()).is(BlockTags.CLIMBABLE);
        }

        // Center on the ladder block
        double centerX = pos.getX() + 0.5;
        double centerZ = pos.getZ() + 0.5;
        double pullX = (centerX - villager.getX()) * 0.2;
        double pullZ = (centerZ - villager.getZ()) * 0.2;

        if (!canContinue) {
            // Reached end of ladder: help dismount
            if (emeraldcapitalism$climbDirection == 1) {
                BlockPos exitPos = pos.above();
                if (!emeraldcapitalism$isClearForVillager(villager, exitPos)) {
                    emeraldcapitalism$releaseLadder(villager);
                    emeraldcapitalism$climbDirection = 0;
                    villager.setDeltaMovement(0.0D, 0.0D, 0.0D);
                    villager.getNavigation().stop();
                    return;
                }
                // Top of ladder: boost up to clear onto the floor above
                villager.setDeltaMovement(pullX, 0.2, pullZ);
            } else {
                // Bottom of ladder: push off horizontally using ladder facing
                BlockState state = villager.level().getBlockState(pos);
                if (state.hasProperty(LadderBlock.FACING)) {
                    Direction facing = state.getValue(LadderBlock.FACING);
                    BlockPos exitPos = pos.relative(facing);
                    if (!emeraldcapitalism$isClearForVillager(villager, exitPos)) {
                        emeraldcapitalism$releaseLadder(villager);
                        emeraldcapitalism$climbDirection = 0;
                        villager.setDeltaMovement(0.0D, 0.0D, 0.0D);
                        villager.getNavigation().stop();
                        return;
                    }
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
}
