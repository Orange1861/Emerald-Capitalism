package com.orangevillager61.emeraldcapitalism.client.screen;

import com.orangevillager61.emeraldcapitalism.menu.SawmillMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import com.orangevillager61.emeraldcapitalism.recipe.SawmillRecipe;
import com.orangevillager61.emeraldcapitalism.util.GuiGraphicsCompat;
import com.orangevillager61.emeraldcapitalism.util.RecipeResultCompat;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/** The vanilla stonecutter layout and sprites, backed by the sawmill menu. */
@OnlyIn(Dist.CLIENT)
public class SawmillScreen extends AbstractContainerScreen<SawmillMenu> {
    private static final ResourceLocation SCROLLER_SPRITE = ResourceLocation.withDefaultNamespace(
            "container/stonecutter/scroller");
    private static final ResourceLocation SCROLLER_DISABLED_SPRITE = ResourceLocation.withDefaultNamespace(
            "container/stonecutter/scroller_disabled");
    private static final ResourceLocation RECIPE_SELECTED_SPRITE = ResourceLocation.withDefaultNamespace(
            "container/stonecutter/recipe_selected");
    private static final ResourceLocation RECIPE_HIGHLIGHTED_SPRITE = ResourceLocation.withDefaultNamespace(
            "container/stonecutter/recipe_highlighted");
    private static final ResourceLocation RECIPE_SPRITE = ResourceLocation.withDefaultNamespace(
            "container/stonecutter/recipe");
    private static final ResourceLocation BG_LOCATION = ResourceLocation.withDefaultNamespace(
            "textures/gui/container/stonecutter.png");

    private float scrollOffs;
    private boolean scrolling;
    private int startIndex;
    private boolean displayRecipes;

    public SawmillScreen(SawmillMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        menu.registerUpdateListener(this::containerChanged);
        this.titleLabelY--;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int left = this.leftPos;
        int top = this.topPos;
        GuiGraphicsCompat.blit(guiGraphics, BG_LOCATION, left, top, 0, 0,
                this.imageWidth, this.imageHeight);
        int scrollOffset = (int) (41.0F * this.scrollOffs);
        ResourceLocation scroller = this.isScrollBarActive()
                ? SCROLLER_SPRITE : SCROLLER_DISABLED_SPRITE;
        GuiGraphicsCompat.blitSprite(guiGraphics, scroller, left + 119,
                top + 15 + scrollOffset, 12, 15);
        int recipesX = this.leftPos + 52;
        int recipesY = this.topPos + 14;
        int lastVisible = this.startIndex + 12;
        this.renderButtons(guiGraphics, mouseX, mouseY, recipesX, recipesY, lastVisible);
        this.renderRecipes(guiGraphics, recipesX, recipesY, lastVisible);
    }

    @Override
    protected void renderTooltip(GuiGraphics guiGraphics, int x, int y) {
        super.renderTooltip(guiGraphics, x, y);
        if (!this.displayRecipes) {
            return;
        }

        int recipesX = this.leftPos + 52;
        int recipesY = this.topPos + 14;
        int lastVisible = this.startIndex + 12;
        List<RecipeHolder<SawmillRecipe>> recipes = this.menu.getRecipes();
        for (int index = this.startIndex; index < lastVisible && index < this.menu.getNumRecipes(); index++) {
            int visibleIndex = index - this.startIndex;
            int xStart = recipesX + visibleIndex % 4 * 16;
            int yStart = recipesY + visibleIndex / 4 * 18 + 2;
            if (x >= xStart && x < xStart + 16 && y >= yStart && y < yStart + 18) {
                guiGraphics.renderTooltip(this.font,
                        RecipeResultCompat.getSawmillResult(recipes.get(index).value(),
                                this.minecraft.level.registryAccess()), x, y);
            }
        }
    }

    private void renderButtons(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y,
                               int lastVisibleElementIndex) {
        for (int index = this.startIndex; index < lastVisibleElementIndex
                && index < this.menu.getNumRecipes(); index++) {
            int visibleIndex = index - this.startIndex;
            int xStart = x + visibleIndex % 4 * 16;
            int yStart = y + visibleIndex / 4 * 18 + 2;
            ResourceLocation sprite;
            if (index == this.menu.getSelectedRecipeIndex()) {
                sprite = RECIPE_SELECTED_SPRITE;
            } else if (mouseX >= xStart && mouseY >= yStart
                    && mouseX < xStart + 16 && mouseY < yStart + 18) {
                sprite = RECIPE_HIGHLIGHTED_SPRITE;
            } else {
                sprite = RECIPE_SPRITE;
            }
            GuiGraphicsCompat.blitSprite(guiGraphics, sprite, xStart, yStart - 1, 16, 18);
        }
    }

    private void renderRecipes(GuiGraphics guiGraphics, int x, int y, int lastVisibleElementIndex) {
        List<RecipeHolder<SawmillRecipe>> recipes = this.menu.getRecipes();
        for (int index = this.startIndex; index < lastVisibleElementIndex
                && index < this.menu.getNumRecipes(); index++) {
            int visibleIndex = index - this.startIndex;
            int xStart = x + visibleIndex % 4 * 16;
            int yStart = y + visibleIndex / 4 * 18 + 2;
            guiGraphics.renderItem(recipes.get(index).value()
                    .assemble(new net.minecraft.world.item.crafting.SingleRecipeInput(
                            net.minecraft.world.item.ItemStack.EMPTY), this.minecraft.level.registryAccess()),
                    xStart, yStart);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.scrolling = false;
        if (this.displayRecipes) {
            int recipesX = this.leftPos + 52;
            int recipesY = this.topPos + 14;
            int lastVisible = this.startIndex + 12;
            for (int index = this.startIndex; index < lastVisible; index++) {
                int visibleIndex = index - this.startIndex;
                double relativeX = mouseX - (recipesX + visibleIndex % 4 * 16);
                double relativeY = mouseY - (recipesY + visibleIndex / 4 * 18);
                if (relativeX >= 0.0 && relativeY >= 0.0
                        && relativeX < 16.0 && relativeY < 18.0
                        && this.menu.clickMenuButton(this.minecraft.player, index)) {
                    Minecraft.getInstance().getSoundManager().play(
                            SimpleSoundInstance.forUI(SoundEvents.UI_STONECUTTER_SELECT_RECIPE, 1.0F));
                    this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, index);
                    return true;
                }
            }

            int scrollerX = this.leftPos + 119;
            int scrollerY = this.topPos + 9;
            if (mouseX >= scrollerX && mouseX < scrollerX + 12
                    && mouseY >= scrollerY && mouseY < scrollerY + 54) {
                this.scrolling = true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.scrolling && this.isScrollBarActive()) {
            int top = this.topPos + 14;
            int bottom = top + 54;
            this.scrollOffs = ((float) mouseY - top - 7.5F) / ((float) (bottom - top) - 15.0F);
            this.scrollOffs = Mth.clamp(this.scrollOffs, 0.0F, 1.0F);
            this.startIndex = (int) ((double) (this.scrollOffs * this.getOffscreenRows()) + 0.5) * 4;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.isScrollBarActive()) {
            int offscreenRows = this.getOffscreenRows();
            this.scrollOffs = Mth.clamp(this.scrollOffs - (float) scrollY / offscreenRows, 0.0F, 1.0F);
            this.startIndex = (int) ((double) (this.scrollOffs * offscreenRows) + 0.5) * 4;
        }
        return true;
    }

    private boolean isScrollBarActive() {
        return this.displayRecipes && this.menu.getNumRecipes() > 12;
    }

    private int getOffscreenRows() {
        return (this.menu.getNumRecipes() + 3) / 4 - 3;
    }

    private void containerChanged() {
        this.displayRecipes = this.menu.hasInputItem();
        if (!this.displayRecipes) {
            this.scrollOffs = 0.0F;
            this.startIndex = 0;
        }
    }
}
