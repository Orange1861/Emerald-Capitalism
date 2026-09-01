package com.orangevillager61.emeraldcapitalism.client.screen;

import com.orangevillager61.emeraldcapitalism.client.renderer.VillagePOIOverlayRenderer;
import com.orangevillager61.emeraldcapitalism.menu.VillageManagerMenu;
import com.orangevillager61.emeraldcapitalism.network.RequestVillagePOIsPacket;
import com.orangevillager61.emeraldcapitalism.network.TogglePOIOverlayPacket;
import com.orangevillager61.emeraldcapitalism.client.util.MinecraftExecutionCompat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Screen for the Village Manager block. Displays village summary info:
 * name/ID, bell position, bounding box coordinates, and member count.
 */
public class VillageManagerScreen extends AbstractContainerScreen<VillageManagerMenu> {

    private static final int BG_COLOR = 0xCC101010;
    private static final int TITLE_COLOR = 0xFFFFFF;
    private static final int LABEL_COLOR = 0xAAAAAA;
    private static final int VALUE_COLOR = 0x55FF55;
    private static final int NO_VILLAGE_COLOR = 0xFF5555;

    public VillageManagerScreen(VillageManagerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 220;
        this.imageHeight = 160;
    }

    @Override
    protected void init() {
        super.init();
        UUID villageId = menu.getVillageId();
        PacketDistributor.sendToServer(villageId != null
                ? RequestVillagePOIsPacket.forVillage(villageId)
                : RequestVillagePOIsPacket.nearest());
        // Auto-enable the bounding box overlay when accessing the village manager
        if (!VillagePOIOverlayRenderer.isEnabled()) {
            VillagePOIOverlayRenderer.toggle();
            PacketDistributor.sendToServer(villageId != null
                    ? TogglePOIOverlayPacket.forVillage(villageId)
                    : new TogglePOIOverlayPacket());
        }
        // Center the title
        this.titleLabelX = (this.imageWidth - this.font.width(this.title)) / 2;
        this.titleLabelY = 8;
        // Hide inventory label
        this.inventoryLabelY = -999;

        if (this.minecraft != null && this.minecraft.player != null) {
            // Close the server-backed menu before replacing this container screen.
            // The client sends the close packet and returns its local menu to the
            // inventory menu; the server then does the same for ServerPlayer.
            this.minecraft.player.closeContainer();
            MinecraftExecutionCompat.execute(this.minecraft, () -> {
                if (this.minecraft != null) {
                    this.minecraft.setScreen(new VillagePOIScreen(villageId));
                }
            });
        }
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        // Dark semi-transparent background
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BG_COLOR);
        // Border
        guiGraphics.renderOutline(leftPos, topPos, imageWidth, imageHeight, 0xFF555555);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Title
        guiGraphics.drawCenteredString(this.font, this.title, this.imageWidth / 2, 8, TITLE_COLOR);

        VillageManagerMenu menu = this.menu;
        int y = 28;
        int labelX = 10;
        int valueX = 90;

        if (menu.getVillageId() == null) {
            guiGraphics.drawString(this.font, "No village linked.", labelX, y, NO_VILLAGE_COLOR, false);
            y += 14;
            guiGraphics.drawString(this.font, "Place inside a village", labelX, y, LABEL_COLOR, false);
            y += 11;
            guiGraphics.drawString(this.font, "bounding box to link.", labelX, y, LABEL_COLOR, false);
            return;
        }

        // Village ID
        guiGraphics.drawString(this.font, "Village ID:", labelX, y, LABEL_COLOR, false);
        guiGraphics.drawString(this.font, menu.getVillageName(), valueX, y, VALUE_COLOR, false);
        y += 14;

        // Bell position
        BlockPos bell = menu.getBellPos();
        guiGraphics.drawString(this.font, "Bell:", labelX, y, LABEL_COLOR, false);
        guiGraphics.drawString(this.font, bell.getX() + ", " + bell.getY() + ", " + bell.getZ(), valueX, y, VALUE_COLOR, false);
        y += 14;

        // Bounding box
        guiGraphics.drawString(this.font, "Bounds min:", labelX, y, LABEL_COLOR, false);
        guiGraphics.drawString(this.font, String.format("%.0f, %.0f, %.0f", menu.getMinX(), menu.getMinY(), menu.getMinZ()), valueX, y, VALUE_COLOR, false);
        y += 12;
        guiGraphics.drawString(this.font, "Bounds max:", labelX, y, LABEL_COLOR, false);
        guiGraphics.drawString(this.font, String.format("%.0f, %.0f, %.0f", menu.getMaxX(), menu.getMaxY(), menu.getMaxZ()), valueX, y, VALUE_COLOR, false);
        y += 14;

        // Member count
        guiGraphics.drawString(this.font, "Villagers:", labelX, y, LABEL_COLOR, false);
        guiGraphics.drawString(this.font, String.valueOf(menu.getMemberCount()), valueX, y, VALUE_COLOR, false);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
