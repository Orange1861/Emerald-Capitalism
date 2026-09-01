package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumSet;

/**
 * Moves pumpkins held by a Bank employee who is a farmer into that Bank's
 * linked Emerald Chests.
 */
public final class BankPumpkinDepositGoal extends Goal {

    private static final float SPEED = 0.5F;
    private static final int ATTEMPT_TICKS = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final int SUCCESS_COOLDOWN = 20;
    private static final int FAILURE_COOLDOWN = 100;

    private final Villager villager;

    private WorkContext context;
    private int attemptTicks;
    private int attempts;
    private boolean finished;
    private long nextActionTick;

    public BankPumpkinDepositGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level)
                || villager.getVillagerData().getProfession() != VillagerProfession.FARMER
                || villager.isSleeping()
                || villager.isTrading()
                || VillagerBreedingSessions.shouldYieldCustomWork(villager)
                || level.getGameTime() < nextActionTick) {
            return false;
        }

        WorkContext resolved = resolveContext(level);
        if (resolved == null) {
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
            return false;
        }
        boolean pending = resolved.bank().isVillagerDeliveriesEnabled()
                && resolved.bank().isRandomDeliveriesEnabled()
                && resolved.bank().getTotalPumpkinCount() < resolved.bank().getPumpkinTarget()
                && hasPendingTask();
        if (!pending) {
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
        }
        return pending;
    }

    @Override
    public void start() {
        attemptTicks = 0;
        attempts = 0;
        finished = false;

        if (!(villager.level() instanceof ServerLevel level)) {
            finished = true;
            return;
        }

        context = resolveContext(level);
        if (context == null) {
            finished = true;
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
            return;
        }
        BlockPos navigationTarget = VillagerNavigationTargets.findReachableTarget(villager, context.depositPos(), 2);
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
                && villager.getVillagerData().getProfession() == VillagerProfession.FARMER
                && !villager.isSleeping()
                && !villager.isTrading()
                && !VillagerBreedingSessions.shouldYieldCustomWork(villager)
                && context.bank().isVillagerDeliveriesEnabled()
                && context.bank().isRandomDeliveriesEnabled()
                && context.bank().isEmployee(villager.getUUID());
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level) || context == null) {
            finished = true;
            return;
        }

        if (isAtBank()) {
            transferPumpkins(level, context.bank());
            finished = true;
            return;
        }

        attemptTicks++;
        if (attemptTicks >= ATTEMPT_TICKS) {
            attemptTicks = 0;
            attempts++;
            if (attempts >= MAX_ATTEMPTS) {
                finished = true;
                nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
            } else {
                if (!moveToBank()) {
                    finished = true;
                    nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
                }
            }
        }
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        if (finished && villager.level() instanceof ServerLevel level
                && nextActionTick < level.getGameTime()) {
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
        }
    }

    private boolean hasPendingTask() {
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(Items.PUMPKIN)) {
                return true;
            }
        }
        return false;
    }

    private void transferPumpkins(ServerLevel level, BankBlockEntity bank) {
        boolean transferred = false;
        int targetShortfall = Math.max(0, bank.getPumpkinTarget() - bank.getTotalPumpkinCount());
        var inventory = villager.getInventory();

        for (int slot = 0; slot < inventory.getContainerSize() && targetShortfall > 0; slot++) {
            ItemStack held = inventory.getItem(slot);
            if (!held.is(Items.PUMPKIN)) {
                continue;
            }

            int capacity = bank.getItemStorageCapacity(level, held);
            int amount = Math.min(Math.min(held.getCount(), capacity), targetShortfall);
            if (amount <= 0) {
                continue;
            }

            ItemStack toStore = held.copyWithCount(amount);
            if (!bank.storeItemInLinkedChests(level, toStore)) {
                continue;
            }

            held.shrink(amount);
            inventory.setItem(slot, held.isEmpty() ? ItemStack.EMPTY : held);
            targetShortfall -= amount;
            transferred = true;
        }

        nextActionTick = level.getGameTime()
                + (transferred ? SUCCESS_COOLDOWN : FAILURE_COOLDOWN);
    }

    private WorkContext resolveContext(ServerLevel level) {
        BankBlockEntity bank = BankEmployeeLookup.findEmployeeBank(level, villager);
        if (bank == null) {
            return null;
        }
        BlockPos bankPos = bank.getBlockPos();
        BlockPos depositPos = BankBlock.getDepositApproachPos(bank.getBlockState(), bankPos);
        return new WorkContext(bank, depositPos, null);
    }

    private boolean moveToBank() {
        BlockPos pos = context.navigationTarget();
        if (!villager.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SPEED)) {
            return false;
        }
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(pos, SPEED, 1));
        return true;
    }

    private boolean isAtBank() {
        return context != null && BankBlock.isAtDepositApproach(
                context.bank().getBlockState(), context.bank().getBlockPos(), villager.position());
    }

    private record WorkContext(BankBlockEntity bank, BlockPos depositPos, BlockPos navigationTarget) {
    }
}
