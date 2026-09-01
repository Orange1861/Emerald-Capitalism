package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldOreProcessorBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumSet;

/**
 * Supplies a village's Emerald Processor from its registered Bank.
 * All chest access is delegated to the Bank so smiths never perform world-volume scans.
 */
public final class EmeraldSmithProcessorGoal extends Goal {

    private static final float SPEED = 0.5F;
    private static final double ARRIVAL_DIST_SQ = 4.0;
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

    public EmeraldSmithProcessorGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level)
                || villager.getVillagerData().getProfession() != ECAPVillagerProfessions.EMERALDSMITH.get()
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
        boolean pending = hasPendingTask(resolved);
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
        BlockPos navigationTarget = VillagerNavigationTargets.findReachableTarget(villager, context.processorPos(), 2);
        if (navigationTarget == null) {
            finished = true;
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
            return;
        }
        context = new WorkContext(context.bank(), context.processor(), context.processorPos(), navigationTarget);
        if (!moveToProcessor()) {
            finished = true;
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
        }
    }

    @Override
    public boolean canContinueToUse() {
        return !finished
                && context != null
                && attempts < MAX_ATTEMPTS
                && villager.getVillagerData().getProfession() == ECAPVillagerProfessions.EMERALDSMITH.get()
                && !villager.isSleeping()
                && !villager.isTrading()
                && !VillagerBreedingSessions.shouldYieldCustomWork(villager);
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level) || context == null) {
            finished = true;
            return;
        }

        if (isAtProcessor()) {
            performHighestPriorityTask(level);
            finished = true;
            return;
        }

        // Villager brain behaviors can replace a one-time path command. Keep the
        // custom destination present while this goal owns movement.
        setProcessorWalkTarget();
        attemptTicks++;
        if (attemptTicks >= ATTEMPT_TICKS) {
            attemptTicks = 0;
            attempts++;
            if (attempts >= MAX_ATTEMPTS) {
                finished = true;
                nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
            } else {
                if (!moveToProcessor()) {
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

    private void performHighestPriorityTask(ServerLevel level) {
        if (context == null) return;

        EmeraldOreProcessorBlockEntity processor = context.processor();
        BankBlockEntity bank = context.bank();

        // Repair tracked bank locations before ordinary processor work. The
        // crafted chests remain in the Bank's durable repair reserve until a
        // banker can place them, so a reload cannot lose completed work.
        int craftedChests = bank.craftMissingEmeraldChests(level);
        if (craftedChests > 0) {
            nextActionTick = level.getGameTime() + SUCCESS_COOLDOWN;
            return;
        }

        // 1. Refill an empty fuel slot with as much charcoal (or legacy coal) as
        // the processor accepts.
        if (processor.getItem(EmeraldOreProcessorBlockEntity.SLOT_FUEL).isEmpty()) {
            ItemStack fuel = bank.withdrawItem(level, Items.CHARCOAL, processor.getMaxStackSize());
            if (fuel.isEmpty()) {
                fuel = bank.withdrawItem(level, Items.COAL, processor.getMaxStackSize());
            }
            if (!fuel.isEmpty()) {
                processor.setItem(EmeraldOreProcessorBlockEntity.SLOT_FUEL, fuel);
                bank.markInventoryChanged(level);
                nextActionTick = level.getGameTime() + SUCCESS_COOLDOWN;
                return;
            }
        }

        ItemStack input = processor.getItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT);
        ItemStack output = processor.getItem(EmeraldOreProcessorBlockEntity.SLOT_OUTPUT);

        // 2. Move a full output stack to Bank storage when the processor is idle.
        if (input.isEmpty() && !output.isEmpty()
                && output.getCount() >= output.getMaxStackSize()) {
            ItemStack toStore = output.copy();
            if (bank.storeItemInLinkedChests(level, toStore)) {
                processor.setItem(EmeraldOreProcessorBlockEntity.SLOT_OUTPUT, ItemStack.EMPTY);
                bank.markInventoryChanged(level);
                nextActionTick = level.getGameTime() + SUCCESS_COOLDOWN;
                return;
            }
        }

        // 3. Reclaim stocked Emerald Chest items beyond the Bank's replacement
        // reserve. The processor converts each one into eight emeralds.
        if (input.isEmpty()
                && (output.isEmpty() || (output.is(Items.EMERALD)
                && output.getCount() <= output.getMaxStackSize()
                - EmeraldOreProcessorBlockEntity.EMERALDS_PER_CHEST))) {
            ItemStack surplusChests = bank.withdrawSurplusEmeraldChests(
                    level, processor.getMaxStackSize());
            if (!surplusChests.isEmpty()) {
                processor.setItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT, surplusChests);
                bank.markInventoryChanged(level);
                nextActionTick = level.getGameTime() + SUCCESS_COOLDOWN;
                return;
            }
        }

        // 4. Feed an idle, empty processor with as much Emerald Ore as it accepts.
        if (input.isEmpty() && output.isEmpty()) {
            ItemStack ore = bank.withdrawEmeraldOre(level, processor.getMaxStackSize());
            if (!ore.isEmpty()) {
                processor.setItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT, ore);
                bank.markInventoryChanged(level);
                nextActionTick = level.getGameTime() + SUCCESS_COOLDOWN;
                return;
            }
        }

        nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
    }

    private boolean hasPendingTask(WorkContext work) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (work.bank().getMissingChestCount(level)
                > work.bank().getPreparedEmeraldChestCount()
                && work.bank().getMarketStock(level, Items.CHEST) > 0
                && work.bank().getLiveEmeraldValue(level)
                >= EmeraldOreProcessorBlockEntity.EMERALDS_PER_CHEST) {
            return true;
        }
        EmeraldOreProcessorBlockEntity processor = work.processor();
        if (processor.getItem(EmeraldOreProcessorBlockEntity.SLOT_FUEL).isEmpty()
                && work.bank().getTotalCoalCount() > 0) {
            return true;
        }

        ItemStack input = processor.getItem(EmeraldOreProcessorBlockEntity.SLOT_INPUT);
        ItemStack output = processor.getItem(EmeraldOreProcessorBlockEntity.SLOT_OUTPUT);
        boolean canAcceptChest = input.isEmpty()
                && (output.isEmpty() || (output.is(Items.EMERALD)
                && output.getCount() <= output.getMaxStackSize()
                - EmeraldOreProcessorBlockEntity.EMERALDS_PER_CHEST));
        boolean hasFuel = EmeraldOreProcessorBlockEntity.isValidFuel(
                processor.getItem(EmeraldOreProcessorBlockEntity.SLOT_FUEL))
                || work.bank().getTotalCoalCount() > 0;
        if (canAcceptChest && hasFuel
                && work.bank().getSurplusEmeraldChestCount(level) > 0) {
            return true;
        }
        return input.isEmpty() && ((!output.isEmpty() && output.getCount() >= output.getMaxStackSize())
                || (output.isEmpty() && work.bank().getTotalEmeraldOreCount() > 0));
    }

    private WorkContext resolveContext(ServerLevel level) {
        BankBlockEntity bank = BankEmployeeLookup.findVillageBank(level, villager);
        if (bank == null) return null;

        BlockPos processorPos = bank.getClosestEmeraldProcessorPos();
        if (processorPos == null) return null;
        if (!(BankEmployeeLookup.getLoadedBlockEntity(level, processorPos)
                instanceof EmeraldOreProcessorBlockEntity processor)) {
            return null;
        }
        // The goal only resolves for a smith using this Bank's processor, so
        // record the villager immediately instead of waiting for the next village scan.
        bank.registerEmployeeFromJob(level, villager, processorPos);
        return new WorkContext(bank, processor, processorPos, null);
    }

    private boolean moveToProcessor() {
        BlockPos pos = context.navigationTarget();
        if (!villager.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SPEED)) {
            return false;
        }
        setProcessorWalkTarget();
        return true;
    }

    private void setProcessorWalkTarget() {
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(context.navigationTarget(), SPEED, 1));
    }

    private boolean isAtProcessor() {
        BlockPos pos = context.processorPos();
        return villager.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= ARRIVAL_DIST_SQ;
    }

    private record WorkContext(BankBlockEntity bank, EmeraldOreProcessorBlockEntity processor,
                               BlockPos processorPos, BlockPos navigationTarget) {
    }
}
