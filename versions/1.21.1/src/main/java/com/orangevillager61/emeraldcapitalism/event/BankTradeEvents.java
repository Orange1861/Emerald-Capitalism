package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity;
import com.orangevillager61.emeraldcapitalism.util.EmeraldConsolidationUtils;
import com.orangevillager61.emeraldcapitalism.world.bank.BankAccountData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        if (!result.is(Items.EMERALD)) return;
        int emeraldsOwed = result.getCount();

        if (!(event.getAbstractVillager() instanceof Villager villager)) return;
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;

        UUID uuid = villager.getUUID();

        BankAccountData accountData = BankAccountData.get(serverLevel);
        if (!accountData.hasAccount(uuid)) return;

        ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            reverseUnresolvedBankTrade(event, offer, emeraldsOwed, "overworld unavailable");
            return;
        }

        VillageRegistryData registryData = VillageRegistryData.get(overworld);
        VillageRecord village = registryData.getVillageFor(villager.blockPosition());
        if (village == null) {
            reverseUnresolvedBankTrade(event, offer, emeraldsOwed, "village unavailable");
            return;
        }

        BlockPos vmPos = registryData.getVMPos(village.getVillageId());
        if (vmPos == null) {
            reverseUnresolvedBankTrade(event, offer, emeraldsOwed, "village manager position unavailable");
            return;
        }
        if (!(overworld.getBlockEntity(vmPos) instanceof VillageManagerBlockEntity vm)) {
            reverseUnresolvedBankTrade(event, offer, emeraldsOwed, "village manager unavailable");
            return;
        }

        BlockPos bankPos = vm.getBankPos();
        if (bankPos == null) {
            reverseUnresolvedBankTrade(event, offer, emeraldsOwed, "bank position unavailable");
            return;
        }
        if (!(overworld.getBlockEntity(bankPos) instanceof BankBlockEntity bank)) {
            reverseUnresolvedBankTrade(event, offer, emeraldsOwed, "bank block unavailable");
            return;
        }

        SimpleContainer inv = villager.getInventory();
        int invEmeralds = EmeraldConsolidationUtils.countItem(inv, Items.EMERALD);

        if (invEmeralds >= emeraldsOwed) {
            EmeraldConsolidationUtils.removeItems(inv, Items.EMERALD, emeraldsOwed);
            return;
        }

        int shortfall = emeraldsOwed - invEmeralds;

        if (bank.getLiveEmeraldValue(overworld) < shortfall) {
            // The event is post-exchange and non-cancellable; reclaim the result and
            // refund costs before MerchantResultSlot consumes the cost slots.
            Player player = event.getEntity();
            reclaimEmeraldsFromPlayer(player, emeraldsOwed);
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

        EmeraldConsolidationUtils.removeItems(inv, Items.EMERALD, invEmeralds);
        if (!bank.withdrawFromLinkedChests(overworld, shortfall)) {
            // The live preflight and withdrawal must agree. A chest can be broken
            // between cache refreshes, so do not debit an account for an unpaid trade.
            Player player = event.getEntity();
            reclaimEmeraldsFromPlayer(player, emeraldsOwed);
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

        EmeraldCapitalism.LOGGER.debug(
                "[ECAP/Bank] Villager {} trade: {} from inventory, {} from bank chests",
                uuid, invEmeralds, shortfall);
    }

    /**
     * Removes up to {@code amount} raw emeralds from the player's {@link Inventory}.
     * Called when the bank cannot cover the payment.
     */
    private static void reclaimEmeraldsFromPlayer(Player player, int amount) {
        Inventory inv = player.getInventory();
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.is(Items.EMERALD)) continue;
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
            TradeWithVillagerEvent event, MerchantOffer offer, int emeraldsOwed, String reason) {
        Player player = event.getEntity();
        reclaimEmeraldsFromPlayer(player, emeraldsOwed);
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
