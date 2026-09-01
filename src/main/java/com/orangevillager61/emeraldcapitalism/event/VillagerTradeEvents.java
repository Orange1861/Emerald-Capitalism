package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.util.EmeraldConsolidationUtils;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;

/**
 * Applies the villager's emerald accounting after a trade completes.
 *
 * <p>NeoForge 21.1.219 posts {@link TradeWithVillagerEvent} from the end of
 * {@code AbstractVillager.notifyTrade}. Vanilla has already consumed both payment
 * slots before that method is called, and there is no vanilla work after the event
 * post. LOWEST preserves the old {@code notifyTrade} tail ordering relative to the
 * mod's bank-trade listener.</p>
 *
 * <p>The feature invariant is that one completed server-side trade changes both the
 * attachment balance and physical emerald inventory by the exact net emerald value:
 * cost A plus cost B, minus the result; emerald blocks are worth nine and a negative
 * balance is valid.</p>
 */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class VillagerTradeEvents {

    private VillagerTradeEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTradeWithVillager(TradeWithVillagerEvent event) {
        if (!(event.getAbstractVillager() instanceof Villager villager)
                || villager.level().isClientSide()) {
            return;
        }

        MerchantOffer offer = event.getMerchantOffer();
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        if (stats.consumeSkipNextTradeAccounting()) {
            return;
        }
        boolean bankHandledResult = stats.consumeBankTradeResultHandled();

        int emeraldsGained = countEmeraldsInStack(offer.getCostA())
                + countEmeraldsInStack(offer.getCostB());
        int emeraldsLost = countEmeraldsInStack(offer.getResult());
        int netChange = emeraldsGained - emeraldsLost;

        if (netChange != 0) {
            stats.addEmeralds(netChange);
            if (bankHandledResult) {
                if (emeraldsGained > 0) {
                    addEmeraldValue(villager, villager.getInventory(), emeraldsGained);
                }
            } else {
                applyEmeraldInventoryChange(villager, netChange);
            }
            EmeraldCapitalism.LOGGER.debug("Villager {} trade: gained={}, lost={}, net={}, new balance={}",
                    villager.getDisplayName().getString(),
                    emeraldsGained, emeraldsLost, netChange, stats.getEmeraldBalance());
        } else if (bankHandledResult && emeraldsGained > 0) {
            addEmeraldValue(villager, villager.getInventory(), emeraldsGained);
        }
    }

    private static int countEmeraldsInStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.is(Items.EMERALD)) {
            return stack.getCount();
        }
        if (stack.is(Items.EMERALD_BLOCK)) {
            return stack.getCount() * 9;
        }
        return 0;
    }

    private static void applyEmeraldInventoryChange(Villager villager, int netChange) {
        SimpleContainer inventory = villager.getInventory();
        if (netChange > 0) {
            addEmeraldValue(villager, inventory, netChange);
        } else {
            removeEmeraldValue(inventory, -netChange);
        }
        EmeraldConsolidationUtils.consolidateEmeralds(inventory);
    }

    private static void addEmeraldValue(Villager villager, SimpleContainer inventory, int amount) {
        int blocks = amount / 9;
        int remainder = amount % 9;
        if (blocks > 0) {
            addEmeraldStack(villager, inventory, new ItemStack(Items.EMERALD_BLOCK, blocks));
        }
        if (remainder > 0) {
            addEmeraldStack(villager, inventory, new ItemStack(Items.EMERALD, remainder));
        }
    }

    private static void addEmeraldStack(Villager villager, SimpleContainer inventory, ItemStack stack) {
        ItemStack remainder = inventory.addItem(stack);
        if (remainder.isEmpty()) {
            return;
        }

        if (!(villager.level() instanceof net.minecraft.server.level.ServerLevel level)
                || com.orangevillager61.emeraldcapitalism.util.EntityDropUtils.spawn(villager, level, remainder) == null) {
            VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
            int lostValue = countEmeraldsInStack(remainder);
            if (lostValue > 0) {
                stats.subtractEmeralds(lostValue);
            }
        }
    }

    private static void removeEmeraldValue(SimpleContainer inventory, int amount) {
        int remaining = amount;
        remaining -= removeItem(inventory, Items.EMERALD, remaining);
        if (remaining <= 0) {
            return;
        }

        int blocksToRemove = remaining / 9;
        if (blocksToRemove > 0) {
            remaining -= removeItem(inventory, Items.EMERALD_BLOCK, blocksToRemove) * 9;
        }
        if (remaining <= 0) {
            return;
        }

        if (removeItem(inventory, Items.EMERALD_BLOCK, 1) > 0) {
            int change = 9 - remaining;
            if (change > 0) {
                inventory.addItem(new ItemStack(Items.EMERALD, change));
            }
        }
    }

    private static int removeItem(SimpleContainer inventory, Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (remaining <= 0) {
                break;
            }
            ItemStack slotStack = inventory.getItem(i);
            if (!slotStack.is(item)) {
                continue;
            }
            int toRemove = Math.min(remaining, slotStack.getCount());
            slotStack.shrink(toRemove);
            remaining -= toRemove;
            if (slotStack.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        return amount - remaining;
    }
}
