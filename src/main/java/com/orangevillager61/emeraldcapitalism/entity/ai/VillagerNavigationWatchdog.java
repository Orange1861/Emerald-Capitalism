package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Detects a custom movement goal that has stopped making progress toward its target. */
public final class VillagerNavigationWatchdog {

    private static final int MAX_STAGNANT_TICKS = 100;
    private static final double MIN_PROGRESS_SQ = 0.0004D;

    @Nullable
    private BlockPos target;
    @Nullable
    private Vec3 lastPosition;
    private int stagnantTicks;

    public void reset() {
        target = null;
        lastPosition = null;
        stagnantTicks = 0;
    }

    /**
     * Returns true after roughly five seconds without meaningful movement. Callers
     * should only invoke this while the villager is still outside its arrival range.
     */
    public boolean isStuck(Villager villager, BlockPos target) {
        if (!target.equals(this.target)) {
            this.target = target.immutable();
            lastPosition = villager.position();
            stagnantTicks = 0;
            return false;
        }

        Vec3 currentPosition = villager.position();
        if (lastPosition == null || currentPosition.distanceToSqr(lastPosition) >= MIN_PROGRESS_SQ) {
            lastPosition = currentPosition;
            stagnantTicks = 0;
            return false;
        }

        stagnantTicks++;
        return stagnantTicks >= MAX_STAGNANT_TICKS;
    }
}
