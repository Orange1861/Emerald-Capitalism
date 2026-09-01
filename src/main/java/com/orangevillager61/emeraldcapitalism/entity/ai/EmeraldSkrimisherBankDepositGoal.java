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

/** Sends an Emerald Skrimisher to its village bank once each morning. */
public final class EmeraldSkrimisherBankDepositGoal extends Goal {

    private static final long DAY_LENGTH_TICKS = 24_000L;
    private static final long MORNING_END_TICK = 6_000L;
    private static final float SPEED = 0.5F;
    private static final double ARRIVAL_DIST_SQ = 4.0D;
    private static final int ATTEMPT_TICKS = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final int FAILURE_COOLDOWN = 100;

    private final EmeraldSkrimisher skrimisher;

    private WorkContext context;
    private int attemptTicks;
    private int attempts;
    private boolean finished;
    private long nextActionTick;
    private long lastMorningDay = -1L;

    public EmeraldSkrimisherBankDepositGoal(EmeraldSkrimisher skrimisher) {
        this.skrimisher = skrimisher;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(skrimisher.level() instanceof ServerLevel level)
                || skrimisher.isSleeping()
                || skrimisher.getTarget() != null
                || level.getGameTime() < nextActionTick) {
            return false;
        }

        long day = level.getDayTime() / DAY_LENGTH_TICKS;
        long timeOfDay = Math.floorMod(level.getDayTime(), DAY_LENGTH_TICKS);
        if (timeOfDay >= MORNING_END_TICK || day == lastMorningDay) {
            return false;
        }

        context = resolveContext(level);
        if (context == null) {
            return false;
        }

        // An empty morning is still a completed check; do not retry it every AI tick.
        lastMorningDay = day;
        return hasPendingItems();
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
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
            return;
        }

        if (isAtBank(context.depositPos())) {
            context = new WorkContext(context.bank(), context.depositPos(), context.depositPos());
            return;
        }

        BlockPos navigationTarget = VillagerNavigationTargets.findReachableTarget(
                skrimisher, context.depositPos(), 2);
        if (navigationTarget == null) {
            finished = true;
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
            return;
        }
        context = new WorkContext(context.bank(), context.depositPos(), navigationTarget);
        if (!moveToBank()) {
            finished = true;
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
        }
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

        if (isAtBank(context.depositPos())) {
            transferInventory(level, context.bank());
            finished = true;
            return;
        }

        attemptTicks++;
        if (attemptTicks >= ATTEMPT_TICKS) {
            attemptTicks = 0;
            attempts++;
            if (attempts >= MAX_ATTEMPTS || !moveToBank()) {
                finished = true;
                nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
            }
        }
    }

    @Override
    public void stop() {
        skrimisher.getNavigation().stop();
        skrimisher.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        if (finished && skrimisher.level() instanceof ServerLevel level
                && nextActionTick < level.getGameTime()) {
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
        }
    }

    private boolean hasPendingItems() {
        for (int slot = 0; slot < skrimisher.getInventory().getContainerSize(); slot++) {
            if (!skrimisher.getInventory().getItem(slot).isEmpty()) {
                return true;
            }
        }
        return false;
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
                new WalkTarget(pos, SPEED, 1));
        return true;
    }

    private boolean isAtBank(BlockPos pos) {
        return skrimisher.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D,
                pos.getZ() + 0.5D) <= ARRIVAL_DIST_SQ;
    }

    private record WorkContext(BankBlockEntity bank, BlockPos depositPos, BlockPos navigationTarget) {
    }
}
