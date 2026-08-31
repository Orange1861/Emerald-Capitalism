package com.orangevillager61.emeraldcapitalism.client.screen;

import com.orangevillager61.emeraldcapitalism.menu.EmeraldSkrimisherMenu;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/** Client screen for the Emerald Skrimisher's read-only inventory view. */
public final class EmeraldSkrimisherScreen extends AbstractContainerScreen<EmeraldSkrimisherMenu> {

    private static final ResourceLocation BACKGROUND_TEXTURE =
            ModIds.id("textures/gui/villager_stats.png");
    private static final ResourceLocation SLOT_TEXTURE =
            ModIds.id("textures/gui/villager_inv.png");
    private static final int LABEL_X = 8;
    private static final int TITLE_Y = 7;
    private static final int ENTITY_INVENTORY_LABEL_Y = 18;
    private static final int PLAYER_INVENTORY_LABEL_Y = 126;
    private static final int SLOT_BACKGROUND_X = 7;
    private static final int SLOT_BACKGROUND_Y = 30;
    private static final int SLOT_ROW_WIDTH = 162;
    private static final int SLOT_ROW_HEIGHT = 18;

    public EmeraldSkrimisherScreen(EmeraldSkrimisherMenu menu, Inventory playerInventory,
                                   Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 220;
        this.titleLabelX = LABEL_X;
        this.titleLabelY = TITLE_Y;
        this.inventoryLabelX = LABEL_X;
        this.inventoryLabelY = PLAYER_INVENTORY_LABEL_Y;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(BACKGROUND_TEXTURE, this.leftPos, this.topPos, 0, 0,
                this.imageWidth, this.imageHeight, this.imageWidth, this.imageHeight);
        guiGraphics.blit(SLOT_TEXTURE,
                this.leftPos + SLOT_BACKGROUND_X, this.topPos + SLOT_BACKGROUND_Y,
                SLOT_BACKGROUND_X, SLOT_BACKGROUND_Y,
                SLOT_ROW_WIDTH, SLOT_ROW_HEIGHT, this.imageWidth, this.imageHeight);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, LABEL_X, TITLE_Y,
                0x404040, false);
        guiGraphics.drawString(this.font, Component.literal("Inventory"), LABEL_X,
                ENTITY_INVENTORY_LABEL_Y, 0x404040, false);
        guiGraphics.drawString(this.font, Component.translatable("container.inventory"),
                LABEL_X, PLAYER_INVENTORY_LABEL_Y, 0x404040, false);
    }
}
