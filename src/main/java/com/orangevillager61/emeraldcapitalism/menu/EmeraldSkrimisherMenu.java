package com.orangevillager61.emeraldcapitalism.menu;

import com.orangevillager61.emeraldcapitalism.entity.EmeraldSkrimisher;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Read-only container view of an Emerald Skrimisher's carried inventory. */
public final class EmeraldSkrimisherMenu extends AbstractContainerMenu {

    public static final int SKRIMISHER_SLOT_COUNT = EmeraldSkrimisher.INVENTORY_SIZE;
    private static final int PLAYER_INVENTORY_SLOT_COUNT = 27;
    private static final int PLAYER_HOTBAR_SLOT_COUNT = 9;
    private static final int SLOT_X_START = 8;
    private static final int SKRIMISHER_SLOT_Y = 31;
    private static final int PLAYER_INVENTORY_SLOT_Y = 138;
    private static final int PLAYER_HOTBAR_SLOT_Y = 196;
    private static final int SLOT_SPACING = 18;
    private static final int PLAYER_INVENTORY_START = SKRIMISHER_SLOT_COUNT;
    private static final int PLAYER_HOTBAR_START = PLAYER_INVENTORY_START + PLAYER_INVENTORY_SLOT_COUNT;
    private static final int PLAYER_SLOT_END = PLAYER_HOTBAR_START + PLAYER_HOTBAR_SLOT_COUNT;

    private final EmeraldSkrimisher skrimisher;
    private final List<ReadOnlySlot> skrimisherSlots = new ArrayList<>();

    /** Creates the authoritative server-side menu backed by the entity inventory. */
    public EmeraldSkrimisherMenu(int containerId, Inventory playerInventory,
                                 EmeraldSkrimisher skrimisher) {
        super(ECAPMenuTypes.EMERALD_SKRIMISHER_MENU.get(), containerId);
        this.skrimisher = Objects.requireNonNull(skrimisher, "skrimisher");
        addSkrimisherSlots(skrimisher.getInventory());
        addPlayerInventorySlots(playerInventory);
    }

    /** Creates the client-side menu from the entity id sent when the menu opens. */
    public EmeraldSkrimisherMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        super(ECAPMenuTypes.EMERALD_SKRIMISHER_MENU.get(), containerId);
        int entityId = extraData.readableBytes() >= Integer.BYTES ? extraData.readInt() : -1;
        Entity entity = playerInventory.player.level().getEntity(entityId);
        this.skrimisher = entity instanceof EmeraldSkrimisher foundSkrimisher
                ? foundSkrimisher : null;
        addSkrimisherSlots(this.skrimisher != null
                ? this.skrimisher.getInventory()
                : new SimpleContainer(SKRIMISHER_SLOT_COUNT));
        addPlayerInventorySlots(playerInventory);
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        if (index < 0 || index >= this.slots.size()) {
            return ItemStack.EMPTY;
        }

        Slot slot = this.slots.get(index);
        if (this.skrimisherSlots.contains(slot) || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack sourceStack = slot.getItem();
        ItemStack movedStack = sourceStack.copy();
        boolean moved = index < PLAYER_HOTBAR_START
                ? this.moveItemStackTo(sourceStack, PLAYER_HOTBAR_START, PLAYER_SLOT_END, true)
                : this.moveItemStackTo(sourceStack, PLAYER_INVENTORY_START, PLAYER_HOTBAR_START, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }

        if (sourceStack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return movedStack;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return this.skrimisher != null
                && this.skrimisher.isAlive()
                && this.skrimisher.distanceToSqr(player) < 64.0D;
    }

    public EmeraldSkrimisher getSkrimisher() {
        return this.skrimisher;
    }

    public boolean isSkrimisherSlot(Slot slot) {
        return this.skrimisherSlots.contains(slot);
    }

    private void addSkrimisherSlots(Container inventory) {
        for (int index = 0; index < SKRIMISHER_SLOT_COUNT; index++) {
            ReadOnlySlot slot = new ReadOnlySlot(inventory, index,
                    SLOT_X_START + index * SLOT_SPACING, SKRIMISHER_SLOT_Y);
            this.skrimisherSlots.add(slot);
            this.addSlot(slot);
        }
    }

    private void addPlayerInventorySlots(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        SLOT_X_START + col * SLOT_SPACING,
                        PLAYER_INVENTORY_SLOT_Y + row * SLOT_SPACING));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col,
                    SLOT_X_START + col * SLOT_SPACING, PLAYER_HOTBAR_SLOT_Y));
        }
    }

    private static final class ReadOnlySlot extends Slot {

        private ReadOnlySlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(@NotNull Player player) {
            return false;
        }
    }
}
