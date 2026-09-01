package com.orangevillager61.emeraldcapitalism.market;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import com.orangevillager61.emeraldcapitalism.util.EmeraldConsolidationUtils;
import com.orangevillager61.emeraldcapitalism.world.bank.BankReputationData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Server-side adapter that commits a quoted trade against player and chest inventories. */
public final class MarketTradeService {
    public static final int MAX_TRADE_QUANTITY = 4096;
    public static final int MAP_SALE_BANK_OPINION_THRESHOLD = 100;

    private MarketTradeService() {
    }

    public record Result(boolean success, String message) {
        static Result ok() { return new Result(true, ""); }
        static Result failed(String message) { return new Result(false, message); }
    }

    public static Result execute(ServerPlayer player, BankBlockEntity bank, MarketItem marketItem,
                                 int quantity, TradeSide side) {
        return execute(player, bank, marketItem, quantity, side, false);
    }

    /**
     * Executes a player market action. A donation uses the SELL quote and stores
     * the offered items, but converts the emerald payout into bank reputation.
     */
    public static Result execute(ServerPlayer player, BankBlockEntity bank, MarketItem marketItem,
                                 int quantity, TradeSide side, boolean donation) {
        ServerLevel level = player.serverLevel();
        if (donation && side != TradeSide.SELL) {
            return Result.failed("Items can only be donated to a bank.");
        }
        if (quantity <= 0) {
            return Result.failed("Trade quantity must be positive.");
        }
        if (quantity > MAX_TRADE_QUANTITY) {
            return Result.failed("That trade is too large; split it into smaller trades.");
        }
        int stock = bank.getMarketStock(level, marketItem.item());
        int population = bank.getMarketPopulation(level);
        MarketDemandContext demandContext = new MarketDemandContext(
                population, bank.getMarketTarget(level, marketItem));
        MarketTradeQuote quote = MarketPricingEngine.quote(
                marketItem.config(), stock, demandContext, quantity, side);
        if (!quote.valid()) {
            return Result.failed(switch (quote.invalidReason()) {
                case "quantity_below_minimum" -> "Trade quantity is below the minimum size.";
                case "fixed_trade_direction" -> "That fixed trade is only available in one direction.";
                case "fixed_trade_size" -> "That quantity does not complete a fixed trade batch.";
                case "insufficient_market_stock" -> "The bank does not have enough stock.";
                case "market_refuses_buying" -> "The bank is not currently buying this item.";
                case "trade_batch_size" -> "That quantity does not complete the next dynamic trade batch.";
                default -> "That trade is not currently valid.";
            });
        }
        int tradeQuantity = quote.quantity();
        if (side == TradeSide.BUY) {
            Result permission = checkMapSalePermission(player, marketItem, level, side);
            if (!permission.success()) {
                return permission;
            }
            return buy(player, bank, marketItem.item(), tradeQuantity, quote.emeraldAmount(), level);
        }
        if (donation) {
            return donate(player, bank, marketItem.item(), tradeQuantity, quote.emeraldAmount(), level);
        }
        return sell(player, bank, marketItem.item(), tradeQuantity, quote.emeraldAmount(), level);
    }

    private static Result checkMapSalePermission(ServerPlayer player, MarketItem marketItem,
                                                  ServerLevel level,
                                                  TradeSide side) {
        if (side != TradeSide.BUY || marketItem.item() != ECAPItems.ABANDONED_VAULT_MAP.get()) {
            return Result.ok();
        }
        int opinion = BankReputationData.get(level).getReputation(player.getUUID());
        return opinion >= MAP_SALE_BANK_OPINION_THRESHOLD
                ? Result.ok()
                : Result.failed("The bank requires bank opinion of at least +"
                + MAP_SALE_BANK_OPINION_THRESHOLD + " to sell this map.");
    }

    private static Result buy(ServerPlayer player, BankBlockEntity bank, Item item, int quantity,
                              int cost, ServerLevel level) {
        Inventory inventory = player.getInventory();
        if (EmeraldConsolidationUtils.countEmeraldValue(inventory) < cost) {
            return Result.failed("You cannot afford that trade in emeralds.");
        }
        ItemStack itemStack = new ItemStack(item, quantity);
        if (!canAccept(inventory, itemStack)) {
            return Result.failed("You do not have enough inventory space.");
        }
        if (bank.getItemStorageCapacity(level, new ItemStack(Items.EMERALD, cost)) < cost) {
            return Result.failed("The bank has no room for the payment.");
        }

        ItemStack withdrawn = bank.withdrawExactItem(level, item, quantity);
        if (withdrawn.isEmpty()) {
            return Result.failed("The bank stock changed; please try again.");
        }
        ItemStack[] inventoryBeforePayment = snapshotInventory(inventory);
        if (!EmeraldConsolidationUtils.removeEmeraldValueExact(inventory, cost)) {
            bank.storeItemInLinkedChests(level, withdrawn);
            return Result.failed("You do not have enough inventory space for emerald change.");
        }
        ItemStack payment = new ItemStack(Items.EMERALD, cost);
        if (!bank.storeItemInLinkedChests(level, payment)) {
            // The capacity preflight should make this unreachable; restore both sides if a chest changed.
            restoreInventory(inventory, inventoryBeforePayment);
            bank.storeItemInLinkedChests(level, withdrawn);
            return Result.failed("The bank payment transfer failed; nothing was committed.");
        }
        inventory.add(withdrawn);
        bank.markInventoryChanged(level);
        return Result.ok();
    }

    private static ItemStack[] snapshotInventory(Inventory inventory) {
        ItemStack[] contents = new ItemStack[inventory.getContainerSize()];
        for (int slot = 0; slot < contents.length; slot++) {
            contents[slot] = inventory.getItem(slot).copy();
        }
        return contents;
    }

    private static void restoreInventory(Inventory inventory, ItemStack[] contents) {
        for (int slot = 0; slot < contents.length; slot++) {
            inventory.setItem(slot, contents[slot].copy());
        }
        inventory.setChanged();
    }

    private static Result sell(ServerPlayer player, BankBlockEntity bank, Item item, int quantity,
                               int payout, ServerLevel level) {
        Inventory inventory = player.getInventory();
        boolean logResource = BankBlockEntity.isLogMarketItem(item);
        boolean coalResource = BankBlockEntity.isCoalMarketItem(item);
        if (countItem(inventory, item, logResource, coalResource) < quantity) {
            return Result.failed("You do not hold enough of that item.");
        }
        if (!canAccept(inventory, new ItemStack(Items.EMERALD, payout))) {
            return Result.failed("You do not have enough inventory space for the payout.");
        }
        if (bank.getLiveEmeraldValue(level) < payout) {
            return Result.failed("The bank cannot afford that payout.");
        }
        ItemStack offered = new ItemStack(item, quantity);
        if (bank.getItemStorageCapacity(level, offered) < quantity) {
            return Result.failed("The bank has no room for that item.");
        }

        removeItem(inventory, item, quantity, logResource, coalResource);
        if (!bank.storeItemInLinkedChests(level, offered)) {
            inventory.add(new ItemStack(item, quantity));
            return Result.failed("The bank storage changed; please try again.");
        }
        if (!bank.withdrawFromLinkedChests(level, payout)) {
            bank.withdrawExactItem(level, item, quantity);
            inventory.add(new ItemStack(item, quantity));
            return Result.failed("The bank payout changed; nothing was committed.");
        }
        inventory.add(new ItemStack(Items.EMERALD, payout));
        bank.markInventoryChanged(level);
        return Result.ok();
    }

    private static Result donate(ServerPlayer player, BankBlockEntity bank, Item item, int quantity,
                                 int opinionGain, ServerLevel level) {
        Inventory inventory = player.getInventory();
        boolean logResource = BankBlockEntity.isLogMarketItem(item);
        boolean coalResource = BankBlockEntity.isCoalMarketItem(item);
        if (opinionGain <= 0) {
            return Result.failed("That donation would not improve the bank's opinion of you.");
        }
        if (countItem(inventory, item, logResource, coalResource) < quantity) {
            return Result.failed("You do not hold enough of that item.");
        }
        ItemStack offered = new ItemStack(item, quantity);
        if (bank.getItemStorageCapacity(level, offered) < quantity) {
            return Result.failed("The bank has no room for that item.");
        }

        removeItem(inventory, item, quantity, logResource, coalResource);
        if (!bank.storeItemInLinkedChests(level, offered)) {
            inventory.add(new ItemStack(item, quantity));
            return Result.failed("The bank storage changed; nothing was donated.");
        }
        bank.markInventoryChanged(level);
        BankReputationData.get(level).adjustReputation(
                player.getUUID(), opinionGain * BankReputationData.DONATION_OPINION_PER_EMERALD);
        return Result.ok();
    }

    private static int countItem(Inventory inventory, Item item) {
        return countItem(inventory, item, false);
    }

    private static int countItem(Inventory inventory, Item item, boolean logResource) {
        return countItem(inventory, item, logResource, false);
    }

    private static int countItem(Inventory inventory, Item item, boolean logResource, boolean coalResource) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (logResource ? stack.is(ItemTags.LOGS)
                    : coalResource ? stack.is(ItemTags.COALS) : stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean canAccept(Inventory inventory, ItemStack incoming) {
        int remaining = incoming.getCount();
        // Inventory.add targets the main/hotbar item list, not armor or off-hand slots.
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stored = inventory.getItem(slot);
            if (stored.isEmpty()) {
                remaining -= incoming.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(stored, incoming)) {
                remaining -= stored.getMaxStackSize() - stored.getCount();
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return remaining <= 0;
    }

    private static void removeItem(Inventory inventory, Item item, int amount, boolean logResource) {
        removeItem(inventory, item, amount, logResource, false);
    }

    private static void removeItem(Inventory inventory, Item item, int amount,
                                   boolean logResource, boolean coalResource) {
        int remaining = amount;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!(logResource ? stack.is(ItemTags.LOGS)
                    : coalResource ? stack.is(ItemTags.COALS) : stack.is(item))) {
                continue;
            }
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
    }

    private static void removeItem(Inventory inventory, Item item, int amount) {
        removeItem(inventory, item, amount, false);
    }
}
