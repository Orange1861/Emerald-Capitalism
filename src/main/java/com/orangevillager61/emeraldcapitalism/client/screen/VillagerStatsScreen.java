package com.orangevillager61.emeraldcapitalism.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.menu.VillagerStatsMenu;
import com.orangevillager61.emeraldcapitalism.client.presentation.VillageStatsPresentation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.util.GuiGraphicsCompat;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class VillagerStatsScreen extends AbstractContainerScreen<VillagerStatsMenu> {

    private static final ResourceLocation STATS_TEXTURE =
            ModIds.id("textures/gui/villager_stats.png");
    private static final ResourceLocation INVENTORY_TEXTURE =
            ModIds.id("textures/gui/villager_inv.png");
    private static final int PLAYER_INVENTORY_Y = 137;
    private static final int PLAYER_INVENTORY_LABEL_OFFSET = 11;
    private static final int STATS_X_OFFSET = 10;
    private static final int STATS_Y_OFFSET = 33;
    private static final int PAGE_HEADER_Y_OFFSET = 20;
    private static final int PAGE_BUTTON_Y_OFFSET = 16;
    private static final int PAGE_BUTTON_HEIGHT = 12;
    private static final int PAGE_BUTTON_WIDTH = 42;
    private static final int PAGE_BUTTON_GAP = 6;
    private static final int PAGE_BUTTON_RIGHT_PADDING = 7;
    private static final int VILLAGER_SLOT_X_START = 7;
    private static final int INVENTORY_PAGE_INDEX = 4;
    private static final ResourceLocation FOOD_EMPTY_SPRITE =
            ResourceLocation.withDefaultNamespace("hud/food_empty");
    private static final ResourceLocation FOOD_FULL_SPRITE =
            ResourceLocation.withDefaultNamespace("hud/food_full");
    private static final ResourceLocation FOOD_HALF_SPRITE =
            ResourceLocation.withDefaultNamespace("hud/food_half");
    private static final ResourceLocation HEART_EMPTY_SPRITE =
            ResourceLocation.withDefaultNamespace("hud/heart/container");
    private static final ResourceLocation HEART_FULL_SPRITE =
            ResourceLocation.withDefaultNamespace("hud/heart/full");
    private static final ResourceLocation HEART_HALF_SPRITE =
            ResourceLocation.withDefaultNamespace("hud/heart/half");
    private static final ResourceLocation HEART_WITHERED_FULL_SPRITE =
            ResourceLocation.withDefaultNamespace("hud/heart/withered_full");
    private static final ResourceLocation HEART_WITHERED_HALF_SPRITE =
            ResourceLocation.withDefaultNamespace("hud/heart/withered_half");
    private final List<PageDefinition> pages = new ArrayList<>();
    private int activePageIndex = 0;

    public VillagerStatsScreen(VillagerStatsMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 220;
        this.inventoryLabelY = PLAYER_INVENTORY_Y - PLAYER_INVENTORY_LABEL_OFFSET;
        this.inventoryLabelX = VILLAGER_SLOT_X_START;
        this.pages.add(new PageDefinition("Basic", STATS_TEXTURE, this::renderBasicInformationTab));
        this.pages.add(new PageDefinition("Health", STATS_TEXTURE, this::renderHealthTab));
        this.pages.add(new PageDefinition("Job", STATS_TEXTURE, this::renderJobTab));
        this.pages.add(new PageDefinition("Family", STATS_TEXTURE, this::renderParentsTab));
        this.pages.add(new PageDefinition("Inventory", INVENTORY_TEXTURE, (guiGraphics, x, y) -> {
        }));
    }

    @Override
    protected void init() {
        super.init();
        this.updateVillagerSlotVisibility();
        this.clearWidgets();
        this.addRenderableWidgetButtons();
    }

    private void addRenderableWidgetButtons() {
        int buttonY = this.topPos + PAGE_BUTTON_Y_OFFSET;
        int totalWidth = (PAGE_BUTTON_WIDTH * 2) + PAGE_BUTTON_GAP;
        int rightEdge = this.leftPos + this.imageWidth - PAGE_BUTTON_RIGHT_PADDING;
        int startX = rightEdge - totalWidth;
        this.addRenderableWidget(Button.builder(Component.literal("< Prev"), press ->
                        this.setActivePageIndex(this.activePageIndex - 1))
                .bounds(startX, buttonY, PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Next >"), press ->
                        this.setActivePageIndex(this.activePageIndex + 1))
                .bounds(startX + PAGE_BUTTON_WIDTH + PAGE_BUTTON_GAP, buttonY, PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT)
                .build());
    }

    private void setActivePageIndex(int newPageIndex) {
        this.activePageIndex = Math.floorMod(newPageIndex, this.pages.size());
        this.updateVillagerSlotVisibility();
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        PageDefinition activePage = pages.get(activePageIndex);
        GuiGraphicsCompat.blit(guiGraphics, activePage.texture(), x, y, 0, 0,
                this.imageWidth, this.imageHeight, this.imageWidth, this.imageHeight);

        activePage.renderer().render(guiGraphics, x, y);
    }

    private void renderBasicInformationTab(GuiGraphics guiGraphics, int x, int y) {
        int statsX = x + STATS_X_OFFSET;
        int statsY = y + STATS_Y_OFFSET;

        renderFirstName(guiGraphics, statsX, statsY);

        // Wandering traders do not have Villager gossip/reputation in vanilla.
        if (this.menu.hasPlayerReputation()) {
            renderReputation(guiGraphics, statsX, statsY + 18);
        } else {
            guiGraphics.drawString(this.font, "Opinion of You: Unavailable", statsX, statsY + 18,
                    0x606060, false);
        }
    }

    private void renderHealthTab(GuiGraphics guiGraphics, int x, int y) {
        int statsX = x + STATS_X_OFFSET;
        int statsY = y + STATS_Y_OFFSET;

        renderHealthBar(guiGraphics, statsX, statsY);
        if (this.menu.isWanderingTrader()) {
            guiGraphics.drawString(this.font, "Hunger: Disabled", statsX, statsY + 18,
                    0x606060, false);
        } else {
            renderHungerBar(guiGraphics, statsX, statsY + 18);
        }
        renderIllness(guiGraphics, statsX, statsY + 36);
    }

    private void renderJobTab(GuiGraphics guiGraphics, int x, int y) {
        int statsX = x + STATS_X_OFFSET;
        int statsY = y + STATS_Y_OFFSET;

        String professionLabel = "Profession:";
        guiGraphics.drawString(this.font, professionLabel, statsX, statsY, 0x404040, false);
        guiGraphics.drawString(this.font, this.menu.getProfession(),
                statsX + this.font.width(professionLabel) + 6, statsY, 0x404040, false);

        // Wandering traders have no mod bank account; their trade offers are
        // intentionally not changed or exposed as bank data in this pass.
        if (this.menu.isWanderingTrader()) {
            guiGraphics.drawString(this.font, "Bank balance: Unavailable", statsX, statsY + 18,
                    0x606060, false);
        } else {
            renderEmeraldBalance(guiGraphics, statsX, statsY + 18);
        }
        renderEmeraldInventory(guiGraphics, statsX, statsY + 36);
    }

    private void renderParentsTab(GuiGraphics guiGraphics, int x, int y) {
        int statsX = x + STATS_X_OFFSET;
        int statsY = y + STATS_Y_OFFSET;
        renderParentInfo(guiGraphics, statsX, statsY);
    }

    private boolean shouldRenderVillagerSlots() {
        return this.activePageIndex == INVENTORY_PAGE_INDEX;
    }

    private void updateVillagerSlotVisibility() {
        this.menu.setVillagerSlotsVisible(this.shouldRenderVillagerSlots());
    }

    private boolean isVillagerSlot(net.minecraft.world.inventory.Slot slot) {
        return this.menu.isVillagerSlot(slot);
    }

    @Override
    protected void renderSlot(@NotNull GuiGraphics guiGraphics, net.minecraft.world.inventory.@NotNull Slot slot) {
        if (!shouldRenderVillagerSlots() && isVillagerSlot(slot)) {
            return;
        }
        super.renderSlot(guiGraphics, slot);
    }

    private void renderHealthBar(GuiGraphics guiGraphics, int x, int y) {
        int health = this.menu.getHealth();
        int maxHealth = Math.max(1, this.menu.getMaxHealth());
        guiGraphics.drawString(this.font, "Health:", x, y, 0x404040, false);

        int labelWidth = this.font.width("Health:");
        int iconY = y - 1;
        int iconStartX = x + labelWidth + 6;
        int maxHearts = 10;
        boolean withered = this.menu.getIllnessPhase() == 2;
        float healthPerHeart = maxHealth / (float) maxHearts;
        float halfHeartValue = healthPerHeart / 2.0F;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (int i = 0; i < maxHearts; i++) {
            int iconX = iconStartX + i * 8;
            GuiGraphicsCompat.blitSprite(guiGraphics, HEART_EMPTY_SPRITE, iconX, iconY, 9, 9);

            float heartHealth = health - (i * healthPerHeart);
            if (heartHealth >= healthPerHeart) {
                GuiGraphicsCompat.blitSprite(guiGraphics, withered ? HEART_WITHERED_FULL_SPRITE : HEART_FULL_SPRITE,
                        iconX, iconY, 9, 9);
            } else if (heartHealth >= halfHeartValue) {
                GuiGraphicsCompat.blitSprite(guiGraphics, withered ? HEART_WITHERED_HALF_SPRITE : HEART_HALF_SPRITE,
                        iconX, iconY, 9, 9);
            }
        }
        RenderSystem.disableBlend();
    }

    private void renderFirstName(GuiGraphics guiGraphics, int x, int y) {
        String label = "First Name:";
        guiGraphics.drawString(this.font, label, x, y, 0x404040, false);
        guiGraphics.drawString(this.font, this.menu.getFirstName(),
                x + this.font.width(label) + 6, y, 0x404040, false);
    }

    private void renderIllness(GuiGraphics guiGraphics, int x, int y) {
        int phase = this.menu.getIllnessPhase();
        if (phase == 0) {
            return;
        }

        if (phase == 2) {
            guiGraphics.drawString(this.font, "Illness: Zombkolaps (Rotting Phase)", x, y, 0x6B1F1F, false);
            return;
        }

        int seconds = Math.max(0, this.menu.getIllnessRemainingTicks() / 20);
        guiGraphics.drawString(this.font,
                "Illness: Zombkolaps (Turning Phase): " + (seconds / 60) + ":" + String.format("%02d", seconds % 60),
                x, y, 0x4CAF50, false);
    }

    private void renderHungerBar(GuiGraphics guiGraphics, int x, int y) {
        int hungerLevel = this.menu.getHungerLevel();
        guiGraphics.drawString(this.font, "Hunger:", x, y, 0x404040, false);

        int labelWidth = this.font.width("Hunger:");
        int iconY = y - 1;
        int iconStartX = x + labelWidth + 6;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (int i = 0; i < 10; i++) {
            int iconX = iconStartX + i * 8;

            GuiGraphicsCompat.blitSprite(guiGraphics, FOOD_EMPTY_SPRITE, iconX, iconY, 9, 9);

            if (i < hungerLevel / 2) {
                GuiGraphicsCompat.blitSprite(guiGraphics, FOOD_FULL_SPRITE, iconX, iconY, 9, 9);
            }
            else if (i == hungerLevel / 2 && hungerLevel % 2 != 0) {
                GuiGraphicsCompat.blitSprite(guiGraphics, FOOD_HALF_SPRITE, iconX, iconY, 9, 9);
            }
        }
        RenderSystem.disableBlend();
    }

    private void renderEmeraldBalance(GuiGraphics guiGraphics, int x, int y) {
        int emeraldBalance = this.menu.getEmeraldBalance();

        guiGraphics.drawString(this.font, "Balance:", x, y, 0x404040, false);

        int labelWidth = this.font.width("Balance:");
        int iconX = x + labelWidth + 6;
        int iconY = y - 4;
        int countX = iconX + 18;

        guiGraphics.renderItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD), iconX, iconY);

        int color;
        String displayText;
        if (emeraldBalance < 0) {
            color = 0xCC3333;
            displayText = String.valueOf(emeraldBalance);
        } else if (emeraldBalance > 0) {
            color = 0x2E7D32;
            displayText = "+" + emeraldBalance;
        } else {
            color = 0x404040;
            displayText = "0";
        }
        guiGraphics.drawString(this.font, displayText, countX, y, color, false);
    }

    private void renderEmeraldInventory(GuiGraphics guiGraphics, int x, int y) {
        int emeraldCount = this.menu.getEmeraldInventoryCount();

        guiGraphics.drawString(this.font, "Emeralds:", x, y, 0x404040, false);

        int labelWidth = this.font.width("Emeralds:");
        int iconX = x + labelWidth + 6;
        int iconY = y - 4;
        int countX = iconX + 18;

        guiGraphics.renderItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD), iconX, iconY);

        guiGraphics.drawString(this.font, String.valueOf(emeraldCount), countX, y, 0x404040, false);
    }

    private void renderReputation(GuiGraphics guiGraphics, int x, int y) {
        int reputation = this.menu.getPlayerReputation();

        String label = "Opinion of You:";
        guiGraphics.drawString(this.font, label, x, y, 0x404040, false);

        int labelWidth = this.font.width(label);
        int valueX = x + labelWidth + 6;
        int color = reputation < 0 ? 0xCC3333 : reputation > 0 ? 0x2E7D32 : 0x404040;
        guiGraphics.drawString(this.font, String.valueOf(reputation), valueX, y, color, false);
    }

    private void renderParentInfo(GuiGraphics guiGraphics, int x, int y) {
        guiGraphics.drawString(this.font, "Family:", x, y, 0x404040, false);

        String parent1Name = this.menu.getParent1Name();
        String parent2Name = this.menu.getParent2Name();

        if (!this.menu.hasFamilyProperties()) {
            guiGraphics.drawString(this.font, "Family: Unavailable", x, y, 0x606060, false);
            guiGraphics.drawString(this.font, "Breeding: Unavailable", x, y + 24, 0x606060, false);
            return;
        }

        String displayName1 = (parent1Name != null) ? parent1Name : "Unknown";
        String displayName2 = (parent2Name != null) ? parent2Name : "Unknown";

        String parentText = displayName1 + " & " + displayName2;
        guiGraphics.drawString(this.font, parentText, x + 10, y + 10, 0x606060, false);

        int cooldownTicks = this.menu.getBreedingCooldownTicks();
        guiGraphics.drawString(this.font, "Breeding Cooldown:", x, y + 24, 0x404040, false);
        if (this.menu.getVillager() != null && this.menu.getVillager().isBaby()) {
            guiGraphics.drawString(this.font, "Baby Villager: Cannot Breed", x + 10, y + 34, 0xCC3333, false);
        } else if (cooldownTicks <= 0) {
            guiGraphics.drawString(this.font, "Can Breed", x + 10, y + 34, 0x2E7D32, false);
        } else {
            guiGraphics.drawString(this.font, VillageStatsPresentation.formatCooldownTicks(cooldownTicks, 20),
                    x + 10, y + 34, 0xCC3333, false);
        }
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderTooltip(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.hoveredSlot != null && !shouldRenderVillagerSlots() && isVillagerSlot(this.hoveredSlot)) {
            return;
        }
        super.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Component inventoryTitle;
        if (this.menu.getVillager() != null) {
            Component villagerName = this.menu.getVillager().getDisplayName();
            inventoryTitle = Component.literal(villagerName.getString());
        } else {
            inventoryTitle = Component.literal("Villager");
        }
        guiGraphics.drawString(this.font, inventoryTitle, VILLAGER_SLOT_X_START, 7, 0x404040, false);

        int headerX = this.titleLabelX;
        int headerY = PAGE_HEADER_Y_OFFSET;
        guiGraphics.drawString(this.font, Component.literal(pages.get(activePageIndex).label()),
                headerX, headerY, 0x404040, false);

    }

    private record PageDefinition(String label, ResourceLocation texture, PageRenderer renderer) {
    }

    private interface PageRenderer {
        void render(GuiGraphics guiGraphics, int x, int y);
    }
}
