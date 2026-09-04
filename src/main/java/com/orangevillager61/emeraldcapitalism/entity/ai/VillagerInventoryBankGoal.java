package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.LumberjackProductionAttachment;
import com.orangevillager61.emeraldcapitalism.market.MarketDemandContext;
import com.orangevillager61.emeraldcapitalism.market.MarketItem;
import com.orangevillager61.emeraldcapitalism.market.MarketPricingEngine;
import com.orangevillager61.emeraldcapitalism.market.MarketRegistry;
import com.orangevillager61.emeraldcapitalism.market.MarketTradeQuote;
import com.orangevillager61.emeraldcapitalism.market.TradeSide;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import com.orangevillager61.emeraldcapitalism.util.EmeraldConsolidationUtils;
import com.orangevillager61.emeraldcapitalism.util.VillagerBreedingSessions;
import com.orangevillager61.emeraldcapitalism.util.VillagerInventoryPolicy;
import com.orangevillager61.emeraldcapitalism.world.bank.BankAccountData;
import com.orangevillager61.emeraldcapitalism.world.bank.BankTargets;
import com.orangevillager61.emeraldcapitalism.world.forestry.CharcoalProductionPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumSet;

/** Sends villagers to their village bank for one staggered daily delivery or a full-inventory cleanup. */
public final class VillagerInventoryBankGoal extends Goal {

    public static final int GOAL_PRIORITY = 3;
    private static final long DAY_LENGTH_TICKS = 24_000L;
    private static final long DAY_END_TICK = 12_000L;
    private static final long DAILY_CHECK_START_TICK = 1_000L;
    private static final long DAILY_CHECK_SPAN_TICKS = 6_000L;
    /** Active bank trips may finish after dusk only when the bank is close. */
    public static final double NIGHT_SAFE_BANK_DISTANCE = 20.0D;
    private static final float SPEED = 0.5F;
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
    private long lastDailyCheckDay = -1L;
    private boolean fullInventoryTrigger;
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

        boolean inventoryFull = isInventoryFull();
        if (!isDaytime(level)) {
            return false;
        }
        boolean dailyCheckAvailable = isDailyCheckAvailable(level);
        if (!inventoryFull && !dailyCheckAvailable) {
            return false;
        }

        bank = BankEmployeeLookup.findVillageBank(level, villager);
        if (bank == null || !bank.isVillagerDeliveriesEnabled() || !isDeliveryModeEnabled()) {
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
            return false;
        }
        BankAccountData.get(level).openAccount(villager.getUUID());
        boolean pendingDelivery = hasLiquidatableItems(level);
        boolean pendingBreadPurchase = !inventoryFull && hasPendingBreadPurchase(level);
        if (!pendingDelivery && !pendingBreadPurchase) {
            if (dailyCheckAvailable) {
                lastDailyCheckDay = dayIndex(level);
            }
            nextActionTick = level.getGameTime() + FAILURE_COOLDOWN;
            return false;
        }

        fullInventoryTrigger = inventoryFull;
        return true;
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
            reserveScheduledVisit(level);
            return;
        }

        navigationTarget = VillagerNavigationTargets.findReachableTarget(villager, depositPos, 0);
        if (navigationTarget == null || !moveToBank()) {
            finish(level, FAILURE_COOLDOWN);
        } else {
            reserveScheduledVisit(level);
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
                && isDeliveryModeEnabled()
                && villager.level() instanceof ServerLevel level
                && (isDaytime(level) || isWithinNightSafeBankDistance());
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level)
                || bank == null || depositPos == null) {
            finish(levelOrNull(), FAILURE_COOLDOWN);
            return;
        }

        if (!isDaytime(level) && !isWithinNightSafeBankDistance()) {
            finish(level, FAILURE_COOLDOWN);
            return;
        }

        if (isAtBank()) {
            boolean transferred = liquidateInventory(level);
            boolean purchased = !fullInventoryTrigger && buyBreadIfNeeded(level);
            finish(level, transferred || purchased ? SUCCESS_COOLDOWN : FAILURE_COOLDOWN);
            return;
        }

        if (navigationTarget != null) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(navigationTarget, SPEED, 0));
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
        boolean transferred = bank.depositVillagerEmeralds(level, villager) > 0;
        int logsToReserve = logsReservedForCharcoal();
        int charcoalToReserve = charcoalReservedForProduction();
        int wheatToSell = wheatSaleQuantity(level);
        int breadToSell = breadSaleQuantity(level);
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack held = villager.getInventory().getItem(slot);
            if (held.isEmpty() || held.is(Items.EMERALD) || held.is(Items.EMERALD_BLOCK)
                    || !isDeliverableItem(held)) {
                continue;
            }

            int transferableCount = held.getCount();
            if (held.is(ItemTags.LOGS)) {
                int protectedLogs = Math.min(transferableCount, logsToReserve);
                logsToReserve -= protectedLogs;
                transferableCount -= protectedLogs;
                if (transferableCount <= 0) {
                    continue;
                }
            }
            if (held.is(Items.CHARCOAL)) {
                int protectedCharcoal = Math.min(transferableCount, charcoalToReserve);
                charcoalToReserve -= protectedCharcoal;
                transferableCount -= protectedCharcoal;
                if (transferableCount <= 0) {
                    continue;
                }
            }

            int plannedQuantity = held.getCount();
            if (held.is(Items.WHEAT)) {
                plannedQuantity = Math.min(plannedQuantity, wheatToSell);
            } else if (held.is(Items.BREAD)) {
                plannedQuantity = Math.min(plannedQuantity, breadToSell);
            }
            if (plannedQuantity <= 0) {
                continue;
            }

            while (!held.isEmpty() && transferableCount > 0 && plannedQuantity > 0) {
                MarketItem market = findMarketItem(held);
                int capacity = bank.getItemStorageCapacity(level, held);
                if (capacity <= 0) {
                    break;
                }

                int requested = Math.min(Math.min(transferableCount, plannedQuantity), capacity);
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
                transferableCount -= quantity;
                plannedQuantity -= quantity;
                if (held.is(Items.WHEAT)) {
                    wheatToSell -= quantity;
                } else if (held.is(Items.BREAD)) {
                    breadToSell -= quantity;
                }
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

    private boolean hasLiquidatableItems(ServerLevel level) {
        if (EmeraldConsolidationUtils.countEmeraldValue(villager.getInventory()) > 0
                && bank.getEmeraldStorageCapacity(level) > 0) {
            return true;
        }

        int logsToReserve = logsReservedForCharcoal();
        int charcoalToReserve = charcoalReservedForProduction();
        int wheatToSell = wheatSaleQuantity(level);
        int breadToSell = breadSaleQuantity(level);
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.isEmpty() || !isDeliverableItem(stack)) {
                continue;
            }

            int transferableCount = stack.getCount();
            if (stack.is(ItemTags.LOGS)) {
                int protectedLogs = Math.min(transferableCount, logsToReserve);
                logsToReserve -= protectedLogs;
                transferableCount -= protectedLogs;
            }
            if (stack.is(Items.CHARCOAL)) {
                int protectedCharcoal = Math.min(transferableCount, charcoalToReserve);
                charcoalToReserve -= protectedCharcoal;
                transferableCount -= protectedCharcoal;
            }
            if (stack.is(Items.WHEAT)) {
                transferableCount = Math.min(transferableCount, wheatToSell);
            } else if (stack.is(Items.BREAD)) {
                transferableCount = Math.min(transferableCount, breadToSell);
            }
            if (transferableCount > 0 && hasTransferableQuantity(level, stack, transferableCount)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTransferableQuantity(ServerLevel level, ItemStack stack, int availableQuantity) {
        int capacity = bank.getItemStorageCapacity(level, stack);
        if (capacity <= 0) {
            return false;
        }

        MarketItem market = findMarketItem(stack);
        if (market == null) {
            return true;
        }

        int requested = Math.min(availableQuantity, capacity);
        return isValidQuote(quote(level, market, requested));
    }

    private int logsReservedForCharcoal() {
        if (!isLumberjackDelivery()) {
            return 0;
        }

        int availableLogs = countInventoryItem(ItemTags.LOGS);
        int conversions = pendingCharcoalConversions(availableLogs);
        if (conversions <= 0) {
            return 0;
        }

        int availableCharcoalFuel = countInventoryItem(Items.CHARCOAL);
        int logFuelConversions = Math.max(0, conversions - availableCharcoalFuel);
        return conversions + logFuelConversions;
    }

    private int charcoalReservedForProduction() {
        if (!isLumberjackDelivery()) {
            return 0;
        }
        return Math.min(pendingCharcoalConversions(countInventoryItem(ItemTags.LOGS)),
                countInventoryItem(Items.CHARCOAL));
    }

    private int pendingCharcoalConversions(int availableLogs) {
        LumberjackProductionAttachment production = villager.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION);
        return CharcoalProductionPolicy.wholeConversions(
                production.getCharcoalQuota(), availableLogs);
    }

    private int countInventoryItem(net.minecraft.tags.TagKey<Item> tag) {
        int count = 0;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(tag)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countInventoryItem(Item item) {
        int count = 0;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean isDeliveryModeEnabled() {
        return bank != null && (hasEmeralds()
                || bank.isRandomDeliveriesEnabled()
                || bank.isBreadDeliveriesEnabled() || isLumberjackDelivery());
    }

    private boolean hasEmeralds() {
        return EmeraldConsolidationUtils.countEmeraldValue(villager.getInventory()) > 0;
    }

    private boolean isLumberjackDelivery() {
        return bank != null
                && villager.getVillagerData().getProfession() == ECAPVillagerProfessions.LUMBERJACK.get()
                && bank.isLumberjackDeliveriesEnabled();
    }

    private boolean isDeliverableItem(ItemStack stack) {
        if (bank == null
                || stack.is(Items.EMERALD)
                || stack.is(Items.EMERALD_BLOCK)
                || (stack.is(net.minecraft.world.item.Items.BREAD)
                && !bank.isBreadDeliveriesEnabled())) {
            return false;
        }
        // Bread and surplus farmer wheat are daily bank outputs even though
        // the general inventory policy protects food and farmer inputs.
        boolean dailyBankSale = (stack.is(Items.BREAD) && bank.isBreadDeliveriesEnabled())
                || (stack.is(Items.WHEAT)
                && bank.isRandomDeliveriesEnabled()
                && villager.getVillagerData().getProfession() == VillagerProfession.FARMER);
        if (!dailyBankSale && VillagerInventoryPolicy.isReservedForVillager(villager, stack)) {
            return false;
        }
        if (bank.isRandomDeliveriesEnabled()) {
            return true;
        }
        if (stack.is(Items.BREAD)) {
            return bank.isBreadDeliveriesEnabled();
        }
        return isLumberjackDelivery()
                && (stack.is(ItemTags.LOGS) || stack.is(ItemTags.COALS) || stack.is(Items.STICK));
    }

    private boolean hasPendingBreadPurchase(ServerLevel level) {
        if (bank == null || !bank.isBreadDeliveriesEnabled()) {
            return false;
        }
        int currentBread = countInventoryItem(Items.BREAD);
        int purchaseQuantity = BankTargets.breadPurchaseQuantity(currentBread, bank.getFoodDays());
        if (purchaseQuantity <= 0 || bank.getMarketStock(level, Items.BREAD)
                < breadVillageReserve(level)) {
            return false;
        }
        int quantity = Math.min(purchaseQuantity,
                bank.getMarketStock(level, Items.BREAD) - breadVillageReserve(level));
        quantity = Math.min(quantity, breadStorageCapacity());
        return isValidQuote(quote(level, Items.BREAD, quantity, TradeSide.BUY));
    }

    private boolean buyBreadIfNeeded(ServerLevel level) {
        if (!hasPendingBreadPurchase(level)) {
            return false;
        }
        int currentBread = countInventoryItem(Items.BREAD);
        int requestedQuantity = BankTargets.breadPurchaseQuantity(currentBread, bank.getFoodDays());
        int quantity = Math.min(requestedQuantity,
                bank.getMarketStock(level, Items.BREAD) - breadVillageReserve(level));
        quantity = Math.min(quantity, breadStorageCapacity());
        MarketTradeQuote quote = quote(level, Items.BREAD, quantity, TradeSide.BUY);
        if (!isValidQuote(quote)) {
            return false;
        }

        ItemStack bread = bank.withdrawExactItem(level, Items.BREAD, quote.quantity());
        if (bread.isEmpty() || bread.getCount() != quote.quantity()) {
            if (!bread.isEmpty() && !bank.storeItemInLinkedChests(level, bread)) {
                com.orangevillager61.emeraldcapitalism.util.EntityDropUtils.spawn(villager, level, bread);
            }
            return false;
        }

        ItemStack[] inventoryBeforePurchase = snapshotInventory();
        ItemStack remainder = villager.getInventory().addItem(bread);
        if (!remainder.isEmpty()) {
            restoreInventory(inventoryBeforePurchase);
            if (!bank.storeItemInLinkedChests(level, bread)) {
                com.orangevillager61.emeraldcapitalism.util.EntityDropUtils.spawn(villager, level, bread);
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

    private MarketTradeQuote quote(ServerLevel level, Item item, int quantity, TradeSide side) {
        if (quantity <= 0) {
            return null;
        }
        MarketItem market = findMarketItem(new ItemStack(item));
        if (market == null) {
            return null;
        }
        MarketDemandContext context = new MarketDemandContext(
                bank.getMarketPopulation(level), bank.getMarketTarget(level, market));
        return MarketPricingEngine.quote(
                market.config(), bank.getMarketStock(level, market.item()), context,
                quantity, side);
    }

    private int breadSaleQuantity(ServerLevel level) {
        if (bank == null || !bank.isBreadDeliveriesEnabled()) {
            return 0;
        }
        int available = BankTargets.breadSaleQuantity(
                countInventoryItem(Items.BREAD), bank.getFoodDays());
        return Math.min(available,
                bank.getItemStorageCapacity(level, new ItemStack(Items.BREAD, available)));
    }

    private int wheatSaleQuantity(ServerLevel level) {
        if (bank == null || !bank.isRandomDeliveriesEnabled()
                || villager.getVillagerData().getProfession() != VillagerProfession.FARMER) {
            return 0;
        }
        int available = BankTargets.wheatSaleQuantity(countInventoryItem(Items.WHEAT));
        if (available <= 0) {
            return 0;
        }
        int capacity = bank.getItemStorageCapacity(level, new ItemStack(Items.WHEAT, available));
        return Math.min(available, capacity) / BankTargets.WHEAT_TRADE_BATCH
                * BankTargets.WHEAT_TRADE_BATCH;
    }

    private int breadVillageReserve(ServerLevel level) {
        return BankTargets.breadTarget(bank.getMarketPopulation(level), bank.getFoodDays());
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
                new WalkTarget(navigationTarget, SPEED, 0));
        return true;
    }

    private boolean isAtBank() {
        return bank != null && BankBlock.isAtDepositApproach(
                bank.getBlockState(), bank.getBlockPos(), villager.position());
    }

    private boolean isDailyCheckAvailable(ServerLevel level) {
        long timeOfDay = Math.floorMod(level.getDayTime(), DAY_LENGTH_TICKS);
        return timeOfDay >= DAILY_CHECK_START_TICK
                && timeOfDay < DAY_END_TICK
                && dayIndex(level) != lastDailyCheckDay
                && timeOfDay >= DAILY_CHECK_START_TICK
                + Math.floorMod(villager.getUUID().getLeastSignificantBits(), DAILY_CHECK_SPAN_TICKS);
    }

    /** Reserves the daily window only after the selector has actually started the trip. */
    private void reserveScheduledVisit(ServerLevel level) {
        if (fullInventoryTrigger || isDailyCheckAvailable(level)) {
            lastDailyCheckDay = dayIndex(level);
        }
    }

    private static boolean isDaytime(ServerLevel level) {
        return Math.floorMod(level.getDayTime(), DAY_LENGTH_TICKS) < DAY_END_TICK;
    }

    private static long dayIndex(ServerLevel level) {
        return Math.floorDiv(level.getDayTime(), DAY_LENGTH_TICKS);
    }

    private boolean isWithinNightSafeBankDistance() {
        if (bank == null) {
            return false;
        }
        BlockPos bankPos = bank.getBlockPos();
        double deltaX = villager.getX() - (bankPos.getX() + 0.5D);
        double deltaZ = villager.getZ() - (bankPos.getZ() + 0.5D);
        return deltaX * deltaX + deltaZ * deltaZ
                <= NIGHT_SAFE_BANK_DISTANCE * NIGHT_SAFE_BANK_DISTANCE;
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
