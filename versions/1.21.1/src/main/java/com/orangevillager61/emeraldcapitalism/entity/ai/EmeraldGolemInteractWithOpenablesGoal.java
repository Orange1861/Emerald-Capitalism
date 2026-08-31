package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.util.DoorPairingUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Keeps openable blocks on the emerald golem's current path open while it moves,
 * then closes them after it has passed through.
 */
public class EmeraldGolemInteractWithOpenablesGoal extends Goal {

    private static final double CLOSE_DISTANCE_SQ = 16.0;
    private static final int LOOK_AHEAD_NODES = 2;
    private static final double OPEN_DISTANCE_SQ = 5.0;

    private final EmeraldGolem golem;
    private final Set<BlockPos> openedPositions = new HashSet<>();

    public EmeraldGolemInteractWithOpenablesGoal(@Nonnull EmeraldGolem golem) {
        this.golem = golem;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        Path path = this.golem.getNavigation().getPath();
        return path != null && !path.isDone();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        handleOpenables();
    }

    @Override
    public void tick() {
        handleOpenables();
    }

    @Override
    public void stop() {
        closeAllTracked();
    }

    private void handleOpenables() {
        Path path = this.golem.getNavigation().getPath();
        if (path == null || path.isDone()) {
            closeAllTracked();
            return;
        }

        int currentIndex = path.getNextNodeIndex();
        int endIndex = Math.min(currentIndex + LOOK_AHEAD_NODES, path.getNodeCount());

        for (int i = Math.max(0, currentIndex - 1); i < endIndex; i++) {
            Node node = path.getNode(i);
            BlockPos nodePos = node.asBlockPos();
            tryOpenAt(nodePos);
            tryOpenAt(nodePos.above());
        }

        closePassedOpenables();
    }

    private void tryOpenAt(@Nonnull BlockPos pos) {
        BlockState state = this.golem.level().getBlockState(pos);
        if (!isGolemOpenable(state)) {
            return;
        }

        if (this.golem.blockPosition().distSqr(pos) > OPEN_DISTANCE_SQ) {
            return;
        }

        if (!state.getValue(BlockStateProperties.OPEN)) {
            setOpenState(pos, state, true);
            playOpenSound(pos, state);
            this.golem.level().gameEvent(this.golem, GameEvent.BLOCK_OPEN, pos);
            this.openedPositions.add(pos.immutable());
        }
    }

    private void closePassedOpenables() {
        Iterator<BlockPos> iterator = this.openedPositions.iterator();
        while (iterator.hasNext()) {
            BlockPos openablePos = iterator.next();
            BlockState state = this.golem.level().getBlockState(openablePos);
            if (!isGolemOpenable(state)) {
                iterator.remove();
                continue;
            }

            if (this.golem.blockPosition().distSqr(openablePos) > CLOSE_DISTANCE_SQ) {
                closeAt(openablePos, state);
                iterator.remove();
            }
        }
    }

    private void closeAllTracked() {
        for (BlockPos pos : this.openedPositions) {
            closeAt(pos, this.golem.level().getBlockState(pos));
        }
        this.openedPositions.clear();
    }

    private void closeAt(@Nonnull BlockPos pos, @Nonnull BlockState state) {
        if (!isGolemOpenable(state) || !state.getValue(BlockStateProperties.OPEN)) {
            return;
        }

        setOpenState(pos, state, false);
        playCloseSound(pos, state);
        this.golem.level().gameEvent(this.golem, GameEvent.BLOCK_CLOSE, pos);
    }

    private boolean isGolemOpenable(@Nonnull BlockState state) {
        if (!state.hasProperty(BlockStateProperties.OPEN)) {
            return false;
        }
        if (state.getBlock() instanceof FenceGateBlock) {
            return true;
        }
        if (state.getBlock() instanceof TrapDoorBlock) {
            return state.is(BlockTags.WOODEN_TRAPDOORS);
        }
        if (!(state.getBlock() instanceof DoorBlock)) {
            return false;
        }

        return state.is(BlockTags.WOODEN_DOORS)
                || state.is(ECAPBlocks.EMERALD_DOOR.get())
                || state.is(ECAPBlocks.REGULAR_EMERALD_DOOR.get());
    }

    private void playOpenSound(@Nonnull BlockPos pos, @Nonnull BlockState state) {
        if (state.getBlock() instanceof FenceGateBlock) {
            this.golem.level().playSound(null, pos, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS,
                    1.0F, this.golem.level().getRandom().nextFloat() * 0.1F + 0.9F);
            return;
        }
        if (state.getBlock() instanceof TrapDoorBlock) {
            this.golem.level().playSound(null, pos, SoundEvents.WOODEN_TRAPDOOR_OPEN, SoundSource.BLOCKS,
                    1.0F, this.golem.level().getRandom().nextFloat() * 0.1F + 0.9F);
            return;
        }
        this.golem.level().playSound(null, pos, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS,
                1.0F, this.golem.level().getRandom().nextFloat() * 0.1F + 0.9F);
    }

    private void playCloseSound(@Nonnull BlockPos pos, @Nonnull BlockState state) {
        if (state.getBlock() instanceof FenceGateBlock) {
            this.golem.level().playSound(null, pos, SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS,
                    1.0F, this.golem.level().getRandom().nextFloat() * 0.1F + 0.9F);
            return;
        }
        if (state.getBlock() instanceof TrapDoorBlock) {
            this.golem.level().playSound(null, pos, SoundEvents.WOODEN_TRAPDOOR_CLOSE, SoundSource.BLOCKS,
                    1.0F, this.golem.level().getRandom().nextFloat() * 0.1F + 0.9F);
            return;
        }
        this.golem.level().playSound(null, pos, SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS,
                1.0F, this.golem.level().getRandom().nextFloat() * 0.1F + 0.9F);
    }

    private void setOpenState(@Nonnull BlockPos pos, @Nonnull BlockState state, boolean open) {
        if (state.getBlock() instanceof DoorBlock) {
            BlockPos lowerPos = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
            DoorPairingUtils.setDoorAndPairedOpen(this.golem.level(), lowerPos, open, 10);
            return;
        }

        this.golem.level().setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), 10);
    }
}
