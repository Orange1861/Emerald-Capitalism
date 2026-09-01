package com.orangevillager61.emeraldcapitalism.block.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.EmeraldOreProcessorBlock;
import com.orangevillager61.emeraldcapitalism.menu.EmeraldOreProcessorMenu;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlockEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

public class EmeraldOreProcessorBlockEntity extends BlockEntity implements MenuProvider, WorldlyContainer {

    private static final int DYES_PER_EMERALD = 2;
    public static final int EMERALDS_PER_CHEST = 8;

    public static final int SLOT_INPUT = 0;
    public static final int SLOT_FUEL = 1;
    public static final int SLOT_OUTPUT = 2;
    public static final int INVENTORY_SIZE = 3;

    public static final int SMELT_DURATION = 400; // 20 seconds (slow)
    private static final long IDLE_CHECK_INTERVAL_TICKS = 20L;

    private final NonNullList<ItemStack> items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);

    private int burnTime;
    private int burnDuration;
    private int cookProgress;
    private int cookTotalTime = SMELT_DURATION;
    private long nextIdleCheckTick = Long.MIN_VALUE;

    /**
     * The mod-owned durable processor state. Inventory remains owned by
     * {@link ContainerHelper}; this record owns only the burn/cook timers so
     * the 1.21.1 NBT override is a narrow version boundary.
     */
    static record PersistedState(int burnTime, int burnDuration, int cookProgress, int cookTotalTime) {
        private static final int MAX_TIMER = SMELT_DURATION;

        static final Codec<PersistedState> CODEC = RecordCodecBuilder.<PersistedState>create(instance -> instance.group(
                Codec.intRange(0, MAX_TIMER).optionalFieldOf("burn_time", 0)
                        .forGetter(PersistedState::burnTime),
                Codec.intRange(0, MAX_TIMER).optionalFieldOf("burn_duration", 0)
                        .forGetter(PersistedState::burnDuration),
                Codec.intRange(0, MAX_TIMER).optionalFieldOf("cook_progress", 0)
                        .forGetter(PersistedState::cookProgress),
                Codec.intRange(1, MAX_TIMER).optionalFieldOf("cook_total_time", SMELT_DURATION)
                        .forGetter(PersistedState::cookTotalTime)
        ).apply(instance, PersistedState::new)).validate(PersistedState::validate);

        static PersistedState empty() {
            return new PersistedState(0, 0, 0, SMELT_DURATION);
        }

        static PersistedState from(EmeraldOreProcessorBlockEntity processor) {
            return new PersistedState(
                    processor.burnTime,
                    processor.burnDuration,
                    processor.cookProgress,
                    processor.cookTotalTime
            );
        }

        private static DataResult<PersistedState> validate(PersistedState state) {
            if (state.burnTime > state.burnDuration) {
                return DataResult.error(() -> "burn_time cannot exceed burn_duration");
            }
            if (state.cookProgress >= state.cookTotalTime) {
                return DataResult.error(() -> "cook_progress must be below cook_total_time");
            }
            return DataResult.success(state);
        }

        void applyTo(EmeraldOreProcessorBlockEntity processor) {
            processor.burnTime = burnTime;
            processor.burnDuration = burnDuration;
            processor.cookProgress = cookProgress;
            processor.cookTotalTime = cookTotalTime;
        }
    }

    private static final int[] SLOTS_FOR_UP = {SLOT_INPUT};
    private static final int[] SLOTS_FOR_DOWN = {SLOT_OUTPUT};
    private static final int[] SLOTS_FOR_SIDES = {SLOT_FUEL};

    private final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> burnTime;
                case 1 -> burnDuration;
                case 2 -> cookProgress;
                case 3 -> cookTotalTime;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            switch (index) {
                case 0 -> burnTime = value;
                case 1 -> burnDuration = value;
                case 2 -> cookProgress = value;
                case 3 -> cookTotalTime = value;
            }
        }

        @Override
        public int getCount() {
            return 4;
        }
    };

    public EmeraldOreProcessorBlockEntity(BlockPos pos, BlockState state) {
        super(ECAPBlockEntityTypes.EMERALD_ORE_PROCESSOR.get(), pos, state);
    }

    public ContainerData getDataAccess() {
        return dataAccess;
    }

    private boolean isLit() {
        return burnTime > 0;
    }

    public static boolean isValidInput(ItemStack stack) {
        return stack.is(Items.EMERALD)
                || stack.is(Items.EMERALD_ORE)
                || stack.is(Items.DEEPSLATE_EMERALD_ORE)
                || stack.is(ECAPItems.EMERALD_CHEST.get());
    }

    private static Item getProcessedOutput(ItemStack input) {
        if (input.is(ECAPItems.EMERALD_CHEST.get())) {
            return Items.EMERALD;
        }
        return input.is(Items.EMERALD) ? ECAPItems.EMERALD_GREEN_DYE.get() : Items.EMERALD_BLOCK;
    }

    private static int getProcessedOutputCount(ItemStack input) {
        if (input.is(ECAPItems.EMERALD_CHEST.get())) {
            return EMERALDS_PER_CHEST;
        }
        return input.is(Items.EMERALD) ? DYES_PER_EMERALD : 1;
    }

    public static boolean isValidFuel(ItemStack stack) {
        return stack.is(Items.COAL) || stack.is(Items.CHARCOAL);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, EmeraldOreProcessorBlockEntity processor) {
        long gameTime = level.getGameTime();
        if (!processor.isLit()) {
            if (gameTime < processor.nextIdleCheckTick) {
                return;
            }
            processor.nextIdleCheckTick = gameTime + IDLE_CHECK_INTERVAL_TICKS;
        }

        boolean wasLit = processor.isLit();
        boolean changed = false;
        boolean inventoryChanged = false;

        if (processor.isLit()) {
            processor.burnTime--;
            changed = true;
        }

        ItemStack input = processor.items.get(SLOT_INPUT);
        ItemStack fuel = processor.items.get(SLOT_FUEL);
        ItemStack output = processor.items.get(SLOT_OUTPUT);

        boolean hasInput = !input.isEmpty() && isValidInput(input);
        Item processedOutput = hasInput ? getProcessedOutput(input) : null;
        int processedOutputCount = hasInput ? getProcessedOutputCount(input) : 0;
        boolean canOutput = hasInput && (output.isEmpty() ||
                (output.is(processedOutput) && output.getCount() <= output.getMaxStackSize() - processedOutputCount));

        if (hasInput && canOutput) {
            // Start burning fuel if not already lit
            if (!processor.isLit() && !fuel.isEmpty() && isValidFuel(fuel)) {
                processor.burnDuration = SMELT_DURATION; // 1 coal per smelt
                processor.burnTime = processor.burnDuration;
                fuel.shrink(1);
                changed = true;
                inventoryChanged = true;
            }

            if (processor.isLit()) {
                processor.cookProgress++;
                changed = true;
                if (processor.cookProgress >= processor.cookTotalTime) {
                    processor.cookProgress = 0;
                    // Consume input
                    input.shrink(1);
                    // Produce output
                    if (output.isEmpty()) {
                        processor.items.set(SLOT_OUTPUT, new ItemStack(processedOutput, processedOutputCount));
                    } else {
                        output.grow(processedOutputCount);
                    }
                    changed = true;
                    inventoryChanged = true;
                }
            } else {
                // No fuel, reset progress
                if (processor.cookProgress > 0) {
                    processor.cookProgress = 0;
                    changed = true;
                }
            }
        } else {
            // No valid input or output full, reset progress
            if (processor.cookProgress > 0) {
                processor.cookProgress = 0;
                changed = true;
            }
        }

        if (wasLit != processor.isLit()) {
            changed = true;
            level.setBlock(pos, state.setValue(EmeraldOreProcessorBlock.LIT, processor.isLit()), 3);
        }

        if (changed) {
            processor.setChanged();
        }
        if (inventoryChanged) {
            processor.markNearbyBankCachesDirty();
        }
    }

    // Serialization

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, this.items, registries);
        PersistedState.CODEC.encodeStart(NbtOps.INSTANCE, PersistedState.from(this))
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "Could not encode emerald ore processor durable state: {}", message))
                .filter(CompoundTag.class::isInstance)
                .map(CompoundTag.class::cast)
                .ifPresent(tag::merge);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.loadAdditional(tag, registries);
        for (int slot = 0; slot < items.size(); slot++) {
            items.set(slot, ItemStack.EMPTY);
        }
        ContainerHelper.loadAllItems(tag, this.items, registries);
        PersistedState.CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.warn(
                        "Ignoring malformed emerald ore processor durable state: {}", message))
                .orElseGet(PersistedState::empty)
                .applyTo(this);
        nextIdleCheckTick = Long.MIN_VALUE;
    }

    // MenuProvider

    @Override
    public @NotNull Component getDisplayName() {
        return Component.translatable("block.emeraldcapitalism.emerald_ore_processor");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, @NotNull Inventory playerInventory, @NotNull Player player) {
        return new EmeraldOreProcessorMenu(containerId, playerInventory, this, this.dataAccess);
    }

    // Container / WorldlyContainer

    @Override
    public int getContainerSize() {
        return INVENTORY_SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public @NotNull ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public @NotNull ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) {
            wakeIdleTick();
            setChanged();
            markNearbyBankCachesDirty();
        }
        return result;
    }

    @Override
    public @NotNull ItemStack removeItemNoUpdate(int slot) {
        ItemStack result = ContainerHelper.takeItem(items, slot);
        if (!result.isEmpty()) {
            wakeIdleTick();
            markNearbyBankCachesDirty();
        }
        return result;
    }

    @Override
    public void setItem(int slot, @NotNull ItemStack stack) {
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        wakeIdleTick();
        setChanged();
        markNearbyBankCachesDirty();
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        Level level = getLevel();
        return level != null
                && level.getBlockEntity(worldPosition) == this
                && level.getBlockState(worldPosition).is(ECAPBlocks.EMERALD_ORE_PROCESSOR.get())
                && player.distanceToSqr(
                worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        boolean hadItems = !isEmpty();
        for (int slot = 0; slot < items.size(); slot++) {
            items.set(slot, ItemStack.EMPTY);
        }
        if (hadItems) {
            wakeIdleTick();
            setChanged();
            markNearbyBankCachesDirty();
        }
    }

    /** Wakes the idle fallback after a container mutation. */
    private void wakeIdleTick() {
        nextIdleCheckTick = Long.MIN_VALUE;
    }

    /** Invalidates nearby bank processor scans after a server-side inventory mutation. */
    private void markNearbyBankCachesDirty() {
        if (level instanceof ServerLevel serverLevel) {
            BankBlockEntity.markChestCachesDirtyNear(serverLevel, worldPosition);
        }
    }

    @Override
    public boolean canPlaceItem(int slot, @NotNull ItemStack stack) {
        if (slot == SLOT_INPUT) return isValidInput(stack);
        if (slot == SLOT_FUEL) return isValidFuel(stack);
        return false;
    }

    @Override
    public int @NotNull [] getSlotsForFace(@NotNull net.minecraft.core.Direction side) {
        if (side == net.minecraft.core.Direction.DOWN) return SLOTS_FOR_DOWN;
        if (side == net.minecraft.core.Direction.UP) return SLOTS_FOR_UP;
        return SLOTS_FOR_SIDES;
    }

    @Override
    public boolean canPlaceItemThroughFace(int index, @NotNull ItemStack stack, @Nullable net.minecraft.core.Direction direction) {
        return canPlaceItem(index, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int index, @NotNull ItemStack stack, @NotNull net.minecraft.core.Direction direction) {
        return index == SLOT_OUTPUT;
    }
}
