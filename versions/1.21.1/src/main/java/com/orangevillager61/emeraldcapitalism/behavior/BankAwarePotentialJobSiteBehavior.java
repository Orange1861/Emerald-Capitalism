package com.orangevillager61.emeraldcapitalism.behavior;

import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.behavior.GoToPotentialJobSite;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps vanilla potential-job-site handling while routing bank candidates to
 * the side reserved for banker work.
 */
public final class BankAwarePotentialJobSiteBehavior extends GoToPotentialJobSite {

    private final float speedModifier;

    public BankAwarePotentialJobSiteBehavior(float speedModifier) {
        super(speedModifier);
        this.speedModifier = speedModifier;
    }

    @Override
    protected void tick(ServerLevel level, Villager owner, long gameTime) {
        GlobalPos potentialJobSite = owner.getBrain()
                .getMemory(MemoryModuleType.POTENTIAL_JOB_SITE)
                .orElseThrow(() -> new IllegalStateException(
                        "Potential job-site behavior ticked without a potential job site"));
        BlockPos target = potentialJobSite.pos();

        if (potentialJobSite.dimension().equals(level.dimension())) {
            BlockState state = level.getBlockState(target);
            if (state.is(ECAPBlocks.BANK.get())) {
                target = BankBlock.getBankerWorkPos(state, target);
            }
        }

        BehaviorUtils.setWalkAndLookTargetMemories(owner, target, speedModifier, 1);
    }
}
