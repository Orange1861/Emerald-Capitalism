package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.market.MarketDemandContext;
import com.orangevillager61.emeraldcapitalism.market.MarketItem;
import com.orangevillager61.emeraldcapitalism.market.MarketPricingEngine;
import com.orangevillager61.emeraldcapitalism.market.MarketRegistry;
import com.orangevillager61.emeraldcapitalism.market.MarketTradeQuote;
import com.orangevillager61.emeraldcapitalism.market.TradeSide;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import com.orangevillager61.emeraldcapitalism.util.VillagerInventoryPolicy;
import com.orangevillager61.emeraldcapitalism.world.bank.BankAccountData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/** Sends a full-inventory villager to the bank to sell or donate loose items. */
public final class VillagerInventoryBankGoal extends Goal {

    private static final float SPEED = 0.5F;
    private static final double ARRIVAL_DIST_SQ = 4.0D;
    private static final int ATTEMPT_TICKS = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final int SUCCESS_COOLDOWN = 20;
    private static final int FAILURE_COOLDOWN = 100;

    private final Villager villager;

    private BankBlockEntity bank;
    private BlockPos depositPos;
    private BlockPos navigationTarget;
    private int attemptTicks;
    private int attempts;
    private long nextActionTick;
    private boolean finished;

    public VillagerInventoryBankGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level)
                || villager.isBaby()
                || villager.isSleeping()
                || villager.isTrading()
                || VillagerBreedingSessions.shouldYieldCustomWork(villager)
                || level.getGameTime() < nextActionTick) {
            return false;
        }

        bank = BankEmployeeLookup.findVillageBank(level, villager);
        if (bank == null || !bank.isVillagerDeliveriesEnabled() || !isDeliveryModeEnabled()
                || (!isInventoryFull() && !isLumberjackDelivery())) {
            return false;
        }
        BankAccountData.get(level).openAccount(villager.getUUID());
        return hasLiquidatableItems();
    }

    @Override
    public void start() {
        attemptTicks = 0;
        attempts = 0;
        finished = false;

        if (!(villager.level() instanceof ServerLevel level)) {
            finish(null, FAILURE_COOLDOWN);
            return;
        }

        bank = BankEmployeeLookup.findVillageBank(level, villager);
        if (bank == null) {
            finish(level, FAILURE_COOLDOWN);
            return;
        }

        depositPos = BankBlock.getDepositApproachPos(bank.getBlockState(), bank.getBlockPos());
        if (isAtBank()) {
            navigationTarget = depositPos;
            return;
        }

        navigationTarget = VillagerNavigationTargets.findReachableTarget(villager, depositPos, 2);
        if (navigationTarget == null || !moveToBank()) {
            finish(level, FAILURE_COOLDOWN);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return !finished
                && bank != null
                && depositPos != null
                && attempts < MAX_ATTEMPTS
                && !villager.isSleeping()
                && !villager.isTrading()
                && !VillagerBreedingSessions.shouldYieldCustomWork(villager)
                && !bank.isRemoved()
                && bank.isVillagerDeliveriesEnabled()
                && isDeliveryModeEnabled();
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level)
                || bank == null || depositPos == null) {
            finish(levelOrNull(), FAILURE_COOLDOWN);
            return;
        }

        if (isAtBank()) {
            boolean transferred = liquidateInventory(level);
            finish(level, transferred ? SUCCESS_COOLDOWN : FAILURE_COOLDOWN);
            return;
        }

        if (navigationTarget != null) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(navigationTarget, SPEED, 1));
        }
        attemptTicks++;
        if (attemptTicks >= ATTEMPT_TICKS) {
            attemptTicks = 0;
            attempts++;
            if (attempts >= MAX_ATTEMPTS || navigationTarget == null || !moveToBank()) {
                finish(level, FAILURE_COOLDOWN);
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

    private boolean liquidateInventory(ServerLevel level) {
        boolean transferred = false;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack held = villager.getInventory().getItem(slot);
            if (held.isEmpty() || !isDeliverableItem(held)) {
                continue;
            }

            while (!held.isEmpty()) {
                MarketItem market = findMarketItem(held);
                int capacity = bank.getItemStorageCapacity(level, held);
                if (capacity <= 0) {
                    break;
                }

                int requested = Math.min(held.getCount(), capacity);
                int quantity = requested;
                MarketTradeQuote quote = null;
                if (market != null) {
                    quote = quote(level, market, requested);
                    if (!isValidQuote(quote)) {
                        break;
                    }
                    quantity = Math.min(requested, quote.quantity());
                }
                if (quantity <= 0) {
                    break;
                }

                ItemStack offered = held.copyWithCount(quantity);
                if (!bank.storeItemInLinkedChests(level, offered)) {
                    break;
                }

                held.shrink(quantity);
                villager.getInventory().setItem(slot, held.isEmpty() ? ItemStack.EMPTY : held);
                if (quote != null) {
                    // A priced item is a sale; an unpriced item is a donation
                    // and therefore does not create an account credit.
                    BankAccountData.get(level).deposit(villager.getUUID(), quote.emeraldAmount());
                }
                bank.markInventoryChanged(level);
                transferred = true;
            }
        }
        return transferred;
    }

    private MarketItem findMarketItem(ItemStack stack) {
        for (MarketItem market : MarketRegistry.entries()) {
            Item marketItem = market.item();
            if (marketItem == stack.getItem()
                    || (BankBlockEntity.isLogMarketItem(marketItem) && stack.is(ItemTags.LOGS))
                    || (BankBlockEntity.isCoalMarketItem(marketItem) && stack.is(ItemTags.COALS))) {
                return market;
            }
        }
        return null;
    }

    private MarketTradeQuote quote(ServerLevel level, MarketItem market, int quantity) {
        MarketDemandContext context = new MarketDemandContext(
                bank.getMarketPopulation(level), bank.getMarketTarget(level, market));
        return MarketPricingEngine.quote(
                market.config(), bank.getMarketStock(level, market.item()), context,
                quantity, TradeSide.SELL);
    }

    private boolean isValidQuote(MarketTradeQuote quote) {
        return quote != null && quote.valid() && quote.quantity() > 0;
    }

    private boolean hasLiquidatableItems() {
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (!stack.isEmpty() && isDeliverableItem(stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDeliveryModeEnabled() {
        return bank != null && (bank.isRandomDeliveriesEnabled() || isLumberjackDelivery());
    }

    private boolean isLumberjackDelivery() {
        return bank != null
                && villager.getVillagerData().getProfession() == ECAPVillagerProfessions.LUMBERJACK.get()
                && bank.isLumberjackDeliveriesEnabled();
    }

    private boolean isDeliverableItem(ItemStack stack) {
        if (VillagerInventoryPolicy.isReservedForVillager(villager, stack)
                || bank == null
                || (stack.is(net.minecraft.world.item.Items.BREAD)
                && !bank.isBreadDeliveriesEnabled())) {
            return false;
        }
        if (bank.isRandomDeliveriesEnabled()) {
            return true;
        }
        return isLumberjackDelivery() && (stack.is(ItemTags.LOGS) || stack.is(ItemTags.COALS));
    }

    private boolean isInventoryFull() {
        // A partially filled stack still occupies a slot and can block a
        // different pickup, which is the condition this cleanup task repairs.
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean moveToBank() {
        if (navigationTarget == null) {
            return false;
        }
        if (!villager.getNavigation().moveTo(
                navigationTarget.getX() + 0.5D,
                navigationTarget.getY() + 0.5D,
                navigationTarget.getZ() + 0.5D,
                SPEED)) {
            return false;
        }
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(navigationTarget, SPEED, 1));
        return true;
    }

    private boolean isAtBank() {
        return villager.distanceToSqr(
                depositPos.getX() + 0.5D, depositPos.getY() + 0.5D, depositPos.getZ() + 0.5D)
                <= ARRIVAL_DIST_SQ;
    }

    private void finish(ServerLevel level, int cooldown) {
        finished = true;
        if (level != null) {
            nextActionTick = level.getGameTime() + cooldown;
        }
    }

    private ServerLevel levelOrNull() {
        return villager.level() instanceof ServerLevel level ? level : null;
    }
}
