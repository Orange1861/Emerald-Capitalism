package com.orangevillager61.emeraldcapitalism.mixin;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Maps supported gates and trapdoors to vanilla door path types for their users. */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeOpenablesMixin extends NodeEvaluator {

    /** Applies capability-specific types after the shared block-state cache has been read. */
    @Inject(method = "getPathType", at = @At("RETURN"), cancellable = true)
    private void emeraldcapitalism$openables(PathfindingContext context, int x, int y, int z,
                                                    CallbackInfoReturnable<PathType> cir) {
        boolean emeraldGolem = mob instanceof EmeraldGolem;
        boolean configuredGateUser = Config.enableFenceGateInteraction
                && (mob instanceof Villager || mob instanceof WanderingTrader);
        if (!configuredGateUser && !emeraldGolem) {
            return;
        }

        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = context.getBlockState(pos);
        if (state.getBlock() instanceof FenceGateBlock) {
            cir.setReturnValue(state.getValue(FenceGateBlock.OPEN)
                    ? PathType.DOOR_OPEN : PathType.DOOR_WOOD_CLOSED);
        } else if (emeraldGolem && state.getBlock() instanceof TrapDoorBlock
                && state.is(BlockTags.WOODEN_TRAPDOORS)) {
            cir.setReturnValue(state.getValue(TrapDoorBlock.OPEN)
                    ? PathType.DOOR_OPEN : PathType.DOOR_WOOD_CLOSED);
        }
    }
}
