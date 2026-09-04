package com.orangevillager61.emeraldcapitalism.menu;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.market.MarketItemConfig;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.recipe.EmeraldCraftingRecipe;
import com.orangevillager61.emeraldcapitalism.registry.ECAPRecipeTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

// TODO: MAKE INTO SEPERATE BLOCK
/** Container menu for the Bank block. */
public class BankMenu extends AbstractContainerMenu {

    public static final int RESULT_SLOT = 0;
    public static final int CRAFT_SLOT_START = 1;
    public static final int CRAFT_SLOT_END = 10;
    public static final int INV_SLOT_START = 10;
    public static final int INV_SLOT_END = 37;
    public static final int HOTBAR_SLOT_START = 37;
    public static final int HOTBAR_SLOT_END = 46;

    public static final int CRAFT_GRID_X = 80;
    public static final int CRAFT_GRID_Y = 78;
    public static final int RESULT_X = 188;
    public static final int RESULT_Y = 96;
    public static final int PLAYER_INV_X = 129;
    public static final int PLAYER_INV_Y = 151;
    public static final int PLAYER_HOTBAR_Y = 223;

    public record AccountEntry(String name, int balance) {
    }

    /** Server-authoritative employee row shown by the bank screen. */
    public record EmployeeEntry(String name, String entityType, String profession) {
    }

    /** Server-authoritative market snapshot sent with the bank menu. */
    public record MarketEntry(String id, String itemId, String displayName, int stock,
                              int population, int bankTarget, MarketItemConfig config) {
    }

    private final BankMenuOpenData openData;
    @Nullable
    private final UUID viewerId;
    private boolean bankIndependent;
    @Nullable
    private UUID controllerId;
    private int bankOpinion;
    private List<MarketEntry> marketEntries;
    private BankMenuOpenData.ControlSettings controlSettings;
    private final Player player;
    private final Level level;
    private final ContainerLevelAccess access;
    private final CraftingContainer craftSlots;
    private final ResultContainer resultSlots;
    private boolean placingRecipe;
    private boolean craftingVisible = true;

    /** Server-side constructor; display data is sent through the menu-open payload. */
    public BankMenu(int containerId, Inventory playerInventory, BankBlockEntity blockEntity,
                    @Nullable UUID viewerId) {
        super(ECAPMenuTypes.BANK_MENU.get(), containerId);
        this.openData = BankMenuOpenData.empty(blockEntity.getBlockPos());
        this.viewerId = viewerId;
        this.bankIndependent = openData.bankIndependent();
        this.controllerId = openData.controllerId();
        this.bankOpinion = 0;
        this.marketEntries = List.of();
        this.controlSettings = this.openData.controlSettings();
        this.player = playerInventory.player;
        this.level = player.level();
        this.access = ContainerLevelAccess.create(
                Objects.requireNonNull(blockEntity.getLevel(), "bank level"), blockEntity.getBlockPos());
        this.craftSlots = new TransientCraftingContainer(this, 3, 3);
        this.resultSlots = new ResultContainer();
        addCraftingSlots(playerInventory);
    }

    /** Client-side constructor for the server-authoritative open snapshot. */
    public BankMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        super(ECAPMenuTypes.BANK_MENU.get(), containerId);
        this.openData = BankMenuOpenData.read(buf);
        this.viewerId = null;
        this.bankIndependent = openData.bankIndependent();
        this.controllerId = openData.controllerId();
        this.bankOpinion = openData.bankOpinion();
        this.marketEntries = openData.marketEntries();
        this.controlSettings = openData.controlSettings();
        this.player = playerInventory.player;
        this.level = player.level();
        this.access = ContainerLevelAccess.NULL;
        this.craftSlots = new TransientCraftingContainer(this, 3, 3);
        this.resultSlots = new ResultContainer();
        addCraftingSlots(playerInventory);
    }

    private void addCraftingSlots(Inventory playerInventory) {
        this.addSlot(new BankResultSlot(this.player, this.craftSlots, this.resultSlots, RESULT_SLOT,
                RESULT_X, RESULT_Y));

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                this.addSlot(new BankSlot(this.craftSlots, column + row * 3,
                        CRAFT_GRID_X + column * 18, CRAFT_GRID_Y + row * 18));
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                this.addSlot(new BankSlot(playerInventory, column + row * 9 + 9,
                        PLAYER_INV_X + column * 18, PLAYER_INV_Y + row * 18));
            }
        }

        for (int column = 0; column < 9; column++) {
            this.addSlot(new BankSlot(playerInventory, column,
                    PLAYER_INV_X + column * 18, PLAYER_HOTBAR_Y));
        }
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        ItemStack moved = ItemStack.EMPTY;
        if (!this.isValidSlotIndex(index)) {
            return moved;
        }

        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return moved;
        }

        ItemStack stack = slot.getItem();
        moved = stack.copy();
        if (index == RESULT_SLOT) {
            stack.getItem().onCraftedBy(stack, player.level(), player);
            if (!this.moveItemStackTo(stack, INV_SLOT_START, HOTBAR_SLOT_END, true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, moved);
        } else if (index >= CRAFT_SLOT_START && index < CRAFT_SLOT_END) {
            if (!this.moveItemStackTo(stack, INV_SLOT_START, HOTBAR_SLOT_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (index >= INV_SLOT_START && index < INV_SLOT_END) {
            if (!this.moveItemStackTo(stack, HOTBAR_SLOT_START, HOTBAR_SLOT_END, false)) {
                return ItemStack.EMPTY;
            }
        } else if (index >= HOTBAR_SLOT_START && index < HOTBAR_SLOT_END
                && !this.moveItemStackTo(stack, INV_SLOT_START, INV_SLOT_END, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == moved.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        this.broadcastChanges();
        return moved;
    }

    @Override
    public void slotsChanged(@NotNull Container container) {
        if (this.placingRecipe || this.level.isClientSide()) {
            return;
        }
        this.access.execute((level, pos) -> setupResultSlot(level));
    }

    private void setupResultSlot(Level level) {
        if (!(this.player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        CraftingInput input = this.craftSlots.asCraftInput();
        RecipeHolder<? extends CraftingRecipe> recipe = findRecipe(level, input).orElse(null);
        ItemStack result = ItemStack.EMPTY;
        if (recipe != null) {
            ItemStack assembled = recipe.value().assemble(input, level.registryAccess());
            if (assembled.isItemEnabled(level.enabledFeatures())) {
                this.resultSlots.setRecipeUsed(recipe);
                result = assembled;
            }
        }

        this.resultSlots.setItem(RESULT_SLOT, result);
        this.setRemoteSlot(RESULT_SLOT, result);
        serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(
                this.containerId, this.incrementStateId(), RESULT_SLOT, result));
    }

    private Optional<RecipeHolder<? extends CraftingRecipe>> findRecipe(Level level, CraftingInput input) {
//? if >=1.21.4 {
        Optional<RecipeHolder<CraftingRecipe>> emeraldRecipe =
                com.orangevillager61.emeraldcapitalism.util.RecipeManagerCompat.get(level).getRecipeFor(
                        ECAPRecipeTypes.EMERALD_CRAFTING.get(), input, level);
        if (emeraldRecipe.isPresent()) {
            return Optional.<RecipeHolder<? extends CraftingRecipe>>of(emeraldRecipe.get());
        }

        Optional<RecipeHolder<CraftingRecipe>> vanillaRecipe =
                com.orangevillager61.emeraldcapitalism.util.RecipeManagerCompat.get(level).getRecipeFor(
                        RecipeType.CRAFTING, input, level);
        return vanillaRecipe.isPresent()
                ? Optional.<RecipeHolder<? extends CraftingRecipe>>of(vanillaRecipe.get())
                : Optional.empty();
//?} else {
/*        Optional<RecipeHolder<EmeraldCraftingRecipe>> emeraldRecipe =
                com.orangevillager61.emeraldcapitalism.util.RecipeManagerCompat.get(level).getRecipeFor(
                ECAPRecipeTypes.EMERALD_CRAFTING.get(), input, level);
        if (emeraldRecipe.isPresent()) {
            return Optional.<RecipeHolder<? extends CraftingRecipe>>of(emeraldRecipe.get());
        }

        Optional<RecipeHolder<CraftingRecipe>> vanillaRecipe =
                com.orangevillager61.emeraldcapitalism.util.RecipeManagerCompat.get(level).getRecipeFor(
                RecipeType.CRAFTING, input, level);
        return vanillaRecipe.isPresent()
                ? Optional.<RecipeHolder<? extends CraftingRecipe>>of(vanillaRecipe.get())
                : Optional.empty();
 *///?}
    }

    public void beginPlacingRecipe() {
        this.placingRecipe = true;
    }

    public void finishPlacingRecipe() {
        this.placingRecipe = false;
        if (!this.level.isClientSide()) {
            this.access.execute((level, pos) -> setupResultSlot(level));
        }
    }

    public CraftingContainer getCraftSlots() {
        return this.craftSlots;
    }

    public ResultContainer getResultSlots() {
        return this.resultSlots;
    }

    public void setCraftingVisible(boolean visible) {
        this.craftingVisible = visible;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        BlockPos blockPos = getBlockPos();
        return player.level().getBlockState(blockPos).is(ECAPBlocks.BANK.get())
                && player.level().getBlockEntity(blockPos) instanceof BankBlockEntity
                && player.distanceToSqr(
                blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void removed(@NotNull Player player) {
        super.removed(player);
        this.resultSlots.removeItemNoUpdate(RESULT_SLOT);
        if (!player.level().isClientSide()) {
            this.clearContainer(player, this.craftSlots);
        }
    }

    private class BankSlot extends Slot {
        private BankSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean isActive() {
            return !BankMenu.this.level.isClientSide() || BankMenu.this.craftingVisible;
        }
    }

    private class BankResultSlot extends ResultSlot {
        private BankResultSlot(Player player, CraftingContainer craftSlots, Container resultSlots,
                               int slot, int x, int y) {
            super(player, craftSlots, resultSlots, slot, x, y);
        }

        @Override
        public boolean isActive() {
            return !BankMenu.this.level.isClientSide() || BankMenu.this.craftingVisible;
        }
    }

    public BlockPos getBlockPos() {
        return openData.blockPos();
    }

    public String getBankName() {
        return openData.bankName();
    }

    public boolean isBankIndependent() {
        return bankIndependent;
    }

    @Nullable
    public UUID getControllerId() {
        return controllerId;
    }

    @Nullable
    public UUID getViewerId() {
        return viewerId;
    }

    public int getBankOpinion() {
        return bankOpinion;
    }

    @Nullable
    public UUID getVillageId() {
        return openData.villageId();
    }

    public String getVillageName() {
        return openData.villageName();
    }

    public int getEmployeeCount() {
        return openData.entityCounts().employeeCount();
    }

    public int getEmeraldGolemCount() {
        return openData.entityCounts().emeraldGolemCount();
    }

    public int getExpectedEmeraldGolemCount() {
        return openData.entityCounts().expectedEmeraldGolemCount();
    }

    public int getPumpkinTarget() {
        return openData.targets().pumpkin();
    }

    public int getBreadTarget() {
        return openData.targets().bread();
    }

    public int getPlankTarget() {
        return openData.targets().plank();
    }

    public int getCoalTarget() {
        return openData.targets().coal();
    }

    public int getTotalEmeraldCount() {
        return openData.totals().emerald();
    }

    public int getTotalEmeraldOreCount() {
        return openData.totals().emeraldOre();
    }

    public int getTotalPumpkinCount() {
        return openData.totals().pumpkin();
    }

    public int getTotalWheatCount() {
        return openData.totals().wheat();
    }

    public int getTotalBreadCount() {
        return openData.totals().bread();
    }

    public int getTotalCoalCount() {
        return openData.totals().coal();
    }

    public int getTotalEmeraldGreenDyeCount() {
        return openData.totals().emeraldGreenDye();
    }

    public int getTotalPlankCount() {
        return openData.totals().plank();
    }

    public int getChestCount() {
        return openData.chestCount();
    }

    public List<BlockPos> getChestPositions() {
        return openData.chestPositions();
    }

    public List<AccountEntry> getAccounts() {
        return openData.accounts();
    }

    public List<EmployeeEntry> getEmployees() {
        return openData.employees();
    }

    public BankMenuOpenData.ControlSettings getControlSettings() {
        return controlSettings;
    }

    public List<MarketEntry> getMarketEntries() {
        return marketEntries;
    }

    public void applyMarketEntries(List<MarketEntry> entries) {
        if (entries.size() > BankMenuOpenData.MAX_MARKET_ENTRIES) {
            throw new IllegalArgumentException("Too many market entries");
        }
        marketEntries = List.copyOf(entries);
    }

    public void applyMarketData(List<MarketEntry> entries, int bankOpinion) {
        applyMarketEntries(entries);
        this.bankOpinion = bankOpinion;
    }

    public void applyControlSettings(BankMenuOpenData.ControlSettings settings) {
        this.controlSettings = java.util.Objects.requireNonNull(settings, "settings");
    }

    public void applyBankControlState(boolean bankIndependent, @Nullable UUID controllerId,
                                      BankMenuOpenData.ControlSettings settings) {
        this.bankIndependent = bankIndependent;
        this.controllerId = controllerId;
        applyControlSettings(settings);
    }
}
