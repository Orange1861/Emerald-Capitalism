package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.market.MarketDemandContext;
import com.orangevillager61.emeraldcapitalism.market.MarketItem;
import com.orangevillager61.emeraldcapitalism.market.MarketPricingEngine;
import com.orangevillager61.emeraldcapitalism.market.MarketRegistry;
import com.orangevillager61.emeraldcapitalism.market.MarketTradeQuote;
import com.orangevillager61.emeraldcapitalism.market.TradeSide;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import com.orangevillager61.emeraldcapitalism.world.bank.BankAccountData;
import com.orangevillager61.emeraldcapitalism.world.bank.BankTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumSet;

/** Sends villagers to their village bank once each morning to rebalance food and sell pumpkins. */
public final class BankMorningTradeGoal extends Goal {

    private static final long DAY_LENGTH_TICKS = 24_000L;
    private static final long MORNING_END_TICK = 6_000L;
    private static final float SPEED = 0.5F;
    private static final double ARRIVAL_DIST_SQ = 4.0;
    private static final int ATTEMPT_TICKS = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final int SUCCESS_COOLDOWN = 100;
    private static final int FAILURE_COOLDOWN = 100;

    private final Villager villager;

    private WorkContext context;
    private int attemptTicks;
    private int attempts;
    private boolean finished;
    private long nextActionTick;
    private long lastMorningDay = -1L;

    public BankMorningTradeGoal(Villager villager) {
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
                || LumberjackGoal.isRunning(villager)
                || level.getGameTime() < nextActionTick) {
            return false;
        }

        long day = level.getDayTime() / DAY_LENGTH_TICKS;
        long timeOfDay = Math.floorMod(level.getDayTime(), DAY_LENGTH_TICKS);
        if (timeOfDay >= MORNING_END_TICK || day == lastMorningDay) {
            return false;
        }

        BankBlockEntity bank = BankEmployeeLookup.findVillageBank(level, villager);
        if (bank == null) {
            lastMorningDay = day;
            return false;
        }

        BankAccountData.get(level).openAccount(villager.getUUID());
        boolean pendingTrade = hasPendingTrade(level, bank);
        if (!pendingTrade) {
            lastMorningDay = day;
        }
        return pendingTrade;
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
            markMorningAttemptFailed(level);
            return;
        }

        if (isAtBank(context.depositPos())) {
            context = new WorkContext(context.bank(), context.depositPos(), context.depositPos());
            return;
        }

        BlockPos navigationTarget = VillagerNavigationTargets.findReachableTarget(
                villager, context.depositPos(), 2);
        if (navigationTarget == null) {
            finished = true;
            markMorningAttemptFailed(level);
            return;
        }
        context = new WorkContext(context.bank(), context.depositPos(), navigationTarget);
        if (!moveToBank()) {
            finished = true;
            markMorningAttemptFailed(level);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return !finished
                && context != null
                && attempts < MAX_ATTEMPTS
                && !villager.isSleeping()
                && !villager.isTrading()
                && !VillagerBreedingSessions.shouldYieldCustomWork(villager)
                && !LumberjackGoal.isRunning(villager)
                && context.bank().isVillagerDeliveriesEnabled()
                && !context.bank().isRemoved();
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level) || context == null) {
            finished = true;
            return;
        }

        if (isAtBank(context.depositPos())) {
            executeMorningTrades(level, context.bank());
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
        villager.getNavigation().stop();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        if (finished && villager.level() instanceof ServerLevel level
                && nextActionTick < level.getGameTime()) {
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
        }
    }

    private WorkContext resolveContext(ServerLevel level) {
        BankBlockEntity bank = BankEmployeeLookup.findVillageBank(level, villager);
        if (bank == null) {
            return null;
        }
        BankAccountData.get(level).openAccount(villager.getUUID());
        BlockPos bankPos = bank.getBlockPos();
        return new WorkContext(bank, BankBlock.getDepositApproachPos(bank.getBlockState(), bankPos), null);
    }

    private void markMorningAttemptFailed(ServerLevel level) {
        lastMorningDay = level.getDayTime() / DAY_LENGTH_TICKS;
        nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
    }

    private boolean hasPendingTrade(ServerLevel level, BankBlockEntity bank) {
        return bank.isVillagerDeliveriesEnabled()
                && ((bank.isRandomDeliveriesEnabled() && hasPendingPumpkinSale(level, bank))
                || (bank.isBreadDeliveriesEnabled() && hasPendingBreadTrade(level, bank)));
    }

    private boolean hasPendingPumpkinSale(ServerLevel level, BankBlockEntity bank) {
        int quantity = countItem(Items.PUMPKIN);
        if (quantity <= 0) {
            return false;
        }
        int capacity = bank.getItemStorageCapacity(level, new ItemStack(Items.PUMPKIN, quantity));
        return isValidQuote(quote(level, bank, Items.PUMPKIN, Math.min(quantity, capacity), TradeSide.SELL));
    }

    private boolean hasPendingBreadTrade(ServerLevel level, BankBlockEntity bank) {
        if (!hasBreadReserve(level, bank)) {
            return false;
        }

        int currentBread = countItem(Items.BREAD);
        int purchaseQuantity = BankTargets.breadPurchaseQuantity(currentBread, bank.getFoodDays());
        if (purchaseQuantity > 0) {
            int quantity = Math.min(purchaseQuantity,
                    bank.getMarketStock(level, Items.BREAD) - breadVillageReserve(level, bank));
            quantity = Math.min(quantity, breadStorageCapacity());
            return isValidQuote(quote(level, bank, Items.BREAD, quantity, TradeSide.BUY));
        }

        int saleQuantity = BankTargets.breadSaleQuantity(currentBread, bank.getFoodDays());
        if (saleQuantity <= 0) {
            return false;
        }
        int capacity = bank.getItemStorageCapacity(level, new ItemStack(Items.BREAD, saleQuantity));
        return isValidQuote(quote(level, bank, Items.BREAD, Math.min(saleQuantity, capacity), TradeSide.SELL));
    }

    private void executeMorningTrades(ServerLevel level, BankBlockEntity bank) {
        boolean traded = bank.isVillagerDeliveriesEnabled()
                && bank.isRandomDeliveriesEnabled()
                && sellItem(level, bank, Items.PUMPKIN, countItem(Items.PUMPKIN), false);

        int currentBread = countItem(Items.BREAD);
        if (bank.isVillagerDeliveriesEnabled() && bank.isBreadDeliveriesEnabled()) {
            int purchaseQuantity = BankTargets.breadPurchaseQuantity(currentBread, bank.getFoodDays());
            if (purchaseQuantity > 0) {
                traded |= buyBread(level, bank, purchaseQuantity);
            } else {
                traded |= sellItem(level, bank, Items.BREAD,
                        BankTargets.breadSaleQuantity(currentBread, bank.getFoodDays()), true);
            }
        }

        lastMorningDay = level.getDayTime() / DAY_LENGTH_TICKS;
        nextActionTick = level.getGameTime() + (traded ? SUCCESS_COOLDOWN : FAILURE_COOLDOWN);
    }

    private boolean buyBread(ServerLevel level, BankBlockEntity bank, int requestedQuantity) {
        if (requestedQuantity <= 0 || !hasBreadReserve(level, bank)) {
            return false;
        }

        int quantity = Math.min(requestedQuantity,
                bank.getMarketStock(level, Items.BREAD) - breadVillageReserve(level, bank));
        quantity = Math.min(quantity, breadStorageCapacity());
        MarketTradeQuote quote = quote(level, bank, Items.BREAD, quantity, TradeSide.BUY);
        if (!isValidQuote(quote)) {
            return false;
        }

        ItemStack bread = bank.withdrawExactItem(level, Items.BREAD, quote.quantity());
        if (bread.isEmpty()) {
            return false;
        }
        if (bread.getCount() != quote.quantity()) {
            if (!bank.storeItemInLinkedChests(level, bread)) {
                villager.spawnAtLocation(bread);
            }
            return false;
        }

        ItemStack[] inventoryBeforePurchase = snapshotInventory();
        ItemStack remainder = villager.getInventory().addItem(bread);
        if (!remainder.isEmpty()) {
            restoreInventory(inventoryBeforePurchase);
            ItemStack withdrawnBread = new ItemStack(Items.BREAD, quote.quantity());
            if (!bank.storeItemInLinkedChests(level, withdrawnBread)) {
                villager.spawnAtLocation(withdrawnBread);
            }
            return false;
        }

        BankAccountData.get(level).withdraw(villager.getUUID(), quote.emeraldAmount());
        bank.markInventoryChanged(level);
        return true;
    }

    private ItemStack[] snapshotInventory() {
        ItemStack[] contents = new ItemStack[villager.getInventory().getContainerSize()];
        for (int slot = 0; slot < contents.length; slot++) {
            contents[slot] = villager.getInventory().getItem(slot).copy();
        }
        return contents;
    }

    private void restoreInventory(ItemStack[] contents) {
        for (int slot = 0; slot < contents.length; slot++) {
            villager.getInventory().setItem(slot, contents[slot].copy());
        }
        villager.getInventory().setChanged();
    }

    private boolean sellItem(ServerLevel level, BankBlockEntity bank, Item item,
                             int requestedQuantity, boolean requireBreadReserve) {
        if (requestedQuantity <= 0
                || (requireBreadReserve && !hasBreadReserve(level, bank))) {
            return false;
        }

        boolean sold = false;
        int remaining = requestedQuantity;
        while (remaining > 0) {
            int storageCapacity = bank.getItemStorageCapacity(level, new ItemStack(item, remaining));
            if (storageCapacity <= 0) {
                break;
            }

            int quantity = Math.min(remaining, storageCapacity);
            MarketTradeQuote quote = quote(level, bank, item, quantity, TradeSide.SELL);
            if (!isValidQuote(quote)) {
                break;
            }

            ItemStack offered = new ItemStack(item, quote.quantity());
            if (!bank.storeItemInLinkedChests(level, offered)) {
                break;
            }
            removeItem(item, quote.quantity());
            BankAccountData.get(level).deposit(villager.getUUID(), quote.emeraldAmount());
            bank.markInventoryChanged(level);
            remaining -= quote.quantity();
            sold = true;
        }
        return sold;
    }

    private MarketTradeQuote quote(ServerLevel level, BankBlockEntity bank, Item item,
                                   int quantity, TradeSide side) {
        if (quantity <= 0) {
            return null;
        }
        String marketId = item == Items.PUMPKIN ? "pumpkin" : "bread";
        MarketItem market = MarketRegistry.get(marketId).orElse(null);
        if (market == null) {
            return null;
        }
        return MarketPricingEngine.quote(
                market.config(), bank.getMarketStock(level, item),
                new MarketDemandContext(bank.getMarketPopulation(level), bank.getPumpkinTarget()),
                quantity, side);
    }

    private boolean isValidQuote(MarketTradeQuote quote) {
        return quote != null && quote.valid() && quote.quantity() > 0;
    }

    private boolean hasBreadReserve(ServerLevel level, BankBlockEntity bank) {
        return bank.getMarketStock(level, Items.BREAD) >= breadVillageReserve(level, bank);
    }

    private int breadVillageReserve(ServerLevel level, BankBlockEntity bank) {
        return BankTargets.breadTarget(bank.getMarketPopulation(level), bank.getFoodDays());
    }

    private int countItem(Item item) {
        int total = 0;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int breadStorageCapacity() {
        int capacity = 0;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                capacity += Items.BREAD.getDefaultMaxStackSize();
            } else if (stack.is(Items.BREAD)) {
                capacity += stack.getMaxStackSize() - stack.getCount();
            }
        }
        return capacity;
    }

    private void removeItem(Item item, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < villager.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (!stack.is(item)) {
                continue;
            }
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
            if (stack.isEmpty()) {
                villager.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
    }

    private boolean moveToBank() {
        BlockPos pos = context.navigationTarget();
        if (!villager.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + 0.5,
                pos.getZ() + 0.5, SPEED)) {
            return false;
        }
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(pos, SPEED, 1));
        return true;
    }

    private boolean isAtBank(BlockPos pos) {
        return villager.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5,
                pos.getZ() + 0.5) <= ARRIVAL_DIST_SQ;
    }

    private record WorkContext(BankBlockEntity bank, BlockPos depositPos, BlockPos navigationTarget) {
    }
}
