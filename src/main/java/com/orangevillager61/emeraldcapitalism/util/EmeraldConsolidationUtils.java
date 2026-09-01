package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Function;

public final class EmeraldConsolidationUtils {

    private static final int EMERALD_CONSOLIDATION_THRESHOLD = 56;

    private EmeraldConsolidationUtils() {
    }

    public static void consolidateEmeralds(SimpleContainer inventory) {
        int emeraldCount = countItem(inventory, Items.EMERALD);
        if (emeraldCount <= EMERALD_CONSOLIDATION_THRESHOLD) {
            return;
        }

        int blocksToCraft = emeraldCount / 9;
        if (blocksToCraft <= 0) {
            return;
        }

        ItemStack blockStack = new ItemStack(Items.EMERALD_BLOCK, blocksToCraft);
        ItemStack remainder = inventory.addItem(blockStack);
        int addedBlocks = blocksToCraft - remainder.getCount();
        if (addedBlocks <= 0) {
            return;
        }

        removeItems(inventory, Items.EMERALD, addedBlocks * 9);
    }

    public static int countItem(Container inventory, Item item) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slotStack = inventory.getItem(i);
            if (slotStack.is(item)) {
                total += slotStack.getCount();
            }
        }
        return total;
    }

    /**
     * Counts the inventory's emerald value, treating each emerald block as nine
     * emeralds. This is the value used by bank deposits and villager eligibility
     * checks; it is intentionally separate from {@link #countItem}, which counts
     * physical stacks of one item type.
     */
    public static int countEmeraldValue(Container inventory) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            total += countEmeraldValue(inventory.getItem(i));
        }
        return total;
    }

    /** Counts the emerald value represented by one stack. */
    public static int countEmeraldValue(ItemStack stack) {
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

    /**
     * Removes exactly {@code amount} emerald value, using raw emeralds first and
     * breaking one emerald block for any fractional remainder. The operation is
     * atomic: a container is restored if the block change cannot fit.
     */
    public static boolean removeEmeraldValueExact(Container inventory, int amount) {
        return removeEmeraldValueExact(inventory, amount, stack -> addItem(inventory, stack));
    }

    /**
     * Removes exactly emerald value using the player's normal insertion rules for
     * any change created by breaking a block.
     */
    public static boolean removeEmeraldValueExact(Inventory inventory, int amount) {
        return removeEmeraldValueExact(inventory, amount,
                stack -> inventory.add(stack) && stack.isEmpty());
    }

    private static boolean removeEmeraldValueExact(
            Container inventory, int amount, Function<ItemStack, Boolean> addChange) {
        if (amount < 0) {
            throw new IllegalArgumentException("Emerald removal amount must not be negative");
        }
        if (amount == 0) {
            return true;
        }
        if (countEmeraldValue(inventory) < amount) {
            return false;
        }

        ItemStack[] originalContents = snapshot(inventory);
        int remaining = amount;
        remaining -= removeItemsCount(inventory, Items.EMERALD, remaining);
        if (remaining > 0) {
            int blocksToRemove = (remaining + 8) / 9;
            int removedBlocks = removeItemsCount(inventory, Items.EMERALD_BLOCK, blocksToRemove);
            if (removedBlocks != blocksToRemove) {
                restore(inventory, originalContents);
                return false;
            }

            int change = blocksToRemove * 9 - remaining;
            if (change > 0 && !addChange.apply(new ItemStack(Items.EMERALD, change))) {
                restore(inventory, originalContents);
                return false;
            }
        }

        inventory.setChanged();
        return true;
    }

    public static void removeItems(Container inventory, Item item, int amount) {
        removeItemsCount(inventory, item, amount);
    }

    private static int removeItemsCount(Container inventory, Item item, int amount) {
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

    private static ItemStack[] snapshot(Container inventory) {
        ItemStack[] contents = new ItemStack[inventory.getContainerSize()];
        for (int i = 0; i < contents.length; i++) {
            contents[i] = inventory.getItem(i).copy();
        }
        return contents;
    }

    private static void restore(Container inventory, ItemStack[] contents) {
        for (int i = 0; i < contents.length; i++) {
            inventory.setItem(i, contents[i].copy());
        }
        inventory.setChanged();
    }

    private static boolean addItem(Container inventory, ItemStack source) {
        int remaining = source.getCount();
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            if (!inventory.canPlaceItem(i, source)) {
                continue;
            }

            ItemStack stored = inventory.getItem(i);
            if (stored.isEmpty()) {
                int added = Math.min(remaining, Math.min(source.getMaxStackSize(),
                        inventory.getMaxStackSize(source)));
                if (added > 0) {
                    inventory.setItem(i, source.copyWithCount(added));
                    remaining -= added;
                }
            } else if (ItemStack.isSameItemSameComponents(stored, source)) {
                int capacity = Math.min(stored.getMaxStackSize(), inventory.getMaxStackSize(source))
                        - stored.getCount();
                int added = Math.min(remaining, Math.max(0, capacity));
                if (added > 0) {
                    stored.grow(added);
                    inventory.setItem(i, stored);
                    remaining -= added;
                }
            }
        }
        return remaining == 0;
    }
}
