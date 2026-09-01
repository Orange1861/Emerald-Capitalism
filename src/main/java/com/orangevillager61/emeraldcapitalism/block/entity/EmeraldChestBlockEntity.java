package com.orangevillager61.emeraldcapitalism.block.entity;

import com.orangevillager61.emeraldcapitalism.registry.ECAPBlockEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class EmeraldChestBlockEntity extends ChestBlockEntity {

    public static final StoredCounts EMPTY_COUNTS = new StoredCounts(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());

    /** Derived inventory totals, rebuilt on mutation and never persisted. */
    private StoredCounts cachedCounts = EMPTY_COUNTS;
    /** Nearby banks currently consuming this chest's event-driven totals. */
    private final Set<BlockPos> linkedBanks = new HashSet<>();

    public EmeraldChestBlockEntity(BlockPos pos, BlockState blockState) {
        super(ECAPBlockEntityTypes.EMERALD_CHEST.get(), pos, blockState);
    }

    @Override
    protected @NotNull Component getDefaultName() {
        return Component.translatable("container.emeraldcapitalism.emerald_chest");
    }

    // Emerald count

    /** Returns the cached total emerald value of this chest's contents. */
    public int getEmeraldCount() {
        return cachedCounts.emeraldValue();
    }

    /** Returns the cached emerald-ore block count of this chest's contents. */
    public int getEmeraldOreCount() {
        return cachedCounts.emeraldOre();
    }

    public StoredCounts getStoredCounts() {
        return cachedCounts;
    }

    void linkBank(BlockPos bankPos) {
        linkedBanks.add(bankPos.immutable());
    }

    void unlinkBank(BlockPos bankPos) {
        linkedBanks.remove(bankPos);
    }

    /** Recounts this chest once per inventory mutation and notifies linked banks. */
    private void recomputeStoredCounts() {
        int emeraldValue = 0;
        int emeraldBlocks = 0;
        int emeraldOreTotal = 0;
        int pumpkins = 0;
        int wheat = 0;
        int bread = 0;
        int coal = 0;
        int emeraldGreenDye = 0;
        int plankEquivalent = 0;
        int logs = 0;
        Map<Item, Integer> itemCounts = new HashMap<>();
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack stack = getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            int count = stack.getCount();
            itemCounts.merge(stack.getItem(), count, Math::addExact);
            if (stack.is(Items.EMERALD)) {
                emeraldValue += count;
            } else if (stack.is(Items.EMERALD_BLOCK)) {
                emeraldBlocks += count;
                emeraldValue += count * 9;
            } else if (stack.is(Items.EMERALD_ORE) || stack.is(Items.DEEPSLATE_EMERALD_ORE)) {
                emeraldOreTotal += count;
            }
            if (stack.is(Items.PUMPKIN)) pumpkins += count;
            if (stack.is(Items.WHEAT)) wheat += count;
            if (stack.is(Items.BREAD)) bread += count;
            if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) coal += count;
            if (stack.is(ECAPItems.EMERALD_GREEN_DYE.get())) emeraldGreenDye += count;
            if (stack.is(ItemTags.PLANKS)) plankEquivalent += count;
            if (stack.is(ItemTags.LOGS)) {
                plankEquivalent += count * 4;
                logs += count;
            }
        }
        StoredCounts updated = new StoredCounts(emeraldValue, emeraldBlocks, emeraldOreTotal,
                pumpkins, wheat, bread, coal, emeraldGreenDye, plankEquivalent,
                logs, itemCounts);
        if (updated.equals(cachedCounts)) {
            return;
        }
        cachedCounts = updated;
        notifyLinkedBanks();
    }

    @Override
    public void setChanged() {
        super.setChanged();
        // Container mutations funnel through setChanged, including hopper and
        // menu operations. The null guard covers an unlikely superclass call
        // during construction before subclass fields are initialized.
        if (linkedBanks != null) {
            recomputeStoredCounts();
        }
    }

    @Override
    public @NotNull ItemStack removeItem(int slot, int amount) {
        ItemStack removed = super.removeItem(slot, amount);
        if (!removed.isEmpty()) {
            setChanged();
        }
        return removed;
    }

    @Override
    public @NotNull ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = super.removeItemNoUpdate(slot);
        if (!removed.isEmpty()) {
            recomputeStoredCounts();
        }
        return removed;
    }

    @Override
    public void clearContent() {
        super.clearContent();
        setChanged();
    }

    private void notifyLinkedBanks() {
        if (!(level instanceof ServerLevel serverLevel) || linkedBanks.isEmpty()) {
            return;
        }
        linkedBanks.removeIf(bankPos -> {
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(
                    bankPos.getX() >> 4, bankPos.getZ() >> 4);
            BlockEntity blockEntity = chunk == null
                    ? null
                    : chunk.getBlockEntity(bankPos, LevelChunk.EntityCreationType.CHECK);
            if (blockEntity instanceof BankBlockEntity bank && !bank.isRemoved()) {
                bank.onLinkedChestChanged(worldPosition, cachedCounts);
                return false;
            }
            return true;
        });
    }

    // Serialization

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.loadAdditional(tag, registries);
        // Items were just loaded by super: recompute from them rather than
        // persisting a redundant derived value.
        recomputeStoredCounts();
    }

    public record StoredCounts(int emeraldValue, int emeraldBlocks, int emeraldOre,
                               int pumpkins, int wheat, int bread, int coal,
                               int emeraldGreenDye, int plankEquivalent, int logs,
                               Map<Item, Integer> itemCounts) {

        public StoredCounts {
            itemCounts = Map.copyOf(itemCounts);
        }
    }
}
