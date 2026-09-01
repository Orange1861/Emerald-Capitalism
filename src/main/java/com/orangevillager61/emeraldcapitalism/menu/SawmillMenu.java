package com.orangevillager61.emeraldcapitalism.menu;

import com.google.common.collect.Lists;
import com.orangevillager61.emeraldcapitalism.recipe.SawmillRecipe;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPRecipeTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.List;

/** Server-authoritative menu for the sawmill's stonecutter-shaped UI. */
public class SawmillMenu extends AbstractContainerMenu {
    public static final int INPUT_SLOT = 0;
    public static final int RESULT_SLOT = 1;
    private static final int INV_SLOT_START = 2;
    private static final int INV_SLOT_END = 29;
    private static final int USE_ROW_SLOT_START = 29;
    private static final int USE_ROW_SLOT_END = 38;

    private final ContainerLevelAccess access;
    private final DataSlot selectedRecipeIndex = DataSlot.standalone();
    private final Level level;
    private List<RecipeHolder<SawmillRecipe>> recipes = Lists.newArrayList();
    private ItemStack input = ItemStack.EMPTY;
    private long lastSoundTime;
    private final Slot inputSlot;
    private final Slot resultSlot;
    private Runnable slotUpdateListener = () -> {};

    public final Container container = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            SawmillMenu.this.slotsChanged(this);
            SawmillMenu.this.slotUpdateListener.run();
        }
    };
    private final ResultContainer resultContainer = new ResultContainer();

    public SawmillMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, ContainerLevelAccess.NULL);
    }

    public SawmillMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, playerInventory, ContainerLevelAccess.NULL);
    }

    public SawmillMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access) {
        super(ECAPMenuTypes.SAWMILL_MENU.get(), containerId);
        this.access = access;
        this.level = playerInventory.player.level();
        this.inputSlot = this.addSlot(new Slot(this.container, INPUT_SLOT, 20, 33));
        this.resultSlot = this.addSlot(new Slot(this.resultContainer, RESULT_SLOT, 143, 33) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public void onTake(Player player, ItemStack stack) {
                stack.onCraftedBy(player.level(), player, stack.getCount());
                SawmillMenu.this.resultContainer.awardUsedRecipes(player, this.getRelevantItems());
                RecipeHolder<SawmillRecipe> recipe = SawmillMenu.this.getSelectedRecipe();
                int inputCount = recipe == null ? 1 : recipe.value().getInputCount();
                ItemStack remainingInput = SawmillMenu.this.inputSlot.remove(inputCount);
                if (!remainingInput.isEmpty()) {
                    SawmillMenu.this.setupResultSlot();
                }

                access.execute((level, pos) -> {
                    long gameTime = level.getGameTime();
                    if (SawmillMenu.this.lastSoundTime != gameTime) {
                        level.playSound(null, pos, SoundEvents.UI_STONECUTTER_TAKE_RESULT,
                                SoundSource.BLOCKS, 1.0F, 1.0F);
                        SawmillMenu.this.lastSoundTime = gameTime;
                    }
                });
                super.onTake(player, stack);
            }

            private List<ItemStack> getRelevantItems() {
                return List.of(SawmillMenu.this.inputSlot.getItem());
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                this.addSlot(new Slot(playerInventory, column + row * 9 + 9,
                        8 + column * 18, 84 + row * 18));
            }
        }

        for (int column = 0; column < 9; column++) {
            this.addSlot(new Slot(playerInventory, column, 8 + column * 18, 142));
        }

        this.addDataSlot(this.selectedRecipeIndex);
    }

    public int getSelectedRecipeIndex() {
        return this.selectedRecipeIndex.get();
    }

    public List<RecipeHolder<SawmillRecipe>> getRecipes() {
        return this.recipes;
    }

    public int getNumRecipes() {
        return this.recipes.size();
    }

    public boolean hasInputItem() {
        return this.inputSlot.hasItem() && !this.recipes.isEmpty();
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(this.access, player, ECAPBlocks.SAWMILL.get());
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (this.isValidRecipeIndex(id)) {
            this.selectedRecipeIndex.set(id);
            this.setupResultSlot();
        }
        return true;
    }

    private boolean isValidRecipeIndex(int recipeIndex) {
        return recipeIndex >= 0 && recipeIndex < this.recipes.size();
    }

    @Override
    public void slotsChanged(Container inventory) {
        ItemStack stack = this.inputSlot.getItem();
        if (!ItemStack.isSameItemSameComponents(stack, this.input)
                || stack.getCount() != this.input.getCount()) {
            this.input = stack.copy();
            this.setupRecipeList(inventory, stack);
        }
    }

    private static SingleRecipeInput createRecipeInput(Container container) {
        return new SingleRecipeInput(container.getItem(INPUT_SLOT));
    }

    private void setupRecipeList(Container container, ItemStack stack) {
        this.recipes.clear();
        this.selectedRecipeIndex.set(-1);
        this.resultSlot.set(ItemStack.EMPTY);
        if (!stack.isEmpty()) {
            this.recipes = com.orangevillager61.emeraldcapitalism.util.RecipeManagerCompat.getRecipesFor(
                    this.level, ECAPRecipeTypes.SAWMILL.get(), createRecipeInput(container));
        }
    }

    private RecipeHolder<SawmillRecipe> getSelectedRecipe() {
        return this.isValidRecipeIndex(this.selectedRecipeIndex.get())
                ? this.recipes.get(this.selectedRecipeIndex.get()) : null;
    }

    private void setupResultSlot() {
        RecipeHolder<SawmillRecipe> recipe = this.getSelectedRecipe();
        if (recipe != null) {
            ItemStack result = recipe.value().assemble(createRecipeInput(this.container), this.level.registryAccess());
            if (result.isItemEnabled(this.level.enabledFeatures())) {
                this.resultContainer.setRecipeUsed(recipe);
                this.resultSlot.set(result);
            } else {
                this.resultSlot.set(ItemStack.EMPTY);
            }
        } else {
            this.resultSlot.set(ItemStack.EMPTY);
        }
        this.broadcastChanges();
    }

    @Override
    public MenuType<?> getType() {
        return ECAPMenuTypes.SAWMILL_MENU.get();
    }

    public void registerUpdateListener(Runnable listener) {
        this.slotUpdateListener = listener;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != this.resultContainer && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack moved = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            Item item = stack.getItem();
            moved = stack.copy();
            if (index == RESULT_SLOT) {
                item.onCraftedBy(stack, player.level(), player);
                if (!this.moveItemStackTo(stack, INV_SLOT_START, USE_ROW_SLOT_END, true)) {
                    return ItemStack.EMPTY;
                }
                slot.onQuickCraft(stack, moved);
            } else if (index == INPUT_SLOT) {
                if (!this.moveItemStackTo(stack, INV_SLOT_START, USE_ROW_SLOT_END, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (com.orangevillager61.emeraldcapitalism.util.RecipeManagerCompat.get(this.level).getRecipeFor(
                    ECAPRecipeTypes.SAWMILL.get(), new SingleRecipeInput(stack), this.level).isPresent()) {
                if (!this.moveItemStackTo(stack, INPUT_SLOT, RESULT_SLOT, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index >= INV_SLOT_START && index < INV_SLOT_END) {
                if (!this.moveItemStackTo(stack, USE_ROW_SLOT_START, USE_ROW_SLOT_END, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index >= USE_ROW_SLOT_START && index < USE_ROW_SLOT_END
                    && !this.moveItemStackTo(stack, INV_SLOT_START, INV_SLOT_END, false)) {
                return ItemStack.EMPTY;
            }

            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            }
            slot.setChanged();
            if (stack.getCount() == moved.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.onTake(player, stack);
            this.broadcastChanges();
        }
        return moved;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.resultContainer.removeItemNoUpdate(RESULT_SLOT);
        this.access.execute((level, pos) -> this.clearContainer(player, this.container));
    }
}
