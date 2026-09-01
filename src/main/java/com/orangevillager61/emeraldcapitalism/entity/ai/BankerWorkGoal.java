package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;

import java.util.EnumSet;

/** Keeps a banker on the bank's facing side while the banker is working. */
public final class BankerWorkGoal extends Goal {

    private static final float SPEED = 0.5F;
    private static final int WALK_TARGET_CLOSE_ENOUGH = 1;
    private static final int FAILURE_RETRY_TICKS = 100;
    private static final int REPAIR_ATTEMPT_INTERVAL_TICKS = 20;

    private final Villager villager;
    private final VillagerNavigationWatchdog navigationWatchdog = new VillagerNavigationWatchdog();
    private WorkContext context;
    private boolean failed;
    private long nextContextLookupTick;
    private long nextRepairAttemptTick = Long.MIN_VALUE;

    public BankerWorkGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!isEligible() || villager.level().getGameTime() < nextContextLookupTick) {
            return false;
        }
        context = resolveContext();
        if (context == null) {
            nextContextLookupTick = villager.level().getGameTime() + FAILURE_RETRY_TICKS;
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        nextRepairAttemptTick = Long.MIN_VALUE;
        if (context == null) {
            failed = true;
            nextContextLookupTick = villager.level().getGameTime() + FAILURE_RETRY_TICKS;
            return;
        }
        BlockPos navigationTarget = VillagerNavigationTargets.findReachableTarget(villager, context.workPos(), 2);
        if (navigationTarget == null) {
            context = null;
            failed = true;
            nextContextLookupTick = villager.level().getGameTime() + FAILURE_RETRY_TICKS;
            return;
        }
        context = new WorkContext(context.bank(), context.workPos(), navigationTarget);
        failed = !moveToWorkSide();
        if (failed) {
            nextContextLookupTick = villager.level().getGameTime() + FAILURE_RETRY_TICKS;
        }
        navigationWatchdog.reset();
    }

    @Override
    public boolean canContinueToUse() {
        return isEligible()
                && context != null
                && !failed
                && context.bank().isBankIndependent()
                && context.bank().isEmployee(villager.getUUID());
    }

    @Override
    public void tick() {
        if (context == null || failed || context.navigationTarget() == null) {
            return;
        }

        // Keep the brain's target on the designated work side instead of
        // allowing vanilla job-site pathing to pull the banker into the bank.
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(context.navigationTarget(), SPEED, WALK_TARGET_CLOSE_ENOUGH));
        if (villager.distanceToSqr(context.workPos().getX() + 0.5D,
                context.workPos().getY() + 0.5D, context.workPos().getZ() + 0.5D) <= 2.25D) {
            ServerLevel level = (ServerLevel) villager.level();
            long gameTime = level.getGameTime();
            if (gameTime >= nextRepairAttemptTick) {
                context.bank().replaceMissingEmeraldChest(level);
                nextRepairAttemptTick = gameTime + REPAIR_ATTEMPT_INTERVAL_TICKS;
            }
        }
        if (villager.distanceToSqr(context.workPos().getX() + 0.5D,
                context.workPos().getY() + 0.5D, context.workPos().getZ() + 0.5D) > 2.25D
                && navigationWatchdog.isStuck(villager, context.navigationTarget())) {
            failed = true;
            nextContextLookupTick = villager.level().getGameTime() + FAILURE_RETRY_TICKS;
            villager.getNavigation().stop();
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        } else if (villager.getNavigation().isDone()
                && villager.distanceToSqr(context.navigationTarget().getX() + 0.5D,
                context.navigationTarget().getY() + 0.5D,
                context.navigationTarget().getZ() + 0.5D) > 2.25D) {
            failed = !moveToWorkSide();
            if (failed) {
                nextContextLookupTick = villager.level().getGameTime() + FAILURE_RETRY_TICKS;
            }
        }
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        context = null;
        navigationWatchdog.reset();
        failed = false;
        nextRepairAttemptTick = Long.MIN_VALUE;
    }

    private boolean isEligible() {
        return villager.level() instanceof ServerLevel
                && villager.getVillagerData().getProfession() == ECAPVillagerProfessions.BANKER.get()
                && !villager.isBaby()
                && !villager.isSleeping()
                && !villager.isTrading()
                && villager.level().isDay();
    }

    private WorkContext resolveContext() {
        if (!(villager.level() instanceof ServerLevel level)) {
            return null;
        }

        BankBlockEntity bank = BankEmployeeLookup.findEmployeeBank(level, villager);
        if (bank == null || !bank.isBankIndependent()) {
            return null;
        }

        BlockPos bankPos = bank.getBlockPos();
        BlockPos workPos = BankBlock.getBankerWorkPos(bank.getBlockState(), bankPos);
        return new WorkContext(bank, workPos, null);
    }

    private boolean moveToWorkSide() {
        BlockPos target = context.navigationTarget();
        return villager.getNavigation().moveTo(
                target.getX() + 0.5D,
                target.getY() + 0.5D,
                target.getZ() + 0.5D,
                SPEED);
    }

    private record WorkContext(BankBlockEntity bank, BlockPos workPos, BlockPos navigationTarget) {
    }
}
