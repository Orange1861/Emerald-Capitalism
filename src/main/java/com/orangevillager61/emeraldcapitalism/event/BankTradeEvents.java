package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import com.orangevillager61.emeraldcapitalism.util.EmeraldConsolidationUtils;
import com.orangevillager61.emeraldcapitalism.world.bank.BankAccountData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;

import java.util.UUID;

/** Applies bank-backed payment after a villager trade. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public class BankTradeEvents {

    @SubscribeEvent
    public static void onTradeWithVillager(TradeWithVillagerEvent event) {
        MerchantOffer offer = event.getMerchantOffer();
        ItemStack result = offer.getResult();
        int emeraldsOwed = EmeraldConsolidationUtils.countEmeraldValue(result);
        if (emeraldsOwed <= 0) return;

        if (!(event.getAbstractVillager() instanceof Villager villager)) return;
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;

        UUID uuid = villager.getUUID();

        BankAccountData accountData = BankAccountData.get(serverLevel);
        if (!accountData.hasAccount(uuid)) return;

        ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            reverseUnresolvedBankTrade(event, offer, "overworld unavailable");
            return;
        }

        BankBlockEntity bank = BankEmployeeLookup.findVillageBank(overworld, villager);
        if (bank == null) {
            reverseUnresolvedBankTrade(event, offer, "bank linkage unavailable");
            return;
        }

        SimpleContainer inv = villager.getInventory();
        int invEmeralds = EmeraldConsolidationUtils.countEmeraldValue(inv);

        int inventoryContribution = Math.min(invEmeralds, emeraldsOwed);
        ItemStack[] inventoryBeforePayment = inventoryContribution > 0
                ? snapshotInventory(inv)
                : null;
        if (inventoryContribution > 0
                && !EmeraldConsolidationUtils.removeEmeraldValueExact(inv, inventoryContribution)) {
            // A fractional block payment needs room for change. Let the bank fund
            // the whole result when the villager cannot represent that change.
            inventoryContribution = 0;
        }

        int shortfall = emeraldsOwed - inventoryContribution;

        if (bank.getLiveEmeraldValue(overworld) < shortfall) {
            restoreInventory(inv, inventoryBeforePayment);
            // The event is post-exchange and non-cancellable; reclaim the result and
            // refund costs before MerchantResultSlot consumes the cost slots.
            Player player = event.getEntity();
            reclaimTradeResultFromPlayer(player, result);
            refundCostItems(player, offer);
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(
                        Component.literal("The bank has insufficient funds.")
                                .withStyle(ChatFormatting.RED));
            }
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP/Bank] Trade reversed for {}, "
                            + "bank chests hold {}, shortfall was {}",
                    player.getDisplayName().getString(),
                    bank.getTotalEmeraldCount(), shortfall);
            return;
        }

        if (!bank.withdrawFromLinkedChests(overworld, shortfall)) {
            restoreInventory(inv, inventoryBeforePayment);
            // The live preflight and withdrawal must agree. A chest can be broken
            // between cache refreshes, so do not debit an account for an unpaid trade.
            Player player = event.getEntity();
            reclaimTradeResultFromPlayer(player, result);
            refundCostItems(player, offer);
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(
                        Component.literal("The bank funds changed; the trade was reversed.")
                                .withStyle(ChatFormatting.RED));
            }
            EmeraldCapitalism.LOGGER.info(
                    "[ECAP/Bank] Trade reversed for {} because bank withdrawal failed",
                    player.getDisplayName().getString());
            return;
        }
        accountData.withdraw(uuid, shortfall);
        villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS).markBankTradeResultHandled();

        EmeraldCapitalism.LOGGER.debug(
                "[ECAP/Bank] Villager {} trade: {} from inventory, {} from bank chests",
                uuid, inventoryContribution, shortfall);
    }

    private static ItemStack[] snapshotInventory(SimpleContainer inventory) {
        ItemStack[] contents = new ItemStack[inventory.getContainerSize()];
        for (int slot = 0; slot < contents.length; slot++) {
            contents[slot] = inventory.getItem(slot).copy();
        }
        return contents;
    }

    private static void restoreInventory(SimpleContainer inventory, ItemStack[] contents) {
        if (contents == null) {
            return;
        }
        for (int slot = 0; slot < contents.length; slot++) {
            inventory.setItem(slot, contents[slot].copy());
        }
        inventory.setChanged();
    }

    /** Removes the just-completed emerald result when a post-trade reversal is required. */
    private static void reclaimTradeResultFromPlayer(Player player, ItemStack result) {
        Inventory inv = player.getInventory();
        int remaining = result.getCount();
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!ItemStack.isSameItemSameComponents(stack, result)) continue;
            int take = Math.min(stack.getCount(), remaining);
            stack.shrink(take);
            inv.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
            remaining -= take;
        }
    }

    /**
     * Returns cost items before the post-exchange merchant handler consumes them.
     * This keeps the refund available even if the trading UI closes immediately.
     */
    private static void refundCostItems(Player player, MerchantOffer offer) {
        Inventory inv = player.getInventory();
        ItemStack costA = offer.getCostA();
        if (!costA.isEmpty()) {
            ItemStack toAddA = costA.copy();
            if (!inv.add(toAddA) && !toAddA.isEmpty()) {
                player.drop(toAddA, false);
            }
        }
        ItemStack costB = offer.getCostB();
        if (!costB.isEmpty()) {
            ItemStack toAddB = costB.copy();
            if (!inv.add(toAddB) && !toAddB.isEmpty()) {
                player.drop(toAddB, false);
            }
        }
    }

    /** Reverses a post-exchange trade when an existing account has lost its bank linkage. */
    private static void reverseUnresolvedBankTrade(
            TradeWithVillagerEvent event, MerchantOffer offer, String reason) {
        Player player = event.getEntity();
        if (event.getAbstractVillager() instanceof Villager villager) {
            VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
            stats.skipNextTradeAccounting();
        }
        reclaimTradeResultFromPlayer(player, offer.getResult());
        refundCostItems(player, offer);
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(
                    Component.literal("The bank link was unavailable; the trade was reversed.")
                            .withStyle(ChatFormatting.RED));
        }
        EmeraldCapitalism.LOGGER.error(
                "[ECAP/Bank] Reversed trade for villager {} because bank linkage was unavailable: {}",
                event.getAbstractVillager().getUUID(), reason);
    }
}
