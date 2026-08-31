package com.orangevillager61.emeraldcapitalism.menu;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.event.ZombieVirusEvents;
import com.orangevillager61.emeraldcapitalism.network.ProtocolStringLimits;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import com.orangevillager61.emeraldcapitalism.world.bank.BankAccountData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class VillagerStatsMenu extends AbstractContainerMenu {

    public static final int VILLAGER_SLOT_COUNT = 18;
    public static final int VILLAGER_COLUMN_COUNT = 9;
    public static final int VILLAGER_ROW_COUNT = 2;
    private static final int PLAYER_INVENTORY_SLOT_COUNT = 27;
    private static final int PLAYER_HOTBAR_SLOT_COUNT = 9;
    private static final int SLOT_X_START = 8;
    private static final int SLOT_Y_VILLAGER = 31;
    private static final int SLOT_Y_PLAYER_INVENTORY = 138;
    private static final int SLOT_Y_PLAYER_HOTBAR = 196;
    private static final int SLOT_SPACING = 18;
    private static final int PLAYER_INVENTORY_START = VILLAGER_SLOT_COUNT;
    private static final int PLAYER_HOTBAR_START = PLAYER_INVENTORY_START + PLAYER_INVENTORY_SLOT_COUNT;
    private static final int PLAYER_SLOT_END = PLAYER_HOTBAR_START + PLAYER_HOTBAR_SLOT_COUNT;

    private final AbstractVillager villager;
    private final String profession;
    private final BankAccountData bankAccounts;
    private final ContainerData statsData;
    private final List<ReadOnlySlot> villagerSlots = new ArrayList<>();

    // Emerald count cache
    private int cachedEmeraldCount;
    private long lastEmeraldCountTick;
    private static final int EMERALD_COUNT_UPDATE_INTERVAL = 20; // Recalculate every 20 ticks (1 second)

    // Parent names (synced to client)
    private String parent1Name = null;
    private String parent2Name = null;
    private final String firstName;

    // Data slot indices
    public static final int DATA_HUNGER_LEVEL = 0;
    public static final int DATA_EMERALD_BALANCE = 1;
    public static final int DATA_HEALTH = 2;
    public static final int DATA_MAX_HEALTH = 3;
    public static final int DATA_EMERALD_INVENTORY = 4;
    public static final int DATA_PLAYER_REPUTATION = 5;
    public static final int DATA_BREEDING_COOLDOWN = 6;
    public static final int DATA_ILLNESS_PHASE = 7;
    public static final int DATA_ILLNESS_REMAINING_TICKS = 8;
    public static final int DATA_COUNT = 9; // Total number of data slots

    // Constructor for server-side
    public VillagerStatsMenu(int containerId, Inventory playerInventory, AbstractVillager villager) {
        super(ECAPMenuTypes.VILLAGER_STATS_MENU.get(), containerId);
        this.villager = villager;
        this.profession = getProfessionLabel(villager);
        this.firstName = getFirstNameLabel(villager);
        this.bankAccounts = villager instanceof Villager && villager.level() instanceof ServerLevel level
                ? BankAccountData.get(level)
                : null;

        // Initialize emerald cache
        this.cachedEmeraldCount = countEmeralds(villager);
        this.lastEmeraldCountTick = villager.level().getGameTime();

        // Create ContainerData from authoritative server state; values sync to the client.
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        this.statsData = new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_HUNGER_LEVEL -> stats.getHungerLevel();
                    case DATA_EMERALD_BALANCE -> bankAccounts == null || villager instanceof WanderingTrader
                            ? 0
                            : bankAccounts.getBalance(villager.getUUID());
                    case DATA_HEALTH -> Math.round(villager.getHealth());
                    case DATA_MAX_HEALTH -> Math.round(villager.getMaxHealth());
                    case DATA_EMERALD_INVENTORY -> getCachedEmeraldCount();
                    case DATA_PLAYER_REPUTATION -> villager instanceof Villager regular
                            ? regular.getPlayerReputation(playerInventory.player) : 0;
                    case DATA_BREEDING_COOLDOWN -> Math.max(0, villager.getAge());
                    case DATA_ILLNESS_PHASE -> ZombieVirusEvents.getPhase(villager);
                    case DATA_ILLNESS_REMAINING_TICKS -> ZombieVirusEvents.getPhaseOneRemainingTicks(villager);
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                if (index == DATA_HUNGER_LEVEL) {
                    stats.setHungerLevel(value);
                }
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };

        // Register data slots for automatic syncing
        this.addDataSlots(statsData);

        // Add villager inventory slots (read-only): 2 rows of 9 at y=18
        addVillagerSlots(displayInventory(villager));

        addPlayerInventorySlots(playerInventory);
    }

    // Constructor for client-side (receives data from server)
    public VillagerStatsMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        super(ECAPMenuTypes.VILLAGER_STATS_MENU.get(), containerId);
        this.bankAccounts = null;
        var entity = playerInventory.player.level().getEntity(extraData.readInt());
        this.villager = entity instanceof AbstractVillager foundVillager ? foundVillager : null;
        this.profession = extraData.readUtf(ProtocolStringLimits.MAX_PROFESSION_LABEL_LENGTH);
        this.firstName = extraData.readUtf(ProtocolStringLimits.MAX_PARENT_NAME_LENGTH);

        // Read parent names
        if (extraData.readBoolean()) {
            this.parent1Name = extraData.readUtf(ProtocolStringLimits.MAX_PARENT_NAME_LENGTH);
        }
        if (extraData.readBoolean()) {
            this.parent2Name = extraData.readUtf(ProtocolStringLimits.MAX_PARENT_NAME_LENGTH);
        }

        // Client-side uses SimpleContainerData: values will be synced from server
        this.statsData = new SimpleContainerData(DATA_COUNT);
        this.addDataSlots(statsData);

        // Always mirror the server's 18 villager slots. If the entity is not
        // currently resolvable, the server's initial slot sync fills this
        // temporary container with the authoritative inventory snapshot.
        addVillagerSlots(this.villager != null
                ? displayInventory(this.villager)
                : new SimpleContainer(VILLAGER_SLOT_COUNT));

        addPlayerInventorySlots(playerInventory);
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        // Prevent shift-clicking items from villager inventory
        if (this.villagerSlots.contains(this.slots.get(index))) {
            return ItemStack.EMPTY;
        }

        // Allow normal shift-clicking in player inventory
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);

        if (slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            itemstack = slotStack.copy();

            if (index < PLAYER_HOTBAR_START) {
                if (!this.moveItemStackTo(slotStack, PLAYER_HOTBAR_START, PLAYER_SLOT_END, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(slotStack, PLAYER_INVENTORY_START, PLAYER_HOTBAR_START, false)) {
                return ItemStack.EMPTY;
            }

            if (slotStack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }

        return itemstack;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return this.villager != null &&
                this.villager.isAlive() &&
                this.villager.distanceTo(player) < 8.0F;
    }

    public AbstractVillager getVillager() {
        return villager;
    }

    /** Returns whether the slot belongs to the read-only villager inventory area. */
    public boolean isVillagerSlot(Slot slot) {
        return this.villagerSlots.contains(slot);
    }

    public String getProfession() {
        return profession;
    }

    /** Returns the first word of the villager's server-authoritative display name. */
    public static String getFirstNameLabel(AbstractVillager villager) {
        String displayName = villager.getDisplayName().getString().trim();
        if (displayName.isEmpty()) {
            return "Unknown";
        }
        int separator = displayName.indexOf(' ');
        String firstName = separator > 0 ? displayName.substring(0, separator) : displayName;
        return ProtocolStringLimits.clamp(firstName, ProtocolStringLimits.MAX_PARENT_NAME_LENGTH);
    }

    public String getFirstName() {
        return firstName;
    }

    /** Builds the server-authoritative profession label shown by the Job tab. */
    public static String getProfessionLabel(AbstractVillager villager) {
        if (villager instanceof WanderingTrader) {
            return "Wandering Trader";
        }
        if (!(villager instanceof Villager regularVillager)) {
            return "Unavailable";
        }
        String profession = capitalizeFirst(regularVillager.getVillagerData().getProfession().name());
        if ("Mayor".equals(profession) && regularVillager.level() instanceof ServerLevel level) {
            VillageRecord village = VillageRegistryData.get(level).getVillageFor(regularVillager.blockPosition());
            if (village != null && village.getName() != null && !village.getName().isBlank()) {
                profession += " of " + ProtocolStringLimits.clamp(
                        village.getName(), ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH);
            }
        }
        if (regularVillager.level() instanceof ServerLevel level
                && BankEmployeeLookup.isEmployee(level, regularVillager)) {
            return "Bank " + profession;
        }
        return ProtocolStringLimits.clamp(profession, ProtocolStringLimits.MAX_PROFESSION_LABEL_LENGTH);
    }

    private static String capitalizeFirst(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    public boolean isWanderingTrader() {
        return this.villager instanceof WanderingTrader;
    }

    public boolean hasPlayerReputation() {
        return this.villager instanceof Villager;
    }

    public boolean hasFamilyProperties() {
        return this.villager instanceof Villager;
    }

    private static Container displayInventory(AbstractVillager villager) {
        if (villager.getInventory().getContainerSize() >= VILLAGER_SLOT_COUNT) {
            return villager.getInventory();
        }
        SimpleContainer snapshot = new SimpleContainer(VILLAGER_SLOT_COUNT);
        for (int index = 0; index < villager.getInventory().getContainerSize(); index++) {
            snapshot.setItem(index, villager.getInventory().getItem(index).copy());
        }
        return snapshot;
    }

    /**
     * Returns the remaining breeding cooldown in ticks for this villager.
     * Vanilla stores post-breeding cooldown as a positive age value.
     */
    public int getBreedingCooldownTicks() {
        return statsData.get(DATA_BREEDING_COOLDOWN);
    }

    public void setVillagerSlotsVisible(boolean visible) {
        for (ReadOnlySlot slot : this.villagerSlots) {
            slot.setVisible(visible);
        }
    }

    /**
     * Get the hunger level from synced data.
     * This works on both client and server side.
     */
    public int getHungerLevel() {
        return statsData.get(DATA_HUNGER_LEVEL);
    }

    /**
     * Get the villager's server-authoritative bank balance from synced data.
     * This can be negative when the account is in debt.
     */
    public int getEmeraldBalance() {
        return statsData.get(DATA_EMERALD_BALANCE);
    }

    /**
     * Get the physical emerald count from the villager's inventory.
     * Counts emerald blocks as 9 emeralds each.
     */
    public int getEmeraldInventoryCount() {
        return statsData.get(DATA_EMERALD_INVENTORY);
    }

    public int getHealth() {
        return statsData.get(DATA_HEALTH);
    }

    public int getMaxHealth() {
        return statsData.get(DATA_MAX_HEALTH);
    }

    public int getPlayerReputation() {
        return statsData.get(DATA_PLAYER_REPUTATION);
    }

    public int getIllnessPhase() {
        return statsData.get(DATA_ILLNESS_PHASE);
    }

    public int getIllnessRemainingTicks() {
        return statsData.get(DATA_ILLNESS_REMAINING_TICKS);
    }

    /**
     * Get parent 1 name (synced from server on menu open).
     */
    public String getParent1Name() {
        return parent1Name;
    }

    /**
     * Get parent 2 name (synced from server on menu open).
     */
    public String getParent2Name() {
        return parent2Name;
    }

    /**
     * Get cached emerald count, recalculating if enough time has passed.
     * Only used server-side.
     */
    private int getCachedEmeraldCount() {
        long currentTick = villager.level().getGameTime();
        if (currentTick - lastEmeraldCountTick >= EMERALD_COUNT_UPDATE_INTERVAL) {
            cachedEmeraldCount = countEmeralds(villager);
            lastEmeraldCountTick = currentTick;
        }
        return cachedEmeraldCount;
    }

    /**
     * Count total emeralds in villager inventory.
     * Emerald blocks count as 9 emeralds.
     */
    private static int countEmeralds(AbstractVillager villager) {
        var inventory = villager.getInventory();
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(Items.EMERALD)) {
                count += stack.getCount();
            } else if (stack.is(Items.EMERALD_BLOCK)) {
                count += stack.getCount() * 9;
            }
        }
        return count;
    }

    private void addVillagerSlots(Container villagerInventory) {
        for (int row = 0; row < VILLAGER_ROW_COUNT; row++) {
            for (int col = 0; col < VILLAGER_COLUMN_COUNT; col++) {
                int index = row * VILLAGER_COLUMN_COUNT + col;
                ReadOnlySlot slot = new ReadOnlySlot(villagerInventory, index,
                        SLOT_X_START + col * SLOT_SPACING, SLOT_Y_VILLAGER + row * SLOT_SPACING);
                this.villagerSlots.add(slot);
                this.addSlot(slot);
            }
        }
    }

    private void addPlayerInventorySlots(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, SLOT_X_START + col * SLOT_SPACING,
                        SLOT_Y_PLAYER_INVENTORY + row * SLOT_SPACING));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, SLOT_X_START + col * SLOT_SPACING, SLOT_Y_PLAYER_HOTBAR));
        }
    }

    // Read-only slot class
    private static class ReadOnlySlot extends Slot {
        private boolean visible = true;

        public ReadOnlySlot(net.minecraft.world.Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return false; // Cannot place items
        }

        @Override
        public boolean mayPickup(@NotNull Player player) {
            return false; // Cannot take items
        }

        @Override
        public boolean isActive() {
            return this.visible;
        }

        public void setVisible(boolean visible) {
            this.visible = visible;
        }
    }
}
