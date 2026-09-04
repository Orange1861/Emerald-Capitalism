package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldSkrimisher;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/** Periodically sends an Emerald Skrimisher to its village bank to empty its inventory. */
public final class EmeraldSkrimisherBankDepositGoal extends Goal {

    public static final long CHECK_INTERVAL_TICKS = 2_000L;
    private static final int MINIMUM_ITEMS_TO_DEPOSIT = 2;
    private static final float SPEED = 0.5F;
    private static final int ATTEMPT_TICKS = 100;
    // A Skrimisher may wander anywhere inside its village bounds between
    // checks. Give it enough retry time to return from the far side of that
    // village before declaring the periodic deposit unreachable.
    private static final int MAX_ATTEMPTS = 40;

    private final EmeraldSkrimisher skrimisher;

    private WorkContext context;
    private int attemptTicks;
    private int attempts;
    private boolean finished;
    private long nextCheckTick;

    public EmeraldSkrimisherBankDepositGoal(EmeraldSkrimisher skrimisher) {
        this.skrimisher = skrimisher;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(skrimisher.level() instanceof ServerLevel level)
                || skrimisher.isSleeping()
                || skrimisher.getTarget() != null
                || level.getGameTime() < nextCheckTick) {
            return false;
        }

        // Reserve the next check before resolving the bank. This keeps an
        // unassigned Skrimisher from resolving villages every AI tick.
        nextCheckTick = level.getGameTime() + CHECK_INTERVAL_TICKS;
        if (countHeldItems() <= MINIMUM_ITEMS_TO_DEPOSIT) {
            return false;
        }

        context = resolveContext(level);
        if (context == null) {
            return false;
        }

        return true;
    }

    @Override
    public void start() {
        attemptTicks = 0;
        attempts = 0;
        finished = false;

        if (!(skrimisher.level() instanceof ServerLevel level)) {
            finished = true;
            return;
        }

        context = resolveContext(level);
        if (context == null) {
            finished = true;
            return;
        }

        if (isAtBank()) {
            context = new WorkContext(context.bank(), context.depositPos(), context.depositPos());
            return;
        }

        BlockPos navigationTarget = VillagerNavigationTargets.findReachableTarget(
                skrimisher, context.depositPos(), 0);
        if (navigationTarget == null) {
            // The exact preflight probe can be stale while a Skrimisher is
            // moving at the edge of its village. Let the live navigation
            // system retry the bank approach instead of abandoning the visit.
            navigationTarget = context.depositPos();
        }
        context = new WorkContext(context.bank(), context.depositPos(), navigationTarget);
        // Navigation can transiently reject a target on the first tick (most
        // commonly while the entity is still finishing a random-stroll path).
        // Keep the visit active and let the normal retry cadence try again.
        moveToBank();
    }

    @Override
    public boolean canContinueToUse() {
        return !finished
                && context != null
                && attempts < MAX_ATTEMPTS
                && !skrimisher.isSleeping()
                && skrimisher.getTarget() == null
                && !context.bank().isRemoved();
    }

    @Override
    public void tick() {
        if (!(skrimisher.level() instanceof ServerLevel level) || context == null) {
            finished = true;
            return;
        }

        if (isAtBank()) {
            transferInventory(level, context.bank());
            finished = true;
            return;
        }

        attemptTicks++;
        if (attemptTicks >= ATTEMPT_TICKS) {
            attemptTicks = 0;
            attempts++;
            if (attempts >= MAX_ATTEMPTS) {
                finished = true;
            } else {
                // A failed path request is retryable; it should not discard
                // the inventory visit while the navigation mesh catches up.
                moveToBank();
            }
        }
    }

    @Override
    public void stop() {
        skrimisher.getNavigation().stop();
        skrimisher.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }

    private int countHeldItems() {
        int count = 0;
        for (int slot = 0; slot < skrimisher.getInventory().getContainerSize(); slot++) {
            count += skrimisher.getInventory().getItem(slot).getCount();
        }
        return count;
    }

    private void transferInventory(ServerLevel level, BankBlockEntity bank) {
        for (int slot = 0; slot < skrimisher.getInventory().getContainerSize(); slot++) {
            ItemStack held = skrimisher.getInventory().getItem(slot);
            if (held.isEmpty()) {
                continue;
            }

            // Use a copy so a failed capacity check never consumes the held stack.
            ItemStack toStore = held.copy();
            if (bank.storeItemInLinkedChests(level, toStore)) {
                skrimisher.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
    }

    private WorkContext resolveContext(ServerLevel level) {
        VillageRecord village = VillageRegistryData.get(level).getVillageFor(skrimisher.blockPosition());
        if (village == null) {
            return null;
        }

        BlockPos bankPos = VillageRegistryData.get(level).getBankPos(village.getVillageId());
        if (bankPos == null
                || !(BankEmployeeLookup.getLoadedBlockEntity(level, bankPos)
                instanceof BankBlockEntity bank)) {
            return null;
        }
        BlockPos depositPos = BankBlock.getDepositApproachPos(bank.getBlockState(), bankPos);
        return new WorkContext(bank, depositPos, null);
    }

    private boolean moveToBank() {
        BlockPos pos = context.navigationTarget();
        if (!skrimisher.getNavigation().moveTo(pos.getX() + 0.5D, pos.getY() + 0.5D,
                pos.getZ() + 0.5D, SPEED)) {
            return false;
        }
        skrimisher.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(pos, SPEED, 0));
        return true;
    }

    private boolean isAtBank() {
        return context != null && BankBlock.isAtDepositApproach(
                context.bank().getBlockState(), context.bank().getBlockPos(), skrimisher.position());
    }

    private record WorkContext(BankBlockEntity bank, BlockPos depositPos, BlockPos navigationTarget) {
    }
}
