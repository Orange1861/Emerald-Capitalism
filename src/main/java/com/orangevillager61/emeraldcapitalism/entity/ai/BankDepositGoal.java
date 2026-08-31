package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * One-shot goal that guides the current depositor to the bank. It retries
 * navigation for a bounded number of attempts, then either deposits or calls
 * {@link BankBlockEntity#skipCurrent()}; goal removal is deferred until the
 * bank tick completes to avoid modifying the goal selector during iteration.
 */
public class BankDepositGoal extends Goal {

    /** Squared arrival distance (2 blocks). */
    private static final double ARRIVAL_DIST_SQ = 4.0;
    /** The Bank block is solid, so target a nearby reachable block instead of its center. */
    private static final int WALK_TARGET_CLOSE_ENOUGH = 2;
    /** Walk speed modifier passed to navigation. */
    private static final float  SPEED           = 0.5f;
    /** Ticks allowed per pathfinding attempt before it counts as a failure. */
    private static final int    ATTEMPT_TICKS   = 100;
    /** Maximum failed attempts before the villager is skipped entirely. */
    private static final int    MAX_ATTEMPTS    = 5;

    private final Villager        villager;
    private final BlockPos        bankPos;
    private final BankBlockEntity bank;

    private int     attemptCount     = 0;
    private int     ticksThisAttempt = 0;
    private boolean skipped          = false;
    private BlockPos walkTargetPos;

    public BankDepositGoal(Villager villager, BlockPos bankPos, BankBlockEntity bank) {
        this.villager = villager;
        this.bankPos  = bankPos;
        this.bank     = bank;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // Goal lifecycle

    @Override
    public boolean canUse() {
        if (skipped || VillagerBreedingSessions.shouldYieldCustomWork(villager)
                || LumberjackGoal.isRunning(villager)
                || !villager.level().isDay()
                || !bank.isCurrentDepositor(villager.getUUID())) return false;
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        stats.refreshInventoryCounts(villager.getInventory());
        return stats.getCachedEmeraldCount() > BankBlockEntity.MIN_EMERALDS_TO_DEPOSIT;
    }

    @Override
    public void start() {
        attemptCount     = 0;
        ticksThisAttempt = 0;
        skipped          = false;
        walkTargetPos    = findWalkTarget();
        if (walkTargetPos == null || !moveToBank()) {
            skipped = true;
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (skipped || VillagerBreedingSessions.shouldYieldCustomWork(villager)) return false;
        if (!villager.level().isDay()) return false;
        if (LumberjackGoal.isRunning(villager)) return false;
        if (isAtBank()) return false;
        // Stop if the bank externally cleared the queue (currentDepositor became null)
        return bank.isCurrentDepositor(villager.getUUID());
    }

    @Override
    public void tick() {
        if (walkTargetPos == null) {
            skipped = true;
            return;
        }
        // Keep the brain's walk-target memory aligned so SetWalkTargetFromMemory
        // does not override our navigation path.
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(walkTargetPos, SPEED, WALK_TARGET_CLOSE_ENOUGH));

        ticksThisAttempt++;

        if (ticksThisAttempt >= ATTEMPT_TICKS) {
            attemptCount++;
            ticksThisAttempt = 0;
            if (attemptCount >= MAX_ATTEMPTS) {
                // All attempts exhausted: canContinueToUse() will now return false,
                // and stop() will call skipCurrent().
                skipped = true;
            } else {
                // Retry pathfinding
                walkTargetPos = findWalkTarget();
                if (walkTargetPos == null || !moveToBank()) {
                    skipped = true;
                }
            }
        }
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        // Breeding temporarily owns movement. Keep this goal registered and leave
        // the bank queue untouched so the deposit can resume after the session.
        if (VillagerBreedingSessions.shouldYieldCustomWork(villager)) {
            return;
        }

        if (LumberjackGoal.isRunning(villager)) {
            if (bank.isCurrentDepositor(villager.getUUID())) {
                bank.pauseCurrentDepositor();
            }
            bank.scheduleGoalRemoval(this);
            return;
        }

        if (!villager.level().isDay()) {
            if (bank.isCurrentDepositor(villager.getUUID())) {
                bank.pauseCurrentDepositor();
            }
            bank.scheduleGoalRemoval(this);
            return;
        }

        if (!skipped && isAtBank() && bank.isCurrentDepositor(villager.getUUID())) {
            // Arrived successfully: take emeralds and credit balance
            if (villager.level() instanceof ServerLevel serverLevel) {
                bank.handleDepositorArrival(serverLevel, villager);
            }
            bank.advanceQueue();
        } else if (skipped && bank.isCurrentDepositor(villager.getUUID())) {
            // Could not reach bank after MAX_ATTEMPTS: skip this villager
            bank.skipCurrent();
        }
        // If neither condition holds (bank was externally cleared), the queue state
        // was already reset by clearQueue(): no action needed here.

        // Defer goal selector removal to the next bank tick to avoid
        // ConcurrentModificationException inside the goal scheduler's iteration.
        bank.scheduleGoalRemoval(this);
    }

    // Package-private helpers called by BankBlockEntity

    /**
     * Removes this goal from the owning villager's goal selector.
     * Called by {@link BankBlockEntity} outside of goal-scheduler iteration.
     */
    public void removeFromVillager() {
        villager.goalSelector.removeGoal(this);
    }

    // Private helpers

    private boolean moveToBank() {
        if (walkTargetPos == null) {
            walkTargetPos = findWalkTarget();
        }
        return walkTargetPos != null && villager.getNavigation().moveTo(
                walkTargetPos.getX() + 0.5,
                walkTargetPos.getY() + 0.5,
                walkTargetPos.getZ() + 0.5,
                SPEED);
    }

    @Nullable
    private BlockPos findWalkTarget() {
        // Depositors always use the side named by the bank's FACING state. Do
        // not select the nearest side: that lets the villager approach from
        // the designated deposit side and avoids circling around the bank.
        BlockPos approach = BankBlock.getDepositApproachPos(bank.getBlockState(), bankPos);
        return VillagerNavigationTargets.findReachableTarget(villager, approach, 2);
    }

    private boolean isAtBank() {
        BlockPos depositPos = BankBlock.getDepositApproachPos(bank.getBlockState(), bankPos);
        return villager.distanceToSqr(
                depositPos.getX() + 0.5,
                depositPos.getY() + 0.5,
                depositPos.getZ() + 0.5) <= ARRIVAL_DIST_SQ;
    }
}
