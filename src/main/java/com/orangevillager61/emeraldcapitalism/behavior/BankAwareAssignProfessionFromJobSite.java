package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.AssignProfessionFromJobSite;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Delegates profession assignment to vanilla after enforcing the bank's work
 * side as the assignment point.
 */
public final class BankAwareAssignProfessionFromJobSite implements BehaviorControl<Villager> {

    private final BehaviorControl<Villager> delegate = AssignProfessionFromJobSite.create();

    @Override
    public Behavior.Status getStatus() {
        return delegate.getStatus();
    }

    @Override
    public boolean tryStart(ServerLevel level, Villager villager, long gameTime) {
        if (!atBankerWorkSide(level, villager)) {
            return false;
        }
        return delegate.tryStart(level, villager, gameTime);
    }

    @Override
    public void tickOrStop(ServerLevel level, Villager villager, long gameTime) {
        delegate.tickOrStop(level, villager, gameTime);
    }

    @Override
    public void doStop(ServerLevel level, Villager villager, long gameTime) {
        delegate.doStop(level, villager, gameTime);
    }

    @Override
    public String debugString() {
        return delegate.debugString();
    }

    private static boolean atBankerWorkSide(ServerLevel level, Villager villager) {
        GlobalPos potentialJobSite = villager.getBrain()
                .getMemory(MemoryModuleType.POTENTIAL_JOB_SITE)
                .orElse(null);
        if (potentialJobSite == null || !potentialJobSite.dimension().equals(level.dimension())) {
            return true;
        }

        BlockPos bankPos = potentialJobSite.pos();
        BlockState state = level.getBlockState(bankPos);
        return !state.is(ECAPBlocks.BANK.get())
                || BankBlock.isAtBankerWorkPos(state, bankPos, villager.position());
    }
}
