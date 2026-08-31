package com.orangevillager61.emeraldcapitalism.client.screen;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.client.EmeraldCapitalismClient;
import com.orangevillager61.emeraldcapitalism.client.renderer.VillagePOIOverlayRenderer;
import com.orangevillager61.emeraldcapitalism.client.presentation.PresentationStyle;
import com.orangevillager61.emeraldcapitalism.client.presentation.VillagePOIPresentation;
import com.orangevillager61.emeraldcapitalism.client.presentation.VillageStatsPresentation;
import com.orangevillager61.emeraldcapitalism.network.RenameVillagePacket;
import com.orangevillager61.emeraldcapitalism.network.BecomeGovernorCandidatePacket;
import com.orangevillager61.emeraldcapitalism.network.DuplicateVillageBlocksPacket;
import com.orangevillager61.emeraldcapitalism.network.RequestExpandBoundsPacket;
import com.orangevillager61.emeraldcapitalism.network.RequestFullScanPacket;
import com.orangevillager61.emeraldcapitalism.network.ResetVillageCachePacket;
import com.orangevillager61.emeraldcapitalism.network.SetVillageRepairPacket;
import com.orangevillager61.emeraldcapitalism.network.TogglePOIOverlayPacket;
import com.orangevillager61.emeraldcapitalism.network.VillagePOIClientCache;
import com.orangevillager61.emeraldcapitalism.world.village.JobSiteEntry;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRelationship;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.*;

/**
 * GUI screen displaying village data in four tabs:
 * <ul>
 *   <li><b>Villagers</b>: scrollable, sortable table of villager POI records</li>
 *   <li><b>Village Stats</b>: village-wide statistics (bed counts, etc.)</li>
 *   <li><b>Ownership</b>: governor-only village settings</li>
 * </ul>
 */
public class VillagePOIScreen extends Screen {

    private static final int ROW_HEIGHT = 14;
    private static final int PADDING = 10;
    private static final int TAB_BUTTON_WIDTH = 90;
    private static final int TAB_BUTTON_HEIGHT = 20;
    private static final int TAB_BUTTON_GAP = 8;
    private static final int TOP_RIGHT_BUTTON_GAP = 8;
    private static final int OWNERSHIP_ROW_GAP = 12;
    private static final int REPAIR_BUTTON_X = 200;
    private static final int REPAIR_BUTTON_WIDTH = 100;
    private static final int REPAIR_BUTTON_GAP = 20;
    private static final int RESET_CACHE_BUTTON_X = REPAIR_BUTTON_X + REPAIR_BUTTON_WIDTH + REPAIR_BUTTON_GAP;

    // Column X-offsets (relative to table left)
    private static final int COL_NAME = 0;
    // Leave each value enough room for the preceding header and for the
    // longest normal row value. The old offsets made the name and profession
    // columns overlap at the larger GUI scales commonly used in-game.
    private static final int COL_PROFESSION = 105;
    private static final int COL_HEALTH = 205;
    private static final int COL_OPINION = 255;
    private static final int COL_BED = 350;
    private static final int MAX_TABLE_WIDTH = 460;

    private enum Tab { VILLAGERS, VILLAGE_STATS, JOB_SITES, OWNERSHIP }

    private Tab activeTab = Tab.VILLAGERS;
    private VillagePOIPresentation.SortMode sortMode = VillagePOIPresentation.SortMode.NAME_ASC;
    private List<VillagePOIPresentation.VillagerSnapshot> sortedRecords = List.of();
    private long lastRecordsCacheTimestamp;
    private int scrollOffset;
    private int tableLeft;
    private int tableWidth;
    private int contentTop;
    private int maxVisibleRows;

    private Button villagersTabBtn;
    private Button statsTabBtn;
    private Button jobSitesTabBtn;
    private Button ownershipTabBtn;
    private Button expandBoundsBtn;
    private Button fullScanBtn;
    private int statsScrollOffset;
    private EditBox renameBox;
    private Button renameBtn;
    private Button welcomeBtn;
    private Button claimOwnershipBtn;
    private Button duplicateBlocksBtn;
    private Button farmlandRepairBtn;
    private Button farmlandResetBtn;
    private Button doorRepairBtn;
    private Button doorResetBtn;
    private boolean renameMode;
    private int jobSitesScrollOffset;
    private long lastJobSitesCacheTimestamp;
    private List<VillagePOIPresentation.JobSiteRow> cachedJobSiteLines = List.of();
    private long lastOpinionRefreshTick;
    @Nullable
    private final UUID requestedVillageId;

    public VillagePOIScreen() {
        this(null);
    }

    public VillagePOIScreen(@Nullable UUID requestedVillageId) {
        super(Component.translatable("screen.emeraldcapitalism.village_poi"));
        this.requestedVillageId = requestedVillageId;
    }

    /**
     * Requests a lightweight viewer-specific snapshot while the ledger is open,
     * keeping health and both opinion displays current without a full scan.
     */
    @Override
    public void tick() {
        super.tick();
        if (minecraft == null || minecraft.player == null || !VillagePOIClientCache.hasData()) {
            return;
        }
        long now = minecraft.player.tickCount;
        if (now - lastOpinionRefreshTick >= 20) {
            UUID villageId = requestedVillageId != null
                    ? requestedVillageId
                    : VillagePOIClientCache.getVillageId();
            if (villageId != null) {
                PacketDistributor.sendToServer(
                        new com.orangevillager61.emeraldcapitalism.network.RequestVillagePOIDynamicDataPacket(
                                villageId, VillagePOIClientCache.hasCompletedScan()));
            }
            lastOpinionRefreshTick = now;
        }
    }

    @Override
    protected void init() {
        super.init();
        tableWidth = Math.min(MAX_TABLE_WIDTH, Math.max(1, width - PADDING * 2));
        tableLeft = (width - tableWidth) / 2;
        // Layout: title (y=10), tabs (y=26), content starts below
        int tabY = PADDING + font.lineHeight + 6;
        contentTop = tabY + TAB_BUTTON_HEIGHT + 16;
        maxVisibleRows = Math.max(1, (height - contentTop - 30) / ROW_HEIGHT);
        int tabRowWidth = Math.max(1, width - PADDING * 2);
        int overlayWidth = Math.min(100, Math.max(84, tabRowWidth / 5));
        int tabButtonWidth = Math.min(TAB_BUTTON_WIDTH,
                Math.max(60, (tabRowWidth - overlayWidth - TAB_BUTTON_GAP * 4) / 4));
        tabRowWidth = tabButtonWidth * 4 + overlayWidth + TAB_BUTTON_GAP * 4;
        int tabRowStartX = (width - tabRowWidth) / 2;

        // Tab buttons
        villagersTabBtn = addRenderableWidget(Button.builder(
                Component.literal("Villagers"),
                btn -> switchTab(Tab.VILLAGERS))
                .bounds(tabRowStartX, tabY, tabButtonWidth, TAB_BUTTON_HEIGHT)
                .build());

        statsTabBtn = addRenderableWidget(Button.builder(
                Component.literal("Village Stats"),
                btn -> switchTab(Tab.VILLAGE_STATS))
                .bounds(tabRowStartX + tabButtonWidth + TAB_BUTTON_GAP, tabY, tabButtonWidth, TAB_BUTTON_HEIGHT)
                .build());

        jobSitesTabBtn = addRenderableWidget(Button.builder(
                Component.literal("Job Sites"),
                btn -> switchTab(Tab.JOB_SITES))
                .bounds(tabRowStartX + (tabButtonWidth + TAB_BUTTON_GAP) * 2, tabY, tabButtonWidth, TAB_BUTTON_HEIGHT)
                .build());

        ownershipTabBtn = addRenderableWidget(Button.builder(
                Component.literal("Ownership"),
                btn -> switchTab(Tab.OWNERSHIP))
                .bounds(tabRowStartX + (tabButtonWidth + TAB_BUTTON_GAP) * 3, tabY, tabButtonWidth, TAB_BUTTON_HEIGHT)
                .build());

        int titleRowY = PADDING - 1;

        // Expand Bounds button (title row, right side)
        expandBoundsBtn = addRenderableWidget(Button.builder(
                Component.literal("Expand Bounds"),
                btn -> {
                    btn.active = false;
                    PacketDistributor.sendToServer(new RequestExpandBoundsPacket());
                })
                .bounds(width - 95 - TOP_RIGHT_BUTTON_GAP - 80 - PADDING, titleRowY, 95, font.lineHeight + 4)
                .build());

        // Full Scan button (title row, right of Expand Bounds)
        fullScanBtn = addRenderableWidget(Button.builder(
                Component.literal("Full Scan"),
                btn -> {
                    btn.active = false;
                    PacketDistributor.sendToServer(new RequestFullScanPacket());
                })
                .bounds(width - 80 - PADDING, titleRowY, 80, font.lineHeight + 4)
                .build());
        // Ownership controls live in the Ownership tab.
        claimOwnershipBtn = addRenderableWidget(Button.builder(
                Component.literal("Claim Village Ownership"),
                btn -> claimVillageOwnership())
                .bounds(tableLeft, contentTop, 170, TAB_BUTTON_HEIGHT)
                .build());
        duplicateBlocksBtn = addRenderableWidget(Button.builder(
                Component.literal("Duplicate"),
                btn -> duplicateVillageBlocks())
                .bounds(tableLeft + 180, contentTop, 90, TAB_BUTTON_HEIGHT)
                .build());

        int ownershipDetailsY = contentTop + TAB_BUTTON_HEIGHT + OWNERSHIP_ROW_GAP;
        renameBtn = addRenderableWidget(Button.builder(
                Component.literal("Rename"),
                btn -> toggleRename())
                .bounds(tableLeft, ownershipDetailsY, 90, TAB_BUTTON_HEIGHT)
                .build());
        renameBox = new EditBox(font, tableLeft + 96, ownershipDetailsY, 220, TAB_BUTTON_HEIGHT,
                Component.literal("Village Name"));
        renameBox.setMaxLength(64);
        renameBox.setVisible(false);
        renameBox.setResponder(s -> {}); // no-op
        addRenderableWidget(renameBox);

        welcomeBtn = addRenderableWidget(Button.builder(
                Component.literal("Welcome Msg"),
                btn -> {
                    if (requireGovernor("change the welcome message")) {
                        minecraft.setScreen(new WelcomeMessageScreen(this));
                    }
                })
                .bounds(tableLeft + 330, ownershipDetailsY, 100, TAB_BUTTON_HEIGHT)
                .build());

        int repairRowOneY = ownershipDetailsY + TAB_BUTTON_HEIGHT + OWNERSHIP_ROW_GAP;
        farmlandRepairBtn = addRenderableWidget(Button.builder(
                Component.literal("Repair"),
                btn -> toggleVillageRepair(SetVillageRepairPacket.FARMLAND))
                .bounds(tableLeft + REPAIR_BUTTON_X, repairRowOneY,
                        REPAIR_BUTTON_WIDTH, TAB_BUTTON_HEIGHT)
                .build());
        farmlandResetBtn = addRenderableWidget(Button.builder(
                Component.literal("Reset List"),
                btn -> resetVillageCache(SetVillageRepairPacket.FARMLAND))
                .bounds(tableLeft + RESET_CACHE_BUTTON_X, repairRowOneY,
                        REPAIR_BUTTON_WIDTH, TAB_BUTTON_HEIGHT)
                .build());
        doorRepairBtn = addRenderableWidget(Button.builder(
                Component.literal("Repair"),
                btn -> toggleVillageRepair(SetVillageRepairPacket.DOORS))
                .bounds(tableLeft + REPAIR_BUTTON_X, repairRowOneY + ROW_HEIGHT,
                        REPAIR_BUTTON_WIDTH, TAB_BUTTON_HEIGHT)
                .build());
        doorResetBtn = addRenderableWidget(Button.builder(
                Component.literal("Reset List"),
                btn -> resetVillageCache(SetVillageRepairPacket.DOORS))
                .bounds(tableLeft + RESET_CACHE_BUTTON_X, repairRowOneY + ROW_HEIGHT,
                        REPAIR_BUTTON_WIDTH, TAB_BUTTON_HEIGHT)
                .build());

        // Toggle overlay button (tab row, right side)
        String overlayState = VillagePOIOverlayRenderer.isEnabled() ? "ON" : "OFF";
        addRenderableWidget(Button.builder(
                Component.literal("Overlay: " + overlayState),
                btn -> {
                    VillagePOIOverlayRenderer.toggle();
                    UUID villageId = requestedVillageId != null
                            ? requestedVillageId
                            : VillagePOIClientCache.getVillageId();
                    PacketDistributor.sendToServer(villageId != null
                            ? TogglePOIOverlayPacket.forVillage(villageId)
                            : new TogglePOIOverlayPacket());
                    String newState = VillagePOIOverlayRenderer.isEnabled() ? "ON" : "OFF";
                    btn.setMessage(Component.literal("Overlay: " + newState));
                })
                .bounds(tabRowStartX + (tabButtonWidth + TAB_BUTTON_GAP) * 3 + tabButtonWidth + TAB_BUTTON_GAP,
                        tabY, overlayWidth, TAB_BUTTON_HEIGHT)
                .build());

        updateTabButtonStyles();
        refreshSortedRecords();
    }

    private void switchTab(Tab tab) {
        activeTab = tab;
        statsScrollOffset = 0;
        jobSitesScrollOffset = 0;
        if (tab != Tab.OWNERSHIP && renameMode) {
            renameMode = false;
            renameBox.setVisible(false);
            renameBtn.setMessage(Component.literal("Rename"));
        }
        updateTabButtonStyles();
    }

    private void updateTabButtonStyles() {
        villagersTabBtn.active = activeTab != Tab.VILLAGERS;
        statsTabBtn.active = activeTab != Tab.VILLAGE_STATS;
        jobSitesTabBtn.active = activeTab != Tab.JOB_SITES;
        ownershipTabBtn.active = activeTab != Tab.OWNERSHIP;
    }

    private boolean requireGovernor(String action) {
        if (VillagePOIClientCache.getRelationship() == VillageRelationship.GOVERNOR) {
            return true;
        }
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal(
                    "[ECAP] Only the village Governor can " + action + "."));
        }
        return false;
    }

    private void toggleRename() {
        if (!VillagePOIClientCache.hasData() || !requireGovernor("rename this village")) {
            return;
        }
        if (renameMode) {
            String newName = renameBox.getValue().trim();
            UUID villageId = VillagePOIClientCache.getVillageId();
            if (!newName.isEmpty() && villageId != null) {
                PacketDistributor.sendToServer(new RenameVillagePacket(villageId, newName));
            }
            renameMode = false;
            renameBox.setVisible(false);
            renameBtn.setMessage(Component.literal("Rename"));
            return;
        }
        renameMode = true;
        renameBox.setValue(VillagePOIClientCache.getVillageName());
        renameBox.setVisible(true);
        renameBox.setFocused(true);
        renameBox.moveCursorToEnd(false);
        setInitialFocus(renameBox);
        setFocused(renameBox);
        renameBtn.setMessage(Component.literal("Confirm"));
    }

    private void toggleVillageRepair(int featureId) {
        if (!VillagePOIClientCache.hasData() || !requireGovernor("change village repair settings")) {
            return;
        }
        UUID villageId = VillagePOIClientCache.getVillageId();
        if (villageId == null) {
            return;
        }
        boolean enabled = featureId == SetVillageRepairPacket.FARMLAND
                ? !VillagePOIClientCache.isFarmlandRepairEnabled()
                : !VillagePOIClientCache.isDoorRepairEnabled();
        PacketDistributor.sendToServer(new SetVillageRepairPacket(villageId, featureId, enabled));
    }

    private void resetVillageCache(int featureId) {
        if (!VillagePOIClientCache.hasData() || !requireGovernor("reset village caches")) {
            return;
        }
        UUID villageId = VillagePOIClientCache.getVillageId();
        if (villageId != null) {
            PacketDistributor.sendToServer(new ResetVillageCachePacket(villageId, featureId));
        }
    }

    private void refreshSortedRecords() {
        List<VillagePOIPresentation.VillagerSnapshot> records = VillagePOIClientCache.getRecords().stream()
                .map(record -> new VillagePOIPresentation.VillagerSnapshot(
                        record.getDisplayName(), record.getProfession(), record.getHealth(),
                        record.getOpinionOfPlayer(), formatNullablePos(record.getBedPos())))
                .toList();
        sortedRecords = VillagePOIPresentation.sortVillagers(records, sortMode);
        scrollOffset = Math.min(scrollOffset, Math.max(0, sortedRecords.size() - maxVisibleRows));
        lastRecordsCacheTimestamp = VillagePOIClientCache.getUpdateTimestamp();
    }

    private void refreshJobSiteLines() {
        List<JobSiteEntry> jobSites = VillagePOIClientCache.getJobSites();
        if (jobSites.isEmpty()) {
            cachedJobSiteLines = List.of();
            jobSitesScrollOffset = 0;
            lastJobSitesCacheTimestamp = VillagePOIClientCache.getUpdateTimestamp();
            return;
        }

        Map<BlockPos, String> villagerNamesByJobSite = new HashMap<>();
        for (VillagerPOIRecord record : VillagePOIClientCache.getRecords()) {
            BlockPos jobSitePos = record.getJobSitePos();
            if (jobSitePos != null) {
                villagerNamesByJobSite.putIfAbsent(jobSitePos, record.getDisplayName());
            }
        }

        List<VillagePOIPresentation.JobSiteSnapshot> snapshots = jobSites.stream()
                .map(entry -> {
                    String villagerName = villagerNamesByJobSite.get(entry.position());
                    if (villagerName == null || villagerName.isBlank()) {
                        villagerName = entry.claimed() ? "Unknown" : "—";
                    }
                    return new VillagePOIPresentation.JobSiteSnapshot(
                            entry.jobType(), formatPos(entry.position()), entry.claimed(), villagerName);
                })
                .toList();
        cachedJobSiteLines = VillagePOIPresentation.groupJobSites(snapshots);
        jobSitesScrollOffset = Math.min(jobSitesScrollOffset, Math.max(0, cachedJobSiteLines.size() - maxVisibleRows));
        lastJobSitesCacheTimestamp = VillagePOIClientCache.getUpdateTimestamp();
    }

    // Render

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (renameMode && renameBox != null && renameBox.visible && !renameBox.isFocused()) {
            renameBox.setFocused(true);
            setFocused(renameBox);
        }

        boolean scanInProgress = VillagePOIClientCache.isScanInProgress();
        boolean hasData = VillagePOIClientCache.hasData();
        if (expandBoundsBtn != null) expandBoundsBtn.active = hasData && !scanInProgress;
        if (fullScanBtn != null) fullScanBtn.active = hasData && !scanInProgress;
        if (renameBtn != null) {
            renameBtn.visible = activeTab == Tab.OWNERSHIP;
            renameBtn.active = hasData && !scanInProgress;
        }
        if (welcomeBtn != null) {
            welcomeBtn.visible = activeTab == Tab.OWNERSHIP;
            welcomeBtn.active = hasData && !scanInProgress;
        }
        if (renameBox != null) {
            renameBox.visible = activeTab == Tab.OWNERSHIP && renameMode;
        }
        if (farmlandRepairBtn != null) {
            farmlandRepairBtn.visible = activeTab == Tab.OWNERSHIP;
            farmlandRepairBtn.active = hasData && !scanInProgress;
            farmlandRepairBtn.setMessage(Component.literal(
                    "Repair: " + VillagePOIClientCache.isFarmlandRepairEnabled()));
        }
        if (farmlandResetBtn != null) {
            farmlandResetBtn.visible = activeTab == Tab.OWNERSHIP;
            farmlandResetBtn.active = hasData && !scanInProgress;
        }
        if (doorRepairBtn != null) {
            doorRepairBtn.visible = activeTab == Tab.OWNERSHIP;
            doorRepairBtn.active = hasData && !scanInProgress;
            doorRepairBtn.setMessage(Component.literal(
                    "Repair: " + VillagePOIClientCache.isDoorRepairEnabled()));
        }
        if (doorResetBtn != null) {
            doorResetBtn.visible = activeTab == Tab.OWNERSHIP;
            doorResetBtn.active = hasData && !scanInProgress;
        }
        if (claimOwnershipBtn != null) {
            claimOwnershipBtn.visible = activeTab == Tab.OWNERSHIP;
            claimOwnershipBtn.active = hasData && !scanInProgress;
        }
        if (duplicateBlocksBtn != null) {
            duplicateBlocksBtn.visible = activeTab == Tab.OWNERSHIP;
            duplicateBlocksBtn.active = hasData && !scanInProgress
                    && VillagePOIClientCache.getRelationship() == VillageRelationship.GOVERNOR;
        }
        super.render(graphics, mouseX, mouseY, partialTick);

        long cacheTimestamp = VillagePOIClientCache.getUpdateTimestamp();
        if (cacheTimestamp != lastRecordsCacheTimestamp) {
            refreshSortedRecords();
        }
        if (cacheTimestamp != lastJobSitesCacheTimestamp) {
            refreshJobSiteLines();
        }

        // Title: show village name if available
        if (VillagePOIClientCache.hasData()) {
            graphics.drawCenteredString(font, VillagePOIClientCache.getVillageName(), width / 2, PADDING, 0xFFFFFF);
        } else {
            graphics.drawCenteredString(font, title, width / 2, PADDING, 0xFFFFFF);
        }

        if (!VillagePOIClientCache.hasData()) {
            String keyName = resolveOpenPoiKeyName();
            graphics.drawCenteredString(font, "No village data. Press " + keyName + " near a village first.",
                    width / 2, contentTop + 20, 0xFF5555);
            return;
        }

        if (scanInProgress) {
            graphics.drawCenteredString(font,
                    VillagePOIClientCache.hasCompletedScan()
                            ? "Refreshing village data..."
                            : "Loading village data...",
                    width / 2, contentTop - 12, 0xFFFF55);
            if (!VillagePOIClientCache.hasCompletedScan()) {
                graphics.drawCenteredString(font, "Results will appear automatically when loading completes.",
                        width / 2, contentTop + 20, 0xFFFF55);
                return;
            }
        }

        switch (activeTab) {
            case VILLAGERS -> renderVillagersTab(graphics, mouseX, mouseY);
            case VILLAGE_STATS -> renderVillageStatsTab(graphics);
            case JOB_SITES -> renderJobSitesTab(graphics);
            case OWNERSHIP -> renderOwnershipTab(graphics);
        }
    }

    private static String resolveOpenPoiKeyName() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options != null) {
            String keyTranslation = "key." + EmeraldCapitalism.MODID + ".toggle_poi_overlay";
            for (KeyMapping keyMapping : minecraft.options.keyMappings) {
                if (keyTranslation.equals(keyMapping.getName())) {
                    return keyMapping.getTranslatedKeyMessage().getString();
                }
            }
        }

        return EmeraldCapitalismClient.getOpenPoiScreenKeyMapping()
                .getTranslatedKeyMessage().getString();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (renameMode && renameBox != null && renameBox.visible) {
            if (renameBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (renameMode && renameBox != null && renameBox.visible) {
            if (renameBox.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void claimVillageOwnership() {
        if (claimOwnershipBtn == null || !claimOwnershipBtn.active) {
            return;
        }
        UUID villageId = VillagePOIClientCache.getVillageId();
        if (villageId == null) {
            return;
        }
        claimOwnershipBtn.active = false;
        PacketDistributor.sendToServer(new BecomeGovernorCandidatePacket(villageId));
    }

    private void duplicateVillageBlocks() {
        if (duplicateBlocksBtn == null || !duplicateBlocksBtn.active
                || !VillagePOIClientCache.hasData()
                || !requireGovernor("duplicate village blocks")) {
            return;
        }
        UUID villageId = VillagePOIClientCache.getVillageId();
        if (villageId == null) {
            return;
        }
        PacketDistributor.sendToServer(new DuplicateVillageBlocksPacket(villageId));
    }

    private void renderVillagersTab(GuiGraphics graphics, int mouseX, int mouseY) {
        // Column headers (clickable)
        int headerY = contentTop - ROW_HEIGHT;
        renderColumnHeader(graphics, "Name", COL_NAME, headerY, mouseX, mouseY,
                sortMode == VillagePOIPresentation.SortMode.NAME_ASC || sortMode == VillagePOIPresentation.SortMode.NAME_DESC);
        renderColumnHeader(graphics, "Profession", COL_PROFESSION, headerY, mouseX, mouseY,
                sortMode == VillagePOIPresentation.SortMode.PROFESSION_ASC || sortMode == VillagePOIPresentation.SortMode.PROFESSION_DESC);
        graphics.drawString(font, "Health", tableLeft + COL_HEALTH, headerY, 0xFF6666);
        graphics.drawString(font, "Opinion of You", tableLeft + COL_OPINION, headerY, 0x55FF55);
        graphics.drawString(font, "Bed", tableLeft + COL_BED, headerY, 0x5599FF);

        // Separator line
        graphics.fill(tableLeft, contentTop - 2, tableLeft + tableWidth, contentTop - 1, 0xFF555555);

        // Rows
        int visibleCount = Math.min(maxVisibleRows, sortedRecords.size() - scrollOffset);
        for (int i = 0; i < visibleCount; i++) {
            VillagePOIPresentation.VillagerSnapshot record = sortedRecords.get(scrollOffset + i);
            int rowY = contentTop + i * ROW_HEIGHT;

            if (i % 2 == 0) {
                graphics.fill(tableLeft - 2, rowY - 1, tableLeft + tableWidth + 2, rowY + ROW_HEIGHT - 2, 0x20FFFFFF);
            }

            graphics.drawString(font, truncateToWidth(record.name(), COL_PROFESSION - COL_NAME - 6),
                    tableLeft + COL_NAME, rowY, 0xFFFFFF);
            graphics.drawString(font, truncateToWidth(record.profession(), COL_HEALTH - COL_PROFESSION - 6),
                    tableLeft + COL_PROFESSION, rowY, 0xCCCCCC);
            graphics.drawString(font, String.format(Locale.ROOT, "%.1f", record.health()), tableLeft + COL_HEALTH, rowY, 0xFF6666);
            int opinion = record.opinion();
            int opinionColor = styleColor(VillagePOIPresentation.opinionStyle(opinion));
            graphics.drawString(font, String.valueOf(opinion), tableLeft + COL_OPINION, rowY, opinionColor);
            graphics.drawString(font, record.bedPosition(), tableLeft + COL_BED, rowY, 0x5599FF);
        }

        // Scrollbar
        if (sortedRecords.size() > maxVisibleRows) {
            int totalRows = sortedRecords.size();
            int scrollbarHeight = height - contentTop - 20;
            int thumbHeight = Math.max(10, scrollbarHeight * maxVisibleRows / totalRows);
            int thumbY = contentTop + (scrollbarHeight - thumbHeight) * scrollOffset / Math.max(1, totalRows - maxVisibleRows);
            int scrollbarX = tableLeft + tableWidth + 4;

            graphics.fill(scrollbarX, contentTop, scrollbarX + 4, contentTop + scrollbarHeight, 0x40FFFFFF);
            graphics.fill(scrollbarX, thumbY, scrollbarX + 4, thumbY + thumbHeight, 0xAAFFFFFF);
        }

        // Footer
        String footer = sortedRecords.size() + " villager" + (sortedRecords.size() != 1 ? "s" : "");
        graphics.drawString(font, footer, tableLeft, height - 16, 0x888888);
    }

    private void renderVillageStatsTab(GuiGraphics graphics) {
        VillageRelationship relationship = VillagePOIClientCache.getRelationship();
        drawStatLine(graphics, tableLeft, contentTop - ROW_HEIGHT, "Your Relationship",
                relationship.displayName(), relationshipColor(relationship));
        List<VillageStatsPresentation.StatLine> lines = buildVillageStatsLines();
        statsScrollOffset = Math.min(statsScrollOffset, Math.max(0, lines.size() - maxVisibleRows));

        int visibleCount = Math.min(maxVisibleRows, lines.size() - statsScrollOffset);
        for (int i = 0; i < visibleCount; i++) {
            VillageStatsPresentation.StatLine line = lines.get(statsScrollOffset + i);
            int rowY = contentTop + i * ROW_HEIGHT;
            if (line.separator()) {
                graphics.fill(tableLeft, rowY + (ROW_HEIGHT / 2), tableLeft + tableWidth, rowY + (ROW_HEIGHT / 2) + 1, 0xFF555555);
            } else {
                drawStatLine(graphics, tableLeft, rowY, line.label(), line.value(), styleColor(line.style()));
            }
        }

        if (lines.size() > maxVisibleRows) {
            int totalRows = lines.size();
            int scrollbarHeight = height - contentTop - 20;
            int thumbHeight = Math.max(10, scrollbarHeight * maxVisibleRows / totalRows);
            int thumbY = contentTop + (scrollbarHeight - thumbHeight) * statsScrollOffset / Math.max(1, totalRows - maxVisibleRows);
            int scrollbarX = tableLeft + tableWidth + 4;
            graphics.fill(scrollbarX, contentTop, scrollbarX + 4, contentTop + scrollbarHeight, 0x40FFFFFF);
            graphics.fill(scrollbarX, thumbY, scrollbarX + 4, thumbY + thumbHeight, 0xAAFFFFFF);
        }
    }

    private void renderJobSitesTab(GuiGraphics graphics) {
        List<JobSiteEntry> jobSites = VillagePOIClientCache.getJobSites();

        if (jobSites.isEmpty()) {
            graphics.drawCenteredString(font, "No job sites found in village.",
                    width / 2, contentTop + 20, 0xAAAAAA);
            return;
        }

        List<VillagePOIPresentation.JobSiteRow> lines = cachedJobSiteLines;

        // Column headers
        int headerY = contentTop - ROW_HEIGHT;
        graphics.drawString(font, "Job Site", tableLeft, headerY, 0xFFFFFF);
        graphics.drawString(font, "Status", tableLeft + 200, headerY, 0xFFFFFF);
        graphics.drawString(font, "Villager", tableLeft + 300, headerY, 0xFFFFFF);
        graphics.fill(tableLeft, contentTop - 2, tableLeft + tableWidth, contentTop - 1, 0xFF555555);

        // Scrollable rows
        int visibleCount = Math.min(maxVisibleRows, lines.size() - jobSitesScrollOffset);
        for (int i = 0; i < visibleCount; i++) {
            VillagePOIPresentation.JobSiteRow line = lines.get(jobSitesScrollOffset + i);
            int rowY = contentTop + i * ROW_HEIGHT;

            if (line.header()) {
                graphics.drawString(font, line.text(), tableLeft, rowY, styleColor(line.style()));
            } else {
                if (i % 2 == 0) {
                    graphics.fill(tableLeft - 2, rowY - 1, tableLeft + tableWidth + 2, rowY + ROW_HEIGHT - 2, 0x20FFFFFF);
                }
                graphics.drawString(font, line.text(), tableLeft, rowY, styleColor(line.style()));
                int statusColor = line.claimed() ? styleColor(PresentationStyle.POSITIVE) : styleColor(PresentationStyle.WARNING);
                graphics.drawString(font, line.status(), tableLeft + 200, rowY, statusColor);
                graphics.drawString(font, truncate(line.villagerName(), 18), tableLeft + 300, rowY, 0xFFFFFF);
            }
        }

        // Scrollbar
        if (lines.size() > maxVisibleRows) {
            int totalRows = lines.size();
            int scrollbarHeight = height - contentTop - 20;
            int thumbHeight = Math.max(10, scrollbarHeight * maxVisibleRows / totalRows);
            int thumbY = contentTop + (scrollbarHeight - thumbHeight) * jobSitesScrollOffset / Math.max(1, totalRows - maxVisibleRows);
            int scrollbarX = tableLeft + tableWidth + 4;
            graphics.fill(scrollbarX, contentTop, scrollbarX + 4, contentTop + scrollbarHeight, 0x40FFFFFF);
            graphics.fill(scrollbarX, thumbY, scrollbarX + 4, thumbY + thumbHeight, 0xAAFFFFFF);
        }

        // Footer summary
        long totalClaimed = jobSites.stream().filter(JobSiteEntry::claimed).count();
        String footer = jobSites.size() + " job site" + (jobSites.size() != 1 ? "s" : "")
                + " (" + totalClaimed + " claimed, " + (jobSites.size() - totalClaimed) + " unclaimed)";
        graphics.drawString(font, footer, tableLeft, height - 16, 0x888888);
    }

    private List<VillageStatsPresentation.StatLine> buildVillageStatsLines() {
        UUID villageId = VillagePOIClientCache.getVillageId();
        BlockPos bell = VillagePOIClientCache.getBellPosition();
        int assignedBeds = (int) VillagePOIClientCache.getRecords().stream()
                .filter(record -> record.getBedPos() != null).count();
        String shortId = villageId != null ? villageId.toString().substring(0, 8) + "..." : "none";
        return VillageStatsPresentation.lines(new VillageStatsPresentation.Snapshot(
                VillagePOIClientCache.getVillageOpinionOfPlayer(),
                shortId,
                bell != null ? formatPos(bell) : "none",
                VillagePOIClientCache.getRecords().size(),
                VillagePOIClientCache.getTotalBeds(),
                assignedBeds,
                VillagePOIClientCache.getFarmlandCount(),
                VillagePOIClientCache.getDoorCount(),
                VillagePOIClientCache.getRepairQueueCount(),
                VillagePOIClientCache.getIronGolemCapacity(),
                VillagePOIClientCache.getIronGolemsPresent(),
                VillagePOIClientCache.getEmeraldGolemsPresent(),
                VillagePOIClientCache.getEmeraldGolemCapacity(),
                VillagePOIClientCache.getBankName()));
    }

    private static int styleColor(PresentationStyle style) {
        return switch (style) {
            case POSITIVE -> 0x55FF55;
            case NEGATIVE -> 0xFF5555;
            case WARNING -> 0xFFFF55;
            case INFRASTRUCTURE -> 0x2EB84A;
            case NEUTRAL -> 0xAAAAAA;
        };
    }

    private void renderOwnershipTab(GuiGraphics graphics) {
        int ownershipDetailsY = contentTop + TAB_BUTTON_HEIGHT + OWNERSHIP_ROW_GAP;
        int rowOneY = ownershipDetailsY + TAB_BUTTON_HEIGHT + OWNERSHIP_ROW_GAP;
        int rowTwoY = rowOneY + ROW_HEIGHT;
        graphics.drawString(font, "Ownership controls", tableLeft, contentTop - ROW_HEIGHT,
                relationshipColor(VillagePOIClientCache.getRelationship()));
        graphics.drawString(font, "Farmland", tableLeft, rowOneY + 5, 0xFFFFFF);
        graphics.drawString(font, "Doors", tableLeft, rowTwoY + 5, 0xFFFFFF);
        graphics.drawString(font, "Repair settings apply to this village only.", tableLeft,
                rowTwoY + ROW_HEIGHT + 10, 0x888888);
        graphics.drawString(font, "Reset List rebuilds the village's tracked block list.", tableLeft,
                rowTwoY + ROW_HEIGHT + 22, 0x888888);
    }

    private static int relationshipColor(VillageRelationship relationship) {
        return switch (relationship) {
            case GOVERNOR -> 0x55FF55;
            case GOVERNOR_CANDIDATE -> 0xFFFF55;
            case HOSTILE -> 0xFF5555;
            case NEUTRAL -> 0xAAAAAA;
            case FRIENDLY -> 0x55FF55;
        };
    }

    private void drawStatLine(GuiGraphics graphics, int x, int y, String label, String value, int valueColor) {
        graphics.drawString(font, label + ":", x, y, 0xBBBBBB);
        graphics.drawString(font, value, x + 120, y, valueColor);
    }

    private void renderColumnHeader(GuiGraphics graphics, String label, int colOffset, int y,
                                    int mouseX, int mouseY, boolean active) {
        int x = tableLeft + colOffset;
        String sortIndicator = "";
        if (active) {
            sortIndicator = (sortMode == VillagePOIPresentation.SortMode.NAME_ASC || sortMode == VillagePOIPresentation.SortMode.PROFESSION_ASC) ? " ▲" : " ▼";
        }
        String text = label + sortIndicator;
        int textWidth = font.width(text);

        boolean hovered = mouseX >= x && mouseX <= x + textWidth && mouseY >= y && mouseY <= y + font.lineHeight;
        int color = hovered ? 0xFFFF55 : (active ? 0x55FF55 : 0xFFFFFF);
        graphics.drawString(font, text, x, y, color);
    }

    // Input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && activeTab == Tab.VILLAGERS) {
            int headerY = contentTop - ROW_HEIGHT;
            if (mouseY >= headerY && mouseY <= headerY + font.lineHeight) {
                int nameX = tableLeft + COL_NAME;
                if (mouseX >= nameX && mouseX <= nameX + font.width("Name ▲")) {
                    sortMode = (sortMode == VillagePOIPresentation.SortMode.NAME_ASC)
                            ? VillagePOIPresentation.SortMode.NAME_DESC : VillagePOIPresentation.SortMode.NAME_ASC;
                    refreshSortedRecords();
                    return true;
                }
                int profX = tableLeft + COL_PROFESSION;
                if (mouseX >= profX && mouseX <= profX + font.width("Profession ▲")) {
                    sortMode = (sortMode == VillagePOIPresentation.SortMode.PROFESSION_ASC)
                            ? VillagePOIPresentation.SortMode.PROFESSION_DESC : VillagePOIPresentation.SortMode.PROFESSION_ASC;
                    refreshSortedRecords();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (activeTab == Tab.VILLAGERS && sortedRecords.size() > maxVisibleRows) {
            scrollOffset = Math.max(0, Math.min(scrollOffset - (int) scrollY, sortedRecords.size() - maxVisibleRows));
            return true;
        }
        if (activeTab == Tab.JOB_SITES) {
            int totalLines = getJobSiteLineCount();
            if (totalLines > maxVisibleRows) {
                jobSitesScrollOffset = Math.max(0, Math.min(jobSitesScrollOffset - (int) scrollY, totalLines - maxVisibleRows));
                return true;
            }
        }
        if (activeTab == Tab.VILLAGE_STATS) {
            int totalLines = buildVillageStatsLines().size();
            if (totalLines > maxVisibleRows) {
                statsScrollOffset = Math.max(0, Math.min(statsScrollOffset - (int) scrollY, totalLines - maxVisibleRows));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int getJobSiteLineCount() {
        return cachedJobSiteLines.size();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // Formatting helpers

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String formatNullablePos(@Nullable BlockPos pos) {
        return pos != null ? formatPos(pos) : "none";
    }

    private String truncate(String text, int maxChars) {
        if (font.width(text) <= font.width("M") * maxChars) return text;
        while (text.length() > 1 && font.width(text + "...") > font.width("M") * maxChars) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "...";
    }

    private String truncateToWidth(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        while (text.length() > 1 && font.width(text + "...") > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text.length() > 1 ? text + "..." : "...";
    }
}
