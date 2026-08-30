package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.util.DoorPairingUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.block.DoorBlock;
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
import java.util.Optional;
import java.util.Set;

/**
 * Opens 3-block emerald double doors for iron golems while pathing,
 * then closes them once the golem has passed.
 */
public class IronGolemInteractWithEmeraldDoorsGoal extends Goal {

    private static final double CLOSE_DISTANCE_SQ = 25.0;
    private static final int LOOK_AHEAD_NODES = 2;
    private static final double OPEN_DISTANCE_SQ = 6.25;

    private final IronGolem golem;
    private final Set<BlockPos> openedLowerDoorPositions = new HashSet<>();

    public IronGolemInteractWithEmeraldDoorsGoal(@Nonnull IronGolem golem) {
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
    public void tick() {
        Path path = this.golem.getNavigation().getPath();
        if (path == null || path.isDone()) {
            closeAllTracked();
            return;
        }

        int currentIndex = path.getNextNodeIndex();
        int endIndex = Math.min(currentIndex + LOOK_AHEAD_NODES, path.getNodeCount());

        for (int i = Math.max(0, currentIndex - 1); i < endIndex; i++) {
            Node node = path.getNode(i);
            tryOpenAt(node.asBlockPos());
            tryOpenAt(node.asBlockPos().above());
        }

        closePassedDoors();
    }

    @Override
    public void stop() {
        closeAllTracked();
    }

    private void tryOpenAt(@Nonnull BlockPos pos) {
        BlockState state = this.golem.level().getBlockState(pos);
        if (!state.is(ECAPBlocks.EMERALD_DOOR.get()) || state.getValue(DoorBlock.OPEN)) {
            return;
        }

        BlockPos lowerPos = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        if (this.golem.blockPosition().distSqr(lowerPos) > OPEN_DISTANCE_SQ) {
            return;
        }

        Optional<BlockPos> adjacentDoor = DoorPairingUtils.findPairedLowerDoor(this.golem.level(), lowerPos);
        if (adjacentDoor.isEmpty()) {
            return;
        }

        setDoorOpen(lowerPos, true);
        setDoorOpen(adjacentDoor.get(), true);
        this.openedLowerDoorPositions.add(lowerPos.immutable());
        this.openedLowerDoorPositions.add(adjacentDoor.get().immutable());
    }

    private void closePassedDoors() {
        Iterator<BlockPos> iterator = this.openedLowerDoorPositions.iterator();
        while (iterator.hasNext()) {
            BlockPos lowerPos = iterator.next();
            BlockState state = this.golem.level().getBlockState(lowerPos);
            if (!state.is(ECAPBlocks.EMERALD_DOOR.get()) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                iterator.remove();
                continue;
            }

            if (this.golem.blockPosition().distSqr(lowerPos) > CLOSE_DISTANCE_SQ) {
                setDoorOpen(lowerPos, false);
                iterator.remove();
            }
        }
    }

    private void closeAllTracked() {
        for (BlockPos lowerPos : this.openedLowerDoorPositions) {
            setDoorOpen(lowerPos, false);
        }
        this.openedLowerDoorPositions.clear();
    }

    private void setDoorOpen(@Nonnull BlockPos lowerPos, boolean open) {
        BlockState lowerState = this.golem.level().getBlockState(lowerPos);
        if (!lowerState.is(ECAPBlocks.EMERALD_DOOR.get()) || lowerState.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
            return;
        }

        if (lowerState.getValue(BlockStateProperties.OPEN) == open) {
            return;
        }

        DoorPairingUtils.setDoorOpen(this.golem.level(), lowerPos, open, 10);
        this.golem.level().playSound(null, lowerPos,
                open ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE,
                SoundSource.BLOCKS, 1.0F, this.golem.level().getRandom().nextFloat() * 0.1F + 0.9F);
        this.golem.level().gameEvent(this.golem, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, lowerPos);
    }
}
