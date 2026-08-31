package com.orangevillager61.emeraldcapitalism.menu;

import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldOreProcessorBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

public class EmeraldOreProcessorMenu extends AbstractContainerMenu {

    private final Container container;
    private final ContainerData data;

    // Primary constructor (used by both server and client)
    public EmeraldOreProcessorMenu(int containerId, Inventory playerInventory,
                                    Container container, ContainerData data) {
        super(ECAPMenuTypes.EMERALD_ORE_PROCESSOR_MENU.get(), containerId);
        this.container = container;
        this.data = data;

        // Input slot
        this.addSlot(new Slot(container, EmeraldOreProcessorBlockEntity.SLOT_INPUT, 56, 17) {
            @Override
            public boolean mayPlace(@NotNull ItemStack stack) {
                return EmeraldOreProcessorBlockEntity.isValidInput(stack);
            }
        });

        // Fuel slot
        this.addSlot(new Slot(container, EmeraldOreProcessorBlockEntity.SLOT_FUEL, 56, 53) {
            @Override
            public boolean mayPlace(@NotNull ItemStack stack) {
                return EmeraldOreProcessorBlockEntity.isValidFuel(stack);
            }
        });

        // Output slot
        this.addSlot(new Slot(container, EmeraldOreProcessorBlockEntity.SLOT_OUTPUT, 116, 35) {
            @Override
            public boolean mayPlace(@NotNull ItemStack stack) {
                return false;
            }
        });

        // Player inventory
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }

        // Player hotbar
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }

        this.addDataSlots(data);
    }

    // Client-side constructor
    public EmeraldOreProcessorMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        this(containerId, playerInventory, new SimpleContainerWrapper(), new SimpleContainerData(4));
    }

    public boolean isLit() {
        return data.get(0) > 0;
    }

    public float getLitProgress() {
        int burnTime = data.get(0);
        int burnDuration = data.get(1);
        if (burnDuration == 0) burnDuration = 200;
        return (float) burnTime / (float) burnDuration;
    }

    public float getBurnProgress() {
        int cookProgress = data.get(2);
        int cookTotalTime = data.get(3);
        return cookTotalTime != 0 && cookProgress != 0 ? (float) cookProgress / (float) cookTotalTime : 0.0F;
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);

        if (slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            result = slotStack.copy();

            // Output slot or processor slots -> player inventory
            if (index < 3) {
                if (!this.moveItemStackTo(slotStack, 3, 39, true)) {
                    return ItemStack.EMPTY;
                }
            }
            // Player inventory -> processor slots
            else {
                if (EmeraldOreProcessorBlockEntity.isValidInput(slotStack)) {
                    if (!this.moveItemStackTo(slotStack, 0, 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (EmeraldOreProcessorBlockEntity.isValidFuel(slotStack)) {
                    if (!this.moveItemStackTo(slotStack, 1, 2, false)) {
                        return ItemStack.EMPTY;
                    }
                } else {
                    return ItemStack.EMPTY;
                }
            }

            if (slotStack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (slotStack.getCount() == result.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, slotStack);
        }

        return result;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return container.stillValid(player);
    }

    /**
     * Simple container wrapper for client-side menu construction.
     */
    private static class SimpleContainerWrapper extends SimpleContainer {
        SimpleContainerWrapper() {
            super(EmeraldOreProcessorBlockEntity.INVENTORY_SIZE);
        }
    }
}
