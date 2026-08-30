package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

/** Opens closed fence gates directly on a wandering trader's active path. */
public final class WanderingTraderFenceGateGoal extends Goal {

    private final WanderingTrader trader;

    public WanderingTraderFenceGateGoal(WanderingTrader trader) {
        this.trader = trader;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return Config.enableFenceGateInteraction && hasGateOnPath();
    }

    @Override
    public boolean canContinueToUse() {
        return Config.enableFenceGateInteraction && hasGateOnPath();
    }

    @Override
    public void tick() {
        Path path = trader.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return;
        }
        int start = Math.max(0, path.getNextNodeIndex() - 1);
        int end = Math.min(path.getNodeCount(), start + 3);
        for (int index = start; index < end; index++) {
            Node node = path.getNode(index);
            openGate(BlockPos.containing(node.x, node.y, node.z));
        }
    }

    private boolean hasGateOnPath() {
        Path path = trader.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return false;
        }
        int start = Math.max(0, path.getNextNodeIndex() - 1);
        int end = Math.min(path.getNodeCount(), start + 3);
        for (int index = start; index < end; index++) {
            BlockState state = trader.level().getBlockState(BlockPos.containing(
                    path.getNode(index).x, path.getNode(index).y, path.getNode(index).z));
            if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)
                    && trader.blockPosition().distSqr(BlockPos.containing(
                    path.getNode(index).x, path.getNode(index).y, path.getNode(index).z)) <= 9.0D) {
                return true;
            }
        }
        return false;
    }

    private void openGate(BlockPos pos) {
        BlockState state = trader.level().getBlockState(pos);
        if (!(state.getBlock() instanceof FenceGateBlock)
                || state.getValue(FenceGateBlock.OPEN)
                || trader.blockPosition().distSqr(pos) > 9.0D) {
            return;
        }
        trader.level().setBlock(pos, state.setValue(FenceGateBlock.OPEN, true), 10);
        trader.level().playSound(null, pos, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS,
                1.0F, trader.level().getRandom().nextFloat() * 0.1F + 0.9F);
    }
}
