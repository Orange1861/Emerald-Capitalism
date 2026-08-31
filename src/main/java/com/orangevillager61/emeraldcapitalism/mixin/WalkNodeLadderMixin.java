package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds vertical nodes through climbable columns for villagers, traders, and emerald golems. */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeLadderMixin extends NodeEvaluator {
    @Inject(method = "getPathTypeOfMob", at = @At("RETURN"), cancellable = true)
    private void emeraldcapitalism$climbableMob(PathfindingContext context, int x, int y, int z,
                                                       Mob mob, CallbackInfoReturnable<PathType> cir) {
        if (!emeraldcapitalism$supportsLadderPathing(mob)) {
            return;
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (context.getBlockState(pos).is(BlockTags.CLIMBABLE)
                || context.getBlockState(pos.below()).is(BlockTags.CLIMBABLE)) {
            PathType vanilla = cir.getReturnValue();
            if (vanilla == PathType.BLOCKED || vanilla == PathType.OPEN
                    || mob.getPathfindingMalus(vanilla) < 0.0F) {
                cir.setReturnValue(PathType.WALKABLE);
            }
        }
    }

    @Inject(method = "getNeighbors", at = @At("RETURN"), cancellable = true)
    private void emeraldcapitalism$verticalNeighbors(Node[] nodes, Node node,
                                                            CallbackInfoReturnable<Integer> cir) {
        if (!emeraldcapitalism$supportsLadderPathing(mob) || currentContext == null) {
            return;
        }
        int count = cir.getReturnValue();
        BlockPos pos = new BlockPos(node.x, node.y, node.z);
        boolean current = currentContext.getBlockState(pos).is(BlockTags.CLIMBABLE);
        boolean below = currentContext.getBlockState(pos.below()).is(BlockTags.CLIMBABLE);
        if (current) {
            count = addVerticalNode(nodes, count, node.x, node.y + 1, node.z);
            count = addVerticalNode(nodes, count, node.x, node.y - 1, node.z);
        } else if (below) {
            count = addVerticalNode(nodes, count, node.x, node.y - 1, node.z);
        }
        cir.setReturnValue(count);
    }

    @Unique
    private int addVerticalNode(Node[] nodes, int count, int x, int y, int z) {
        if (count >= nodes.length || !isSafeVerticalNode(x, y, z)) {
            return count;
        }
        Node vertical = getNode(x, y, z);
        if (!vertical.closed) {
            vertical.type = PathType.OPEN;
            vertical.costMalus = 0.0F;
            nodes[count++] = vertical;
        }
        return count;
    }

    @Unique
    private boolean isSafeVerticalNode(int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        BlockState feetState = currentContext.getBlockState(feet);
        BlockState headState = currentContext.getBlockState(feet.above());
        return (feetState.is(BlockTags.CLIMBABLE)
                || feetState.getCollisionShape(currentContext.level(), feet).isEmpty())
                && (headState.is(BlockTags.CLIMBABLE)
                || headState.getCollisionShape(currentContext.level(), feet.above()).isEmpty());
    }

    @Unique
    private static boolean emeraldcapitalism$supportsLadderPathing(Mob mob) {
        return Config.enableLadderTraversal && (mob instanceof Villager
                || mob instanceof WanderingTrader || mob instanceof EmeraldGolem);
    }
}
