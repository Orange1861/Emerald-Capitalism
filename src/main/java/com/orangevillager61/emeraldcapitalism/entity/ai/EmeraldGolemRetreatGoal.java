package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Moves a newly bank-created emerald golem away from the bank after it spawns. */
public final class EmeraldGolemRetreatGoal extends Goal {

    private static final int RETREAT_DISTANCE = 10;
    private static final int RETREAT_VERTICAL_RANGE = 7;
    private static final int MAX_RETREAT_TICKS = 200;
    private static final double MIN_RETREAT_DISTANCE_SQR = 64.0D;
    private static final double RETREAT_SPEED = 1.0D;

    private final EmeraldGolem golem;
    private final Vec3 bankCenter;
    private int ticksRemaining;
    private Vec3 target;
    private boolean finished;

    private EmeraldGolemRetreatGoal(EmeraldGolem golem, BlockPos bankPos) {
        this.golem = golem;
        this.bankCenter = Vec3.atCenterOf(bankPos);
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** Starts one server-side retreat for the supplied bank-created golem. */
    public static void start(EmeraldGolem golem, BlockPos bankPos) {
        if (golem.isVaultGuard()) {
            return;
        }
        golem.goalSelector.addGoal(2, new EmeraldGolemRetreatGoal(golem, bankPos));
    }

    @Override
    public boolean canUse() {
        return !finished && golem.isAlive() && golem.getTarget() == null;
    }

    @Override
    public void start() {
        ticksRemaining = MAX_RETREAT_TICKS;
        chooseTarget();
    }

    @Override
    public boolean canContinueToUse() {
        return !finished && golem.isAlive() && ticksRemaining > 0
                && (target == null || golem.distanceToSqr(target) > 4.0D);
    }

    @Override
    public void tick() {
        ticksRemaining--;
        if (target == null || golem.getNavigation().isDone()) {
            chooseTarget();
        }
    }

    @Override
    public void stop() {
        finished = true;
        golem.getNavigation().stop();
    }

    private void chooseTarget() {
        target = DefaultRandomPos.getPosAway(golem, RETREAT_DISTANCE,
                RETREAT_VERTICAL_RANGE, bankCenter);
        if (target == null || golem.distanceToSqr(target) < MIN_RETREAT_DISTANCE_SQR) {
            target = null;
            return;
        }

        if (!golem.getNavigation().moveTo(target.x, target.y, target.z, RETREAT_SPEED)) {
            target = null;
        }
    }
}
