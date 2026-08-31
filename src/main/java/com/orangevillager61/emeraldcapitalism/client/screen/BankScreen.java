package com.orangevillager61.emeraldcapitalism.client.screen;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.client.presentation.BankPresentation;
import com.orangevillager61.emeraldcapitalism.market.MarketPricingEngine;
import com.orangevillager61.emeraldcapitalism.market.MarketDemandContext;
import com.orangevillager61.emeraldcapitalism.market.MarketTradeQuote;
import com.orangevillager61.emeraldcapitalism.market.MarketTradeService;
import com.orangevillager61.emeraldcapitalism.market.TradeSide;
import com.orangevillager61.emeraldcapitalism.market.MarketMetric;
import com.orangevillager61.emeraldcapitalism.market.MarketTradeType;
import com.orangevillager61.emeraldcapitalism.menu.BankMenu;
import com.orangevillager61.emeraldcapitalism.menu.BankMenuOpenData;
import com.orangevillager61.emeraldcapitalism.network.MarketDataClientCache;
import com.orangevillager61.emeraldcapitalism.network.MarketTradePacket;
import com.orangevillager61.emeraldcapitalism.network.RenameBankPacket;
import com.orangevillager61.emeraldcapitalism.network.SetBankControlPacket;
import com.orangevillager61.emeraldcapitalism.network.SetBankSettingsPacket;
import com.orangevillager61.emeraldcapitalism.world.bank.BankTargets;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * GUI screen for the Bank block.
 * <p>
 * Main tabs cover:
 * <ul>
 *   <li><b>Overview</b>: chest count, emerald totals, deposit queue, scrollable chest list</li>
 *   <li><b>Accounts</b>: scrollable list of villager accounts (name + balance)</li>
 *   <li><b>Employees</b>: registered bank employees and their professions</li>
 *   <li><b>Control</b>: bank targets, delivery settings, and guard behavior</li>
 * </ul>
 * A rename button at the top-right allows renaming the bank.
 */
public class BankScreen extends AbstractContainerScreen<BankMenu> {

    // Colours

    private static final int BG_COLOR       = 0xCC101010;
    private static final int BORDER_COLOR   = 0xFF555555;
    private static final int TITLE_COLOR    = 0xFFFFFF;
    private static final int LABEL_COLOR    = 0xAAAAAA;
    private static final int VALUE_COLOR    = 0x55FF55;
    private static final int DARK_GREEN     = 0x2EB84A;
    private static final int WARN_COLOR     = 0xFF5555;
    private static final int SEP_COLOR      = 0xFF555555;
    private static final int QUEUE_COLOR    = 0xFFAA00;
    private static final int GOLD_COLOR     = 0xFFD700;

    // Layout

    private static final int PADDING        = 8;
    private static final int ROW_HEIGHT     = 13;
    private static final int TAB_W          = 60;
    private static final int TAB_H          = 18;
    private static final int MARKET_LIST_W  = 184;
    private static final int MARKET_ROW_H   = 34;
    private static final int SUPPLY_BAR_H   = 6;
    private static final int SELECTED_ROW_COLOR = 0xFF1B3666;
    private static final int LABEL_X        = PADDING;
    private static final int VALUE_X        = 130;

    // State

    private enum Tab { OVERVIEW, ACCOUNTS, EMPLOYEES, INVENTORY, CONTROL, MARKET }
    private Tab activeTab = Tab.OVERVIEW;
    private enum ControlTab { TARGETS, DELIVERIES, SECURITY }
    private ControlTab activeControlTab = ControlTab.TARGETS;

    /** Mutable bank name shown in title / rename box, updated optimistically on save. */
    private String displayBankName;

    // Rename controls
    private EditBox renameBox;
    private Button renameBtn;
    private Button controlBtn;
    private boolean renameMode = false;
    private boolean canRename;

    // Overview tab
    private List<String> chestLines = List.of();
    private int overviewScrollOffset = 0;
    private int maxVisibleRows;

    // Accounts tab
    private int accountScrollOffset = 0;
    private List<BankPresentation.AccountRow> accountDisplayItems = List.of();
    private int accountQueuedCount = 0;

    // Employees tab
    private int employeeScrollOffset = 0;

    // Market tab
    private MarketItemList marketList;
    private EditBox marketQuantityBox;
    private Button marketDirectionBtn;
    private Button marketDonateBtn;
    private Button marketConfirmBtn;
    private Button marketMinusBtn;
    private Button marketPlusBtn;
    private int selectedMarketIndex = 0;
    private boolean marketBuy = true;
    private boolean marketDonate;
    private String marketError = "";
    private String marketNotice = "";
    private boolean marketUnavailable;
    private List<BankMenu.MarketEntry> lastMarketEntries = List.of();
    private List<BankMenu.MarketEntry> displayedMarketEntries = List.of();

    // Control tab
    private Button targetTabBtn;
    private Button deliveriesTabBtn;
    private Button securityTabBtn;
    private Button targetModeBtn;
    private Button targetSaveBtn;
    private EditBox emeraldGolemTargetBox;
    private EditBox emeraldSkrimisherTargetBox;
    private EditBox foodDaysBox;
    private TargetSlider emeraldGolemSlider;
    private TargetSlider emeraldSkrimisherSlider;
    private TargetSlider foodDaysSlider;
    private Button villagerDeliveriesBtn;
    private Button randomDeliveriesBtn;
    private Button breadDeliveriesBtn;
    private Button lumberjackDeliveriesBtn;
    private Button attackAllPlayersBtn;

    // Cached content-top relative to panel top-left (computed in init)
    private int contentTopRel;

    // Constructor

    public BankScreen(BankMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth  = 420;
        this.imageHeight = 260;
        this.displayBankName = menu.getBankName().isEmpty() ? "Village Bank" : menu.getBankName();
    }

    @Override
    protected void init() {
        super.init();
        this.inventoryLabelY = -999; // hide vanilla inventory label
        this.titleLabelY     = PADDING;
        this.titleLabelX     = (imageWidth - font.width(displayBankName)) / 2;
        this.canRename = this.minecraft != null
                && this.minecraft.player != null
                && !this.minecraft.player.isSpectator()
                && menu.getControllerId() != null
                && menu.getControllerId().equals(this.minecraft.player.getUUID());

        int tabRowY = topPos + PADDING + font.lineHeight + 4;
        contentTopRel = PADDING + font.lineHeight + 4 + TAB_H + 6 + 23;

        // Tab buttons
        addRenderableWidget(Button.builder(Component.literal("Overview"),
                btn -> switchTab(Tab.OVERVIEW))
                .bounds(leftPos + PADDING, tabRowY, TAB_W, TAB_H)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Accounts"),
                btn -> switchTab(Tab.ACCOUNTS))
                .bounds(leftPos + PADDING + TAB_W + 4, tabRowY, TAB_W, TAB_H)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Employees"),
                btn -> switchTab(Tab.EMPLOYEES))
                .bounds(leftPos + PADDING + (TAB_W + 4) * 2, tabRowY, TAB_W, TAB_H)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Inventory"),
                btn -> switchTab(Tab.INVENTORY))
                .bounds(leftPos + PADDING + (TAB_W + 4) * 3, tabRowY, TAB_W, TAB_H)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Control"),
                btn -> switchTab(Tab.CONTROL))
                .bounds(leftPos + PADDING + (TAB_W + 4) * 4, tabRowY, TAB_W, TAB_H)
                .build());

        int controlY = tabRowY + TAB_H + 3;
        addRenderableWidget(Button.builder(Component.literal("Trade"),
                btn -> switchTab(Tab.MARKET))
                .bounds(leftPos + PADDING, controlY, imageWidth - PADDING * 2, TAB_H)
                .build());

        if (canRename) {
            // Rename button: top-right corner of the panel
            int renameBtnW = 54;
            int renameBtnH = font.lineHeight + 4;
            int renameBtnX = leftPos + imageWidth - PADDING - renameBtnW;
            int renameBtnY = topPos + PADDING;
            renameBtn = addRenderableWidget(Button.builder(Component.literal("Rename"),
                    btn -> onRenameToggle())
                    .bounds(renameBtnX, renameBtnY, renameBtnW, renameBtnH)
                    .build());

            // EditBox for rename (hidden until rename mode is active)
            int boxW = imageWidth - PADDING * 2 - renameBtnW - 6;
            renameBox = addRenderableWidget(new EditBox(font,
                    leftPos + PADDING, renameBtnY,
                    boxW, renameBtnH,
                    Component.literal("Bank name")));
            renameBox.setMaxLength(RenameBankPacket.MAX_NAME_LENGTH);
            renameBox.setVisible(false);
            renameBox.setValue(displayBankName);
        }

        buildChestLines();
        buildAccountDisplayItems();
        buildMarketWidgets();
        buildControlWidgets();
        updateControlButton();

        int contentAbsTop = topPos + contentTopRel;
        int contentBottom = topPos + imageHeight - PADDING;
        maxVisibleRows = Math.max(1, (contentBottom - contentAbsTop) / ROW_HEIGHT);
    }

    private void switchTab(Tab tab) {
        this.activeTab = tab;
        overviewScrollOffset = 0;
        accountScrollOffset = 0;
        employeeScrollOffset = 0;
        boolean marketVisible = tab == Tab.MARKET;
        if (marketList != null) marketList.visible = marketVisible;
        if (marketQuantityBox != null) marketQuantityBox.setVisible(marketVisible);
        if (marketDirectionBtn != null) marketDirectionBtn.visible = marketVisible;
        if (marketDonateBtn != null) marketDonateBtn.visible = marketVisible;
        if (marketConfirmBtn != null) marketConfirmBtn.visible = marketVisible;
        if (marketMinusBtn != null) marketMinusBtn.visible = marketVisible;
        if (marketPlusBtn != null) marketPlusBtn.visible = marketVisible;
        updateControlWidgets();
        updateMarketControls();
    }

    // Rename handling

    private void onRenameToggle() {
        if (!canRename || renameBtn == null || renameBox == null) {
            return;
        }
        if (!renameMode) {
            // Enter rename mode
            renameMode = true;
            renameBox.setValue(displayBankName);
            renameBox.setVisible(true);
            setFocused(renameBox);
            renameBox.moveCursorToEnd(false);
            renameBtn.setMessage(Component.literal("Save"));
        } else {
            // Save and exit rename mode
            String newName = renameBox.getValue().trim();
            if (!newName.isEmpty()) {
                displayBankName = newName;
                // Recenter the title label
                titleLabelX = (imageWidth - font.width(displayBankName)) / 2;
                PacketDistributor.sendToServer(new RenameBankPacket(menu.getBlockPos(), newName));
            }
            renameMode = false;
            renameBox.setVisible(false);
            renameBtn.setMessage(Component.literal("Rename"));
        }
    }

    private void onControlToggle() {
        if (minecraft == null || minecraft.player == null || controlBtn == null
                || !controlBtn.active) {
            return;
        }
        boolean release = menu.getControllerId() != null
                && menu.getControllerId().equals(minecraft.player.getUUID());
        controlBtn.active = false;
        PacketDistributor.sendToServer(new SetBankControlPacket(menu.getBlockPos(), release));
    }

    private void updateControlButton() {
        if (controlBtn == null) {
            return;
        }
        UUID playerId = minecraft == null || minecraft.player == null
                ? null : minecraft.player.getUUID();
        boolean mine = playerId != null && menu.getControllerId() != null
                && playerId.equals(menu.getControllerId());
        if (menu.isBankIndependent()) {
            controlBtn.setMessage(Component.literal("Claim Bank"));
            controlBtn.active = true;
        } else if (mine) {
            controlBtn.setMessage(Component.literal("Release Bank"));
            controlBtn.active = true;
        } else {
            controlBtn.setMessage(Component.literal("Controlled"));
            controlBtn.active = false;
        }
    }

    // Rendering

    @Override
    protected void renderBg(@NotNull GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BG_COLOR);
        g.renderOutline(leftPos, topPos, imageWidth, imageHeight, BORDER_COLOR);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics g, int mouseX, int mouseY) {
        // Title (only draw when not in rename mode)
        if (!renameMode) {
            g.drawCenteredString(font, displayBankName, imageWidth / 2, titleLabelY, TITLE_COLOR);
            if (!canRename) {
                String readOnly = "Read-only";
                g.drawString(font, readOnly, imageWidth - PADDING - font.width(readOnly), titleLabelY, LABEL_COLOR);
            }
        }

        int contentY = contentTopRel;

        if (activeTab == Tab.OVERVIEW) {
            renderOverviewTab(g, contentY);
        } else if (activeTab == Tab.ACCOUNTS) {
            renderAccountsTab(g, contentY);
        } else if (activeTab == Tab.EMPLOYEES) {
            renderEmployeesTab(g, contentY);
        } else if (activeTab == Tab.INVENTORY) {
            renderInventoryTab(g, contentY);
        } else if (activeTab == Tab.CONTROL) {
            renderControlTab(g, contentY);
        } else {
            renderMarketTab(g, contentY);
        }
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        refreshMarketSnapshot();
        updateControlButton();
        updateControlWidgets();
        updateMarketControls();
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }

    // Overview tab

    private void renderOverviewTab(GuiGraphics g, int y) {
        BankMenu m = this.menu;

        // Village linkage
        UUID villageId = m.getVillageId();
        if (villageId == null) {
            drawRow(g, y, "Village:", "None", WARN_COLOR);
        } else {
            drawRow(g, y, "Village:", m.getVillageName(), VALUE_COLOR);
        }
        y += ROW_HEIGHT;

        if (!isBankOwner()) {
            int bankOpinion = m.getBankOpinion();
            drawRow(g, y, "Your bank opinion:", formatOpinion(bankOpinion), opinionColor(bankOpinion));
            y += ROW_HEIGHT;
        }

        drawRow(g, y, "Control:", m.isBankIndependent()
                ? "Independent" : "Player controlled", m.isBankIndependent() ? VALUE_COLOR : GOLD_COLOR);
        y += ROW_HEIGHT;

        int employeeCount = m.getEmployeeCount();
        drawRow(g, y, "Employees:", employeeCount + " / " + BankBlockEntity.MAX_EMPLOYEES,
                employeeCount > 0 ? VALUE_COLOR : LABEL_COLOR);
        y += ROW_HEIGHT;

        int emeraldGolems = m.getEmeraldGolemCount();
        drawRow(g, y, "Emerald golems:", String.valueOf(emeraldGolems),
                emeraldGolems > 0 ? VALUE_COLOR : LABEL_COLOR);
        y += ROW_HEIGHT;

        int emeraldGolemCapacity = m.getExpectedEmeraldGolemCount();
        drawRow(g, y, "Emerald golem capacity:", String.valueOf(emeraldGolemCapacity),
                emeraldGolemCapacity > 0 ? VALUE_COLOR : LABEL_COLOR);
        y += ROW_HEIGHT;

        // Deposit queue
        int queueSize = m.getDepositQueueSize();
        int queueColor = queueSize > 0 ? QUEUE_COLOR : LABEL_COLOR;
        drawRow(g, y, "Deposit queue:", queueSize + " awaiting", queueColor);
        y += ROW_HEIGHT;

        // Separator
        g.fill(LABEL_X, y, VALUE_X + 100, y + 1, SEP_COLOR);
        y += 5;

        // Chest stats
        int radius = BankBlockEntity.SEARCH_RADIUS * 2;
        drawRow(g, y, "Search range:", radius + "\u00d7" + radius + "\u00d7" + radius + " cube", LABEL_COLOR);
        y += ROW_HEIGHT;

        int chestCount = m.getChestCount();
        int chestColor = chestCount > 0 ? DARK_GREEN : LABEL_COLOR;
        drawRow(g, y, "Tracked chests:", String.valueOf(chestCount), chestColor);
        y += ROW_HEIGHT;

        int emeralds = m.getTotalEmeraldCount();
        int emeraldColor = emeralds > 0 ? DARK_GREEN : LABEL_COLOR;
        int blocks = emeralds / 9;
        int loose = emeralds % 9;
        String emeraldStr = emeralds + (blocks > 0 ? "  (" + blocks + " \u25a0 + " + loose + ")" : "");
        drawRow(g, y, "Total emeralds:", emeraldStr, emeraldColor);
        y += ROW_HEIGHT;

        int emeraldOre = m.getTotalEmeraldOreCount();
        int emeraldOreColor = emeraldOre > 0 ? DARK_GREEN : LABEL_COLOR;
        drawRow(g, y, "Total emerald ore:", String.valueOf(emeraldOre), emeraldOreColor);
        y += ROW_HEIGHT;

        int fullInterval   = BankBlockEntity.FULL_SCAN_INTERVAL / 20;
        int verifyInterval = BankBlockEntity.VERIFY_INTERVAL / 20;
        drawRow(g, y, "Rescan every:", fullInterval + " s  (verify " + verifyInterval + " s)", LABEL_COLOR);
        y += ROW_HEIGHT;

        // Chest position list
        if (chestLines.isEmpty()) {
            g.drawString(font, "No chests in range.", LABEL_X, y + 4, LABEL_COLOR);
            return;
        }

        g.fill(LABEL_X, y + 2, VALUE_X + 100, y + 3, SEP_COLOR);
        y += 6;
        g.drawString(font, "Chest positions:", LABEL_X, y, DARK_GREEN);
        y += ROW_HEIGHT;

        int visibleCount = Math.min(maxVisibleRows - /* rows above list */ 0, chestLines.size() - overviewScrollOffset);
        // Clamp to available vertical space
        int panelBottom = imageHeight - PADDING;
        int maxFromSpace = (panelBottom - y) / ROW_HEIGHT;
        visibleCount = Math.min(visibleCount, maxFromSpace);

        for (int i = 0; i < visibleCount; i++) {
            int rowY = y + i * ROW_HEIGHT;
            if (i % 2 == 0) {
                g.fill(LABEL_X - 2, rowY - 1, LABEL_X + imageWidth - PADDING * 2 + 2, rowY + ROW_HEIGHT - 2, 0x20FFFFFF);
            }
            g.drawString(font, chestLines.get(overviewScrollOffset + i), LABEL_X + 4, rowY, 0xCCCCCC);
        }

        // Scrollbar for chest list
        if (chestLines.size() > visibleCount) {
            int barX      = imageWidth - PADDING - 4;
            int barTop    = y;
            int barHeight = visibleCount * ROW_HEIGHT;
            int thumbH    = Math.max(8, barHeight * visibleCount / chestLines.size());
            int thumbY    = barTop + (barHeight - thumbH) * overviewScrollOffset
                    / Math.max(1, chestLines.size() - visibleCount);
            g.fill(barX, barTop, barX + 3, barTop + barHeight, 0x40FFFFFF);
            g.fill(barX, thumbY, barX + 3, thumbY + thumbH, 0xAAFFFFFF);
        }
    }

    // Accounts tab

    private void renderAccountsTab(GuiGraphics g, int y) {
        List<BankMenu.AccountEntry> accounts = menu.getAccounts();

        if (accounts.isEmpty()) {
            g.drawString(font, "No villager accounts registered.", LABEL_X, y + 4, LABEL_COLOR);
            return;
        }

        List<BankPresentation.AccountRow> displayItems = accountDisplayItems;

        // Column headers
        g.drawString(font, "Villager", LABEL_X, y, 0xFFFFFF);
        g.drawString(font, "Balance", VALUE_X, y, 0xFFFFFF);
        y += font.lineHeight + 2;
        g.fill(LABEL_X, y, VALUE_X + 80, y + 1, SEP_COLOR);
        y += 4;

        int panelBottom = imageHeight - PADDING - font.lineHeight - 2; // leave room for footer
        int maxFromSpace = (panelBottom - y) / ROW_HEIGHT;
        int totalItems = displayItems.size();
        int visibleCount = Math.min(maxFromSpace, totalItems - accountScrollOffset);

        int rowIndex = 0; // alternating stripe counter (only counts real entry rows)
        for (int i = 0; i < visibleCount; i++) {
            BankPresentation.AccountRow row = displayItems.get(accountScrollOffset + i);
            int rowY = y + i * ROW_HEIGHT;

            if (row.separator()) {
                // Thin divider between the queue section and the rest
                g.fill(LABEL_X, rowY + ROW_HEIGHT / 2 - 1, LABEL_X + imageWidth - PADDING * 2, rowY + ROW_HEIGHT / 2, SEP_COLOR);
                continue;
            }

            BankPresentation.AccountSnapshot entry = row.account();
            boolean isQueued = entry.queued();

            // Alternating row background
            if (rowIndex % 2 == 0) {
                int stripeColor = isQueued ? 0x28FFAA00 : 0x20FFFFFF;
                g.fill(LABEL_X - 2, rowY - 1, LABEL_X + imageWidth - PADDING * 2 + 2, rowY + ROW_HEIGHT - 2, stripeColor);
            }
            rowIndex++;

            // Queue position prefix for queued entries
            int nameX = LABEL_X + 4;
            if (isQueued) {
                String prefix = entry.queuePosition() == 0 ? "\u2192 " : "#" + (entry.queuePosition() + 1) + " ";
                g.drawString(font, prefix, nameX, rowY, QUEUE_COLOR);
                nameX += font.width(prefix);
            }

            // Villager name (truncated to fit)
            int nameColor = isQueued ? QUEUE_COLOR : LABEL_COLOR;
            int maxNameW = VALUE_X - nameX - 2;
            String name = entry.name();
            while (name.length() > 1 && font.width(name) > maxNameW) {
                name = name.substring(0, name.length() - 1);
            }
            g.drawString(font, name, nameX, rowY, nameColor);

            // Balance
            int balance = entry.balance();
            int balColor = balance > 0 ? VALUE_COLOR : (balance < 0 ? WARN_COLOR : LABEL_COLOR);
            g.drawString(font, String.valueOf(balance), VALUE_X, rowY, balColor);
        }

        // Scrollbar
        if (totalItems > visibleCount) {
            int barX      = imageWidth - PADDING - 4;
            int barTop    = y;
            int barHeight = visibleCount * ROW_HEIGHT;
            int thumbH    = Math.max(8, barHeight * visibleCount / totalItems);
            int thumbY    = barTop + (barHeight - thumbH) * accountScrollOffset
                    / Math.max(1, totalItems - visibleCount);
            g.fill(barX, barTop, barX + 3, barTop + barHeight, 0x40FFFFFF);
            g.fill(barX, thumbY, barX + 3, thumbY + thumbH, 0xAAFFFFFF);
        }

        // Footer: summary
        int footerY = imageHeight - PADDING - font.lineHeight;
        String footer = accounts.size() + " account" + (accounts.size() != 1 ? "s" : "");
        if (accountQueuedCount > 0) {
            footer += "  \u2022  " + accountQueuedCount + " in queue";
        }
        g.drawString(font, footer, LABEL_X, footerY, 0x888888);
    }

    // Employees tab

    private void renderEmployeesTab(GuiGraphics g, int y) {
        List<BankMenu.EmployeeEntry> employees = menu.getEmployees();
        if (employees.isEmpty()) {
            g.drawString(font, "No bank employees registered.", LABEL_X, y + 4, LABEL_COLOR);
            return;
        }

        g.drawString(font, "Name", LABEL_X, y, TITLE_COLOR);
        g.drawString(font, "Entity type", 145, y, TITLE_COLOR);
        g.drawString(font, "Profession", 285, y, TITLE_COLOR);
        y += font.lineHeight + 3;
        g.fill(LABEL_X, y, imageWidth - PADDING, y + 1, SEP_COLOR);
        y += 4;

        int panelBottom = imageHeight - PADDING - font.lineHeight - 2;
        int visibleCount = Math.min((panelBottom - y) / ROW_HEIGHT,
                employees.size() - employeeScrollOffset);
        for (int i = 0; i < visibleCount; i++) {
            BankMenu.EmployeeEntry employee = employees.get(employeeScrollOffset + i);
            int rowY = y + i * ROW_HEIGHT;
            if (i % 2 == 0) {
                g.fill(LABEL_X - 2, rowY - 1, imageWidth - PADDING, rowY + ROW_HEIGHT - 2,
                        0x20FFFFFF);
            }
            g.drawString(font, fitMarketText(employee.name(), 132), LABEL_X + 2, rowY, LABEL_COLOR);
            g.drawString(font, fitMarketText(employee.entityType(), 132), 145, rowY, LABEL_COLOR);
            g.drawString(font, fitMarketText(employee.profession(), imageWidth - PADDING - 285),
                    285, rowY, LABEL_COLOR);
        }

        if (employees.size() > visibleCount) {
            int barX = imageWidth - PADDING - 4;
            int barTop = y;
            int barHeight = visibleCount * ROW_HEIGHT;
            int thumbH = Math.max(8, barHeight * visibleCount / employees.size());
            int thumbY = barTop + (barHeight - thumbH) * employeeScrollOffset
                    / Math.max(1, employees.size() - visibleCount);
            g.fill(barX, barTop, barX + 3, barTop + barHeight, 0x40FFFFFF);
            g.fill(barX, thumbY, barX + 3, thumbY + thumbH, 0xAAFFFFFF);
        }

        String footer = employees.size() + " employee" + (employees.size() == 1 ? "" : "s");
        g.drawString(font, footer, LABEL_X, imageHeight - PADDING - font.lineHeight, 0x888888);
    }

    // Control tab

    private void renderControlTab(GuiGraphics g, int y) {
        if (activeControlTab == ControlTab.TARGETS) {
            g.drawString(font, "Manual target values can be typed into the fields.",
                    LABEL_X, y + 133, LABEL_COLOR);
            g.drawString(font, "Entity targets: 0-" + BankBlockEntity.MAX_MANUAL_ENTITY_TARGET
                    + "; food days: 0-" + BankTargets.MAX_FOOD_DAYS + ".",
                    LABEL_X, y + 146, LABEL_COLOR);
        } else {
            if (activeControlTab == ControlTab.DELIVERIES) {
                g.drawString(font, "Random deliveries cover ordinary market items.",
                        LABEL_X, y + 92, LABEL_COLOR);
                g.drawString(font, "Tree cutting follows lumberjack deliveries.",
                        LABEL_X, y + 105, LABEL_COLOR);
            } else {
                g.drawString(font, "Bank golems attack every player except the controller",
                        LABEL_X, y + 58, LABEL_COLOR);
                g.drawString(font, "when this setting is enabled.", LABEL_X, y + 71, LABEL_COLOR);
            }
        }
        if (!canEditControl()) {
            g.drawString(font, "Only the bank controller can change these settings.",
                    LABEL_X, imageHeight - PADDING - font.lineHeight, WARN_COLOR);
        }
    }

    // Scrolling

    private void renderInventoryTab(GuiGraphics g, int y) {
        BankMenu m = this.menu;
        g.drawString(font, "Bank inventory", LABEL_X, y, LABEL_COLOR);
        y += ROW_HEIGHT + 4;

        int emeralds = m.getTotalEmeraldCount();
        int emeraldColor = emeralds > 0 ? DARK_GREEN : LABEL_COLOR;
        int blocks = emeralds / 9;
        int loose = emeralds % 9;
        String emeraldStr = emeralds + (blocks > 0 ? "  (" + blocks + " \u25a0 + " + loose + ")" : "");
        drawRow(g, y, "Emeralds:", emeraldStr, emeraldColor);
        y += ROW_HEIGHT;

        int emeraldOre = m.getTotalEmeraldOreCount();
        drawRow(g, y, "Emerald Ore:", String.valueOf(emeraldOre), emeraldOre > 0 ? DARK_GREEN : LABEL_COLOR);
        y += ROW_HEIGHT;

        int pumpkins = m.getTotalPumpkinCount();
        int pumpkinTarget = m.getPumpkinTarget();
        drawRow(g, y, "Pumpkins:", pumpkins + " / " + pumpkinTarget,
                pumpkins >= pumpkinTarget ? DARK_GREEN : LABEL_COLOR);
        y += ROW_HEIGHT;

        int wheat = m.getTotalWheatCount();
        drawRow(g, y, "Wheat:", String.valueOf(wheat), wheat > 0 ? DARK_GREEN : LABEL_COLOR);
        y += ROW_HEIGHT;

        int bread = m.getTotalBreadCount();
        int breadTarget = m.getBreadTarget();
        drawRow(g, y, "Bread:", bread + " / " + breadTarget,
                bread >= breadTarget ? DARK_GREEN : LABEL_COLOR);
        y += ROW_HEIGHT;

        int planks = m.getTotalPlankCount();
        int plankTarget = m.getPlankTarget();
        drawRow(g, y, "Planks:", planks + " / " + plankTarget,
                planks >= plankTarget ? DARK_GREEN : LABEL_COLOR);
        y += ROW_HEIGHT;

        int coal = m.getTotalCoalCount();
        int coalTarget = m.getCoalTarget();
        drawRow(g, y, "Coal / Charcoal:", coal + " / " + coalTarget,
                coal >= coalTarget ? DARK_GREEN : LABEL_COLOR);
        y += ROW_HEIGHT;

        int dye = m.getTotalEmeraldGreenDyeCount();
        drawRow(g, y, "Emerald Green Dye:", String.valueOf(dye), dye > 0 ? DARK_GREEN : LABEL_COLOR);
    }

    // Market tab

    private void renderMarketTab(GuiGraphics g, int y) {
        int panelX = 214;
        g.drawString(font, "Market", panelX, y, TITLE_COLOR);
        BankMenu.MarketEntry entry = selectedMarketEntry();
        if (entry == null) {
            drawMarketText(g, panelX, y + ROW_HEIGHT + 4,
                    "No market items are configured.", LABEL_COLOR);
            return;
        }

        boolean fixedTrade = entry.config().tradeType() == MarketTradeType.FIXED;
        MarketTradeQuote quote = currentMarketQuote(entry);
        y += ROW_HEIGHT + 4;
        if (entry.config().tradeType() == MarketTradeType.FIXED) {
            drawRowAt(g, panelX, y, "Trade type:", "Fixed Trade", VALUE_COLOR);
            y += ROW_HEIGHT;
        }
        drawRowAt(g, panelX, y, "Stock:", String.valueOf(entry.stock()), VALUE_COLOR);
        y += ROW_HEIGHT;
        if (!fixedTrade) {
            drawRowAt(g, panelX, y, "Current Price:",
                    formatRate(entry.config().baseRate(), entry.stock(), entry.population()), VALUE_COLOR);
            y += ROW_HEIGHT;
        }
        if (quote.valid()) {
            if (!fixedTrade) {
                drawRowAt(g, panelX, y, "Projected:", format(quote.projectedMidRate()), GOLD_COLOR);
                y += ROW_HEIGHT;
            }
            String valueLabel = marketDonate ? "Opinion gain:"
                    : (quote.side() == TradeSide.BUY ? "Cost:" : "Payout:");
            int valueColor = marketDonate ? GOLD_COLOR
                    : (quote.side() == TradeSide.BUY ? GOLD_COLOR : VALUE_COLOR);
            drawRowAt(g, panelX, y, valueLabel,
                    quote.emeraldAmount() + (marketDonate ? " opinion" : " emeralds"), valueColor);
            y += ROW_HEIGHT;
            if (!fixedTrade) {
                drawRowAt(g, panelX, y, "Effective:", format(quote.effectiveRate()) + " items/emerald", LABEL_COLOR);
                y += ROW_HEIGHT;
            }
        } else {
            drawMarketText(g, panelX, y, marketUnavailable ? "Unavailable" : marketError,
                    marketUnavailable ? LABEL_COLOR : WARN_COLOR);
            y += ROW_HEIGHT;
        }
        y += 5;
        String positionLabel = entry.config().metric() == MarketMetric.TARGET_RATIO
                ? "Target position" : "Supply position";
        drawMarketText(g, panelX, y, positionLabel, LABEL_COLOR);
        drawSupplyBar(g, panelX, y + ROW_HEIGHT, imageWidth - PADDING - panelX,
                entry, quote.valid() ? quote.projectedStock() : entry.stock(),
                quote.valid() && quote.projectedStock() != entry.stock());
        int messageY = y + ROW_HEIGHT + 22;
        if (!marketNotice.isEmpty()) {
            drawMarketText(g, panelX, messageY, marketNotice, LABEL_COLOR);
            messageY += ROW_HEIGHT;
        }
        if (!marketError.isEmpty() && !marketUnavailable) {
            drawMarketText(g, panelX, messageY, marketError, WARN_COLOR);
        }
    }

    private void drawRowAt(GuiGraphics g, int x, int y, String label, String value, int color) {
        int valueX = x + 88;
        g.drawString(font, fitMarketText(label, valueX - x - 4), x, y, LABEL_COLOR);
        drawMarketText(g, valueX, y, value, color);
    }

    private void drawMarketText(GuiGraphics g, int x, int y, String text, int color) {
        g.drawString(font, fitMarketText(text, imageWidth - PADDING - x), x, y, color);
    }

    private void drawSupplyBar(GuiGraphics g, int x, int y, int width, BankMenu.MarketEntry entry,
                               double projectedStock, boolean showProjected) {
        int trackWidth = Math.max(20, width);
        var context = marketDemandContext(entry);
        double bandLow = MarketPricingEngine.greenBandLow(entry.config(), context);
        double bandHigh = MarketPricingEngine.greenBandHigh(entry.config(), context);
        double maxPosition = entry.config().metric() == MarketMetric.TARGET_RATIO
                ? 2.0
                : Math.max(bandHigh * 2.0, bandHigh + 10.0);
        int bandStart = x + (int) (trackWidth * bandLow / maxPosition);
        int bandEnd = x + (int) (trackWidth * bandHigh / maxPosition);
        g.fill(x, y, x + trackWidth, y + SUPPLY_BAR_H, 0xFF333333);
        g.fill(bandStart, y, bandEnd, y + SUPPLY_BAR_H, 0xFF2E7D32);
        drawSupplyMarker(g, x, y, trackWidth, maxPosition, entry, entry.stock(), 0xFFFFFFFF);
        if (showProjected) {
            drawSupplyMarker(g, x, y, trackWidth, maxPosition, entry, projectedStock, 0xFFFFAA00);
        }
    }

    private void drawSupplyMarker(GuiGraphics g, int x, int y, int width, double maxPosition,
                                  BankMenu.MarketEntry entry, double stock, int color) {
        double position = MarketPricingEngine.normalizedSupply(entry.config(), stock, marketDemandContext(entry));
        int markerX = x + (int) (width * Math.min(1.0, position / maxPosition));
        g.fill(markerX, y - 2, markerX + 2, y + SUPPLY_BAR_H + 2, color);
    }

    private void buildMarketWidgets() {
        int contentTop = topPos + contentTopRel;
        int contentBottom = topPos + imageHeight - PADDING;
        marketList = addRenderableWidget(new MarketItemList(
                minecraft, MARKET_LIST_W, contentTop, contentBottom, MARKET_ROW_H));
        marketList.visible = false;
        refreshMarketList();

        int x = leftPos + 214;
        int controlsY = contentBottom - 24;
        marketMinusBtn = addRenderableWidget(Button.builder(Component.literal("-"), btn -> changeQuantity(-1))
                .bounds(x, controlsY, 20, 20).build());
        marketPlusBtn = addRenderableWidget(Button.builder(Component.literal("+"), btn -> changeQuantity(1))
                .bounds(x + 158, controlsY, 20, 20).build());
        marketQuantityBox = addRenderableWidget(new EditBox(font, x + 24, controlsY, 130, 20,
                Component.literal("Quantity")));
        BankMenu.MarketEntry initialEntry = selectedMarketEntry();
        marketQuantityBox.setValue(initialEntry == null ? "1" : String.valueOf(
                MarketPricingEngine.tradeBatchSize(initialEntry.config(), initialEntry.stock(),
                        marketDemandContext(initialEntry), TradeSide.BUY)));
        marketQuantityBox.setMaxLength(6);
        marketDirectionBtn = addRenderableWidget(Button.builder(Component.literal("Buy"), btn -> {
            marketBuy = !marketBuy;
            marketDonate = false;
            resetMarketQuantityToBatch();
            updateMarketControls();
            refreshMarketList();
        }).bounds(x, controlsY - 24, 58, 20).build());
        marketDonateBtn = addRenderableWidget(Button.builder(Component.literal("Donate"), btn -> {
            marketDonate = !marketDonate;
            if (marketDonate) {
                marketBuy = false;
            }
            resetMarketQuantityToBatch();
            updateMarketControls();
            refreshMarketList();
        }).bounds(x + 60, controlsY - 24, 58, 20).build());
        marketConfirmBtn = addRenderableWidget(Button.builder(Component.literal("Confirm"), btn -> confirmMarketTrade())
                .bounds(x + 120, controlsY - 24, 58, 20).build());
        marketMinusBtn.visible = false;
        marketPlusBtn.visible = false;
        marketQuantityBox.setVisible(false);
        marketDirectionBtn.visible = false;
        marketDonateBtn.visible = false;
        marketConfirmBtn.visible = false;
        lastMarketEntries = menu.getMarketEntries();
    }

    private void buildControlWidgets() {
        int contentTop = topPos + contentTopRel;
        int right = leftPos + imageWidth - PADDING;
        int controlActionWidth = 100;
        int targetControlsY = contentTop + 25;
        int sliderX = leftPos + PADDING;
        int inputX = sliderX + 270;

        targetTabBtn = addRenderableWidget(Button.builder(Component.literal("Targets"),
                btn -> switchControlTab(ControlTab.TARGETS))
                .bounds(sliderX, contentTop, 68, TAB_H).build());
        deliveriesTabBtn = addRenderableWidget(Button.builder(Component.literal("Deliveries"),
                btn -> switchControlTab(ControlTab.DELIVERIES))
                .bounds(sliderX + 72, contentTop, 82, TAB_H).build());
        securityTabBtn = addRenderableWidget(Button.builder(Component.literal("Security"),
                btn -> switchControlTab(ControlTab.SECURITY))
                .bounds(sliderX + 158, contentTop, 82, TAB_H).build());
        controlBtn = addRenderableWidget(Button.builder(Component.literal("Claim Bank"),
                btn -> onControlToggle())
                .bounds(right - controlActionWidth, contentTop, controlActionWidth, TAB_H).build());

        targetModeBtn = addRenderableWidget(Button.builder(Component.literal("Automatic targets"),
                btn -> onTargetModeToggle())
                .bounds(sliderX, targetControlsY, 150, 20).build());
        targetSaveBtn = addRenderableWidget(Button.builder(Component.literal("Save"),
                btn -> onTargetSave())
                .bounds(right - 60, targetControlsY, 60, 20).build());

        emeraldGolemTargetBox = addTargetBox(inputX, targetControlsY + 26, "Golem target");
        emeraldSkrimisherTargetBox = addTargetBox(inputX, targetControlsY + 54, "Skrimisher target");
        foodDaysBox = addTargetBox(inputX, targetControlsY + 82, "Food days");
        emeraldGolemSlider = addRenderableWidget(new TargetSlider(
                sliderX, targetControlsY + 26, 258, 20, "Emerald golems",
                0, BankBlockEntity.MAX_MANUAL_ENTITY_TARGET, menu.getExpectedEmeraldGolemCount(),
                emeraldGolemTargetBox));
        emeraldSkrimisherSlider = addRenderableWidget(new TargetSlider(
                sliderX, targetControlsY + 54, 258, 20, "Emerald skrimishers",
                0, BankBlockEntity.MAX_MANUAL_ENTITY_TARGET, menu.getEmeraldGolemCount() * 2,
                emeraldSkrimisherTargetBox));
        foodDaysSlider = addRenderableWidget(new TargetSlider(
                sliderX, targetControlsY + 82, 258, 20, "Food days",
                0, BankTargets.MAX_FOOD_DAYS, menu.getControlSettings().foodDays(), foodDaysBox));

        int deliveryY = contentTop + 30;
        villagerDeliveriesBtn = addDeliveryButton(sliderX, deliveryY, "Villager deliveries",
                DeliverySetting.VILLAGER);
        randomDeliveriesBtn = addDeliveryButton(sliderX + 194, deliveryY, "Random deliveries",
                DeliverySetting.RANDOM);
        breadDeliveriesBtn = addDeliveryButton(sliderX, deliveryY + 28, "Bread deliveries",
                DeliverySetting.BREAD);
        lumberjackDeliveriesBtn = addDeliveryButton(sliderX + 194, deliveryY + 28,
                "Lumberjack deliveries", DeliverySetting.LUMBERJACK);
        attackAllPlayersBtn = addRenderableWidget(Button.builder(Component.literal("Attack Other Players"),
                btn -> toggleAttackAllPlayers())
                .bounds(sliderX, deliveryY, 258, 20).build());

        syncTargetControls();
        updateControlWidgets();
    }

    private EditBox addTargetBox(int x, int y, String hint) {
        EditBox box = addRenderableWidget(new EditBox(font, x, y, 55, 20, Component.literal(hint)));
        box.setMaxLength(4);
        return box;
    }

    private Button addDeliveryButton(int x, int y, String label, DeliverySetting setting) {
        return addRenderableWidget(Button.builder(Component.literal(label),
                btn -> toggleDelivery(setting))
                .bounds(x, y, 186, 20).build());
    }

    private void switchControlTab(ControlTab tab) {
        activeControlTab = tab;
        updateControlWidgets();
    }

    private void updateControlWidgets() {
        if (targetTabBtn == null) {
            return;
        }
        boolean controlVisible = activeTab == Tab.CONTROL;
        boolean targetsVisible = controlVisible && activeControlTab == ControlTab.TARGETS;
        boolean deliveriesVisible = controlVisible && activeControlTab == ControlTab.DELIVERIES;
        boolean securityVisible = controlVisible && activeControlTab == ControlTab.SECURITY;
        boolean editable = canEditControl();
        BankMenuOpenData.ControlSettings settings = menu.getControlSettings();

        targetTabBtn.visible = controlVisible;
        deliveriesTabBtn.visible = controlVisible;
        securityTabBtn.visible = controlVisible;
        controlBtn.visible = controlVisible;
        targetModeBtn.visible = targetsVisible;
        targetSaveBtn.visible = targetsVisible;
        emeraldGolemTargetBox.setVisible(targetsVisible);
        emeraldSkrimisherTargetBox.setVisible(targetsVisible);
        foodDaysBox.setVisible(targetsVisible);
        emeraldGolemSlider.visible = targetsVisible;
        emeraldSkrimisherSlider.visible = targetsVisible;
        foodDaysSlider.visible = targetsVisible;
        villagerDeliveriesBtn.visible = deliveriesVisible;
        randomDeliveriesBtn.visible = deliveriesVisible;
        breadDeliveriesBtn.visible = deliveriesVisible;
        lumberjackDeliveriesBtn.visible = deliveriesVisible;
        attackAllPlayersBtn.visible = securityVisible;

        targetModeBtn.setMessage(Component.literal(settings.manualTargets()
                ? "Manual targets" : "Automatic targets"));
        targetModeBtn.active = editable && targetsVisible;
        targetSaveBtn.active = editable && targetsVisible && settings.manualTargets();
        emeraldGolemTargetBox.active = editable && targetsVisible && settings.manualTargets();
        emeraldSkrimisherTargetBox.active = editable && targetsVisible && settings.manualTargets();
        foodDaysBox.active = editable && targetsVisible && settings.manualTargets();
        emeraldGolemSlider.active = editable && targetsVisible && settings.manualTargets();
        emeraldSkrimisherSlider.active = editable && targetsVisible && settings.manualTargets();
        foodDaysSlider.active = editable && targetsVisible && settings.manualTargets();

        villagerDeliveriesBtn.setMessage(deliveryMessage("Villager deliveries",
                settings.villagerDeliveriesEnabled()));
        randomDeliveriesBtn.setMessage(deliveryMessage("Random deliveries",
                settings.randomDeliveriesEnabled()));
        breadDeliveriesBtn.setMessage(deliveryMessage("Bread deliveries",
                settings.breadDeliveriesEnabled()));
        lumberjackDeliveriesBtn.setMessage(deliveryMessage("Lumberjack deliveries",
                settings.lumberjackDeliveriesEnabled()));
        attackAllPlayersBtn.setMessage(deliveryMessage("Attack Other Players", settings.attackAllPlayers()));
        villagerDeliveriesBtn.active = editable && deliveriesVisible;
        randomDeliveriesBtn.active = editable && deliveriesVisible;
        breadDeliveriesBtn.active = editable && deliveriesVisible;
        lumberjackDeliveriesBtn.active = editable && deliveriesVisible;
        attackAllPlayersBtn.active = editable && securityVisible;
    }

    private Component deliveryMessage(String label, boolean enabled) {
        return Component.literal(label + ": " + (enabled ? "ON" : "OFF"));
    }

    private boolean canEditControl() {
        return minecraft != null && minecraft.player != null && !minecraft.player.isSpectator()
                && menu.getControllerId() != null
                && menu.getControllerId().equals(minecraft.player.getUUID());
    }

    private boolean isBankOwner() {
        return minecraft != null && minecraft.player != null
                && menu.getControllerId() != null
                && menu.getControllerId().equals(minecraft.player.getUUID());
    }

    private void onTargetModeToggle() {
        if (!canEditControl()) {
            return;
        }
        BankMenuOpenData.ControlSettings current = menu.getControlSettings();
        boolean manual = !current.manualTargets();
        int golemTarget = current.emeraldGolemTarget();
        int skrimisherTarget = current.emeraldSkrimisherTarget();
        if (manual && !current.manualTargets()) {
            golemTarget = menu.getExpectedEmeraldGolemCount();
            skrimisherTarget = Math.min(BankBlockEntity.MAX_MANUAL_ENTITY_TARGET,
                    menu.getEmeraldGolemCount() * 2);
        }
        submitControlSettings(new BankMenuOpenData.ControlSettings(manual, golemTarget,
                skrimisherTarget, current.foodDays(), current.villagerDeliveriesEnabled(),
                current.randomDeliveriesEnabled(), current.breadDeliveriesEnabled(),
                current.lumberjackDeliveriesEnabled(), current.attackAllPlayers()));
    }

    private void onTargetSave() {
        if (!canEditControl() || !menu.getControlSettings().manualTargets()) {
            return;
        }
        BankMenuOpenData.ControlSettings current = menu.getControlSettings();
        int golemTarget = parseTarget(emeraldGolemTargetBox, current.emeraldGolemTarget(),
                BankBlockEntity.MAX_MANUAL_ENTITY_TARGET);
        int skrimisherTarget = parseTarget(emeraldSkrimisherTargetBox,
                current.emeraldSkrimisherTarget(), BankBlockEntity.MAX_MANUAL_ENTITY_TARGET);
        int foodDays = parseTarget(foodDaysBox, current.foodDays(), BankTargets.MAX_FOOD_DAYS);
        submitControlSettings(new BankMenuOpenData.ControlSettings(true, golemTarget,
                skrimisherTarget, foodDays, current.villagerDeliveriesEnabled(),
                current.randomDeliveriesEnabled(), current.breadDeliveriesEnabled(),
                current.lumberjackDeliveriesEnabled(), current.attackAllPlayers()));
    }

    private int parseTarget(EditBox box, int fallback, int max) {
        try {
            int value = Integer.parseInt(box.getValue().trim());
            int clamped = Math.max(0, Math.min(max, value));
            box.setValue(String.valueOf(clamped));
            return clamped;
        } catch (NumberFormatException ignored) {
            box.setValue(String.valueOf(fallback));
            return fallback;
        }
    }

    private void toggleDelivery(DeliverySetting setting) {
        if (!canEditControl()) {
            return;
        }
        BankMenuOpenData.ControlSettings current = menu.getControlSettings();
        boolean value = switch (setting) {
            case VILLAGER -> !current.villagerDeliveriesEnabled();
            case RANDOM -> !current.randomDeliveriesEnabled();
            case BREAD -> !current.breadDeliveriesEnabled();
            case LUMBERJACK -> !current.lumberjackDeliveriesEnabled();
        };
        submitControlSettings(new BankMenuOpenData.ControlSettings(current.manualTargets(),
                current.emeraldGolemTarget(), current.emeraldSkrimisherTarget(), current.foodDays(),
                setting == DeliverySetting.VILLAGER ? value : current.villagerDeliveriesEnabled(),
                setting == DeliverySetting.RANDOM ? value : current.randomDeliveriesEnabled(),
                setting == DeliverySetting.BREAD ? value : current.breadDeliveriesEnabled(),
                setting == DeliverySetting.LUMBERJACK ? value : current.lumberjackDeliveriesEnabled(),
                current.attackAllPlayers()));
    }

    private void toggleAttackAllPlayers() {
        if (!canEditControl()) {
            return;
        }
        BankMenuOpenData.ControlSettings current = menu.getControlSettings();
        submitControlSettings(new BankMenuOpenData.ControlSettings(current.manualTargets(),
                current.emeraldGolemTarget(), current.emeraldSkrimisherTarget(), current.foodDays(),
                current.villagerDeliveriesEnabled(), current.randomDeliveriesEnabled(),
                current.breadDeliveriesEnabled(), current.lumberjackDeliveriesEnabled(),
                !current.attackAllPlayers()));
    }

    private void submitControlSettings(BankMenuOpenData.ControlSettings settings) {
        if (!canEditControl()) {
            return;
        }
        menu.applyControlSettings(settings);
        syncTargetControls();
        PacketDistributor.sendToServer(new SetBankSettingsPacket(menu.getBlockPos(), settings));
        updateControlWidgets();
    }

    private void syncTargetControls() {
        if (emeraldGolemSlider == null) {
            return;
        }
        BankMenuOpenData.ControlSettings settings = menu.getControlSettings();
        int golemTarget = settings.manualTargets()
                ? settings.emeraldGolemTarget() : menu.getExpectedEmeraldGolemCount();
        int skrimisherTarget = settings.manualTargets()
                ? settings.emeraldSkrimisherTarget()
                : Math.min(BankBlockEntity.MAX_MANUAL_ENTITY_TARGET, menu.getEmeraldGolemCount() * 2);
        emeraldGolemSlider.setIntegerValue(golemTarget);
        emeraldSkrimisherSlider.setIntegerValue(skrimisherTarget);
        foodDaysSlider.setIntegerValue(settings.foodDays());
    }

    private enum DeliverySetting { VILLAGER, RANDOM, BREAD, LUMBERJACK }

    private final class TargetSlider extends AbstractSliderButton {
        private final String label;
        private final int min;
        private final int max;
        private final EditBox input;

        private TargetSlider(int x, int y, int width, int height, String label,
                             int min, int max, int initial, EditBox input) {
            super(x, y, width, height, Component.literal(label),
                    (double) (Math.max(min, Math.min(max, initial)) - min)
                            / Math.max(1, max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.input = input;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label + ": " + integerValue()));
        }

        @Override
        protected void applyValue() {
            input.setValue(String.valueOf(integerValue()));
        }

        private int integerValue() {
            return min + (int) Math.round(value * (max - min));
        }

        private void setIntegerValue(int target) {
            int clamped = Math.max(min, Math.min(max, target));
            value = (double) (clamped - min) / Math.max(1, max - min);
            updateMessage();
            input.setValue(String.valueOf(clamped));
        }
    }

    private void refreshMarketSnapshot() {
        MarketDataClientCache.get(menu.getBlockPos()).ifPresent(entries -> {
            if (!entries.equals(menu.getMarketEntries())) {
                menu.applyMarketEntries(entries);
                refreshMarketList();
            }
        });
        if (!lastMarketEntries.equals(menu.getMarketEntries())) {
            refreshMarketList();
        }
    }

    private void refreshMarketList() {
        if (marketList == null) return;
        String selectedId = selectedMarketEntry() == null ? null : selectedMarketEntry().id();
        displayedMarketEntries = BankPresentation.sortMarketEntriesByPriority(
                menu.getMarketEntries(), this::marketSortPriority,
                Comparator.comparing(BankMenu.MarketEntry::id));
        if (selectedId != null) {
            int selectedPosition = indexOfMarketEntry(selectedId);
            if (selectedPosition >= 0) {
                selectedMarketIndex = selectedPosition;
            }
        }
        List<MarketItemList.Entry> entries = displayedMarketEntries.stream()
                .map(entry -> marketList.new Entry(entry)).toList();
        if (selectedMarketIndex >= entries.size()) selectedMarketIndex = 0;
        marketList.setEntries(entries);
        marketList.setSelected(entries.isEmpty() ? null : entries.get(selectedMarketIndex));
        lastMarketEntries = menu.getMarketEntries();
    }

    private BankMenu.MarketEntry selectedMarketEntry() {
        List<BankMenu.MarketEntry> entries = displayedMarketEntries;
        return entries.isEmpty() ? null : entries.get(Math.min(selectedMarketIndex, entries.size() - 1));
    }

    private int indexOfMarketEntry(String id) {
        for (int i = 0; i < displayedMarketEntries.size(); i++) {
            if (displayedMarketEntries.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private ItemStack marketStack(BankMenu.MarketEntry entry) {
        ResourceLocation id = entry.itemId().indexOf(':') >= 0
                ? ResourceLocation.parse(entry.itemId())
                : ResourceLocation.withDefaultNamespace(entry.itemId());
        return new ItemStack(BuiltInRegistries.ITEM.get(id));
    }

    private MarketTradeQuote currentMarketQuote(BankMenu.MarketEntry entry) {
        int quantity;
        try {
            quantity = Integer.parseInt(marketQuantityBox == null ? "1" : marketQuantityBox.getValue());
        } catch (NumberFormatException ignored) {
            return MarketTradeQuote.invalid("invalid_quantity", 0, marketSide(entry),
                    MarketPricingEngine.midRate(entry.config(), entry.stock(), marketDemandContext(entry)), entry.stock());
        }
        TradeSide side = marketSide(entry);
        return MarketPricingEngine.quote(entry.config(), entry.stock(), marketDemandContext(entry), quantity, side);
    }

    private TradeSide marketSide(BankMenu.MarketEntry entry) {
        return marketDonate || !marketBuy ? TradeSide.SELL : TradeSide.BUY;
    }

    private boolean canDonate(BankMenu.MarketEntry entry) {
        return entry.config().tradeType() != MarketTradeType.FIXED
                || entry.config().supportsFixedSell();
    }

    private void updateMarketControls() {
        if (marketQuantityBox == null) return;
        BankMenu.MarketEntry entry = selectedMarketEntry();
        if (entry == null) {
            marketError = "No market items configured.";
            marketNotice = "";
            marketUnavailable = false;
            marketConfirmBtn.active = false;
            return;
        }
        boolean fixedTrade = entry.config().tradeType() == MarketTradeType.FIXED;
        if (fixedTrade && !supportsBothFixedDirections(entry)) {
            marketBuy = entry.config().supportsFixedBuy();
        }
        if (!canDonate(entry)) {
            marketDonate = false;
        }
        if (marketDonate) {
            marketBuy = false;
        }
        MarketTradeQuote quote = currentMarketQuote(entry);
        marketUnavailable = !quote.valid() && "market_refuses_buying".equals(quote.invalidReason());
        marketNotice = "";
        marketError = quote.valid() ? "" : switch (quote.invalidReason()) {
            case "quantity_below_minimum" -> "Minimum trade: " + entry.config().minimumTradeSize();
            case "insufficient_market_stock" -> "The bank lacks this much stock.";
            case "market_refuses_buying" -> "Unavailable at current stock.";
            case "trade_batch_size" -> "Next dynamic batch: "
                    + MarketPricingEngine.nextTradeBatchSize(entry.config(), entry.stock(),
                    marketDemandContext(entry), parseMarketQuantity(),
                    marketSide(entry)) + " items.";
            case "fixed_trade_size" -> "Use complete fixed trade batches.";
            case "fixed_trade_direction" -> "This fixed trade is not available in that direction.";
            case "invalid_quantity" -> "Enter a whole-number quantity.";
            default -> "Trade unavailable.";
        };
        if (quote.valid() && quote.quantity() < parseMarketQuantity()) {
            marketNotice = "Executes " + quote.quantity() + "; "
                    + (parseMarketQuantity() - quote.quantity()) + " remains.";
        }
        if (marketBuy && isMapTrade(entry) && !hasMapSalePermission(entry)) {
            marketError = mapSalePermissionMessage();
        } else if (quote.valid() && minecraft != null && minecraft.player != null) {
            int quantity = quote.quantity();
            int held = countMarketItem(minecraft.player.getInventory(), entry);
            int emeralds = countPlayerItem(minecraft.player.getInventory(), net.minecraft.world.item.Items.EMERALD);
            if (marketBuy && emeralds < quote.emeraldAmount()) {
                marketError = "You cannot afford this trade.";
            } else if (!marketBuy && held < quantity) {
                marketError = "You do not hold enough of this item.";
            }
            if (marketDonate && quote.emeraldAmount() <= 0) {
                marketError = "This donation would not improve the bank's opinion.";
            }
        }
        marketDirectionBtn.setMessage(Component.literal(marketBuy ? "Buy" : "Sell"));
        marketDirectionBtn.active = (!fixedTrade || supportsBothFixedDirections(entry)) && !marketDonate;
        marketDonateBtn.setMessage(Component.literal(marketDonate ? "Donating" : "Donate"));
        marketDonateBtn.active = canDonate(entry) && (!fixedTrade || !marketBuy);
        marketConfirmBtn.setMessage(Component.literal(marketDonate ? "Donate" : "Confirm"));
        marketConfirmBtn.active = quote.valid() && marketError.isEmpty()
                && (!marketDonate || quote.emeraldAmount() > 0);
    }

    private void changeQuantity(int delta) {
        if (marketQuantityBox == null) return;
        int current = parseMarketQuantity();
        int max = maxPlayerQuantity();
        BankMenu.MarketEntry entry = selectedMarketEntry();
        int next = entry == null ? Math.max(0, current + delta)
                : MarketPricingEngine.nextValidTradeQuantity(entry.config(), entry.stock(),
                marketDemandContext(entry), current,
                marketSide(entry), delta);
        marketQuantityBox.setValue(String.valueOf(Math.max(0, Math.min(max, next))));
        updateMarketControls();
    }

    private int maxPlayerQuantity() {
        BankMenu.MarketEntry entry = selectedMarketEntry();
        if (entry == null || minecraft == null || minecraft.player == null) return 0;
        int held = countMarketItem(minecraft.player.getInventory(), entry);
        if (entry.config().tradeType() == MarketTradeType.FIXED) {
            if (!marketBuy) {
                return Math.min(held, 9999);
            }
            int emeralds = countPlayerItem(minecraft.player.getInventory(), net.minecraft.world.item.Items.EMERALD);
            if (!entry.config().supportsFixedBuy()) {
                return 0;
            }
            return Math.min(entry.stock(), emeralds / entry.config().fixedEmeraldAmount(TradeSide.BUY))
                    * entry.config().minimumTradeSize();
        }
        int max = marketBuy && !marketDonate ? Math.min(entry.stock(), 9999) : Math.min(held, 9999);
        int emeralds = countPlayerItem(minecraft.player.getInventory(), net.minecraft.world.item.Items.EMERALD);
        TradeSide side = marketSide(entry);
        if (!marketBuy && entry.config().metric() == MarketMetric.TARGET_RATIO) {
            return max;
        }
        if (entry.config().metric() == MarketMetric.TARGET_RATIO) {
            return MarketPricingEngine.maxValidBatchTradeQuantity(entry.config(), entry.stock(),
                    marketDemandContext(entry), max, side, emeralds);
        }
        if (!marketBuy || marketDonate) return max;
        int valid = 0;
        for (int quantity = entry.config().minimumTradeSize(); quantity <= max; quantity++) {
            MarketTradeQuote quote = MarketPricingEngine.quote(entry.config(), entry.stock(),
                    marketDemandContext(entry), quantity, TradeSide.BUY);
            if (!quote.valid() || quote.emeraldAmount() > emeralds) break;
            valid = quantity;
        }
        return valid;
    }

    private int parseMarketQuantity() {
        try {
            return Math.max(0, Integer.parseInt(marketQuantityBox == null
                    ? "0" : marketQuantityBox.getValue()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int countPlayerItem(Inventory inventory, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) count += inventory.getItem(slot).getCount();
        }
        return count;
    }

    private int countMarketItem(Inventory inventory, BankMenu.MarketEntry entry) {
        net.minecraft.world.item.Item item = marketStack(entry).getItem();
        if (!BankBlockEntity.isCoalMarketItem(item)) {
            return countPlayerItem(inventory, item);
        }
        return countPlayerItem(inventory, net.minecraft.world.item.Items.COAL)
                + countPlayerItem(inventory, net.minecraft.world.item.Items.CHARCOAL);
    }

    private void confirmMarketTrade() {
        BankMenu.MarketEntry entry = selectedMarketEntry();
        MarketTradeQuote quote = entry == null ? null : currentMarketQuote(entry);
        if (entry != null && quote != null && quote.valid()) {
            PacketDistributor.sendToServer(new MarketTradePacket(menu.getBlockPos(), entry.id(),
                    quote.quantity(), marketBuy, marketDonate));
        }
    }

    private String formatRate(double baseRate, int stock, int population) {
        BankMenu.MarketEntry entry = selectedMarketEntry();
        return entry == null ? format(baseRate) : format(MarketPricingEngine.midRate(
                entry.config(), stock, marketDemandContext(entry)));
    }

    private MarketDemandContext marketDemandContext(BankMenu.MarketEntry entry) {
        return new MarketDemandContext(entry.population(), entry.bankTarget());
    }

    private String format(double value) { return String.format(java.util.Locale.ROOT, "%.2f", value); }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (activeTab == Tab.MARKET && marketList != null && marketList.isMouseOver(mouseX, mouseY)) {
            return marketList.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (activeTab == Tab.OVERVIEW && !chestLines.isEmpty()) {
            overviewScrollOffset = Math.max(0,
                    Math.min(overviewScrollOffset - (int) scrollY, chestLines.size() - 1));
            return true;
        }
        if (activeTab == Tab.ACCOUNTS && !accountDisplayItems.isEmpty()) {
            accountScrollOffset = Math.max(0,
                    Math.min(accountScrollOffset - (int) scrollY, accountDisplayItems.size() - 1));
            return true;
        }
        if (activeTab == Tab.EMPLOYEES && !menu.getEmployees().isEmpty()) {
            employeeScrollOffset = Math.max(0,
                    Math.min(employeeScrollOffset - (int) scrollY, menu.getEmployees().size() - 1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // Helpers

    private void drawRow(GuiGraphics g, int y, String label, String value, int valueColor) {
        g.drawString(font, fitMarketText(label, VALUE_X - LABEL_X - 4), LABEL_X, y, LABEL_COLOR);
        drawMarketText(g, VALUE_X, y, value, valueColor);
    }

    private String formatOpinion(int opinion) {
        return opinion > 0 ? "+" + opinion : String.valueOf(opinion);
    }

    private int opinionColor(int opinion) {
        return opinion > 0 ? VALUE_COLOR : opinion < 0 ? WARN_COLOR : LABEL_COLOR;
    }

    private void buildAccountDisplayItems() {
        List<BankMenu.AccountEntry> accounts = menu.getAccounts();
        accountQueuedCount = (int) accounts.stream().filter(BankMenu.AccountEntry::isQueued).count();
        accountDisplayItems = BankPresentation.accountRows(accounts.stream()
                .map(entry -> new BankPresentation.AccountSnapshot(
                        entry.name(), entry.balance(), entry.isQueued(), entry.queuePosition()))
                .toList());
    }

    private void buildChestLines() {
        List<BankPresentation.ChestPosition> positions = menu.getChestPositions().stream()
                .map(pos -> new BankPresentation.ChestPosition(pos.getX(), pos.getY(), pos.getZ()))
                .toList();
        chestLines = BankPresentation.chestLines(positions, BankMenuOpenData.MAX_CHEST_POSITIONS, menu.getChestCount());
    }

    private final class MarketItemList extends ObjectSelectionList<MarketItemList.Entry> {
        private MarketItemList(Minecraft minecraft, int width, int top, int bottom, int itemHeight) {
            // In 1.21.1 the final constructor argument is the row height, not
            // the bottom coordinate.  Keep the list's widget bounds aligned
            // with the bank panel so its scissor and hitbox match its rows.
            super(minecraft, width, bottom - top, top, itemHeight);
            setPosition(leftPos + PADDING, top);
        }

        @Override
        public int getRowWidth() { return MARKET_LIST_W - 6; }

        @Override
        protected void renderSelection(GuiGraphics g, int rowTop, int rowWidth, int rowHeight,
                                       int outerColor, int ignoredInnerColor) {
            super.renderSelection(g, rowTop, rowWidth, rowHeight, outerColor, SELECTED_ROW_COLOR);
        }

        private void setEntries(List<Entry> entries) {
            replaceEntries(entries);
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final BankMenu.MarketEntry marketEntry;

            private Entry(BankMenu.MarketEntry marketEntry) {
                this.marketEntry = marketEntry;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                if (hovering && index != selectedMarketIndex) {
                    g.fill(left, top, left + width, top + height - 1, 0x44333333);
                }
                g.renderItem(marketStack(marketEntry), left + 2, top + 1);
                String name = fitMarketText(marketEntry.displayName(), width - 34);
                g.drawString(font, name, left + 24, top + 1, TITLE_COLOR);
                MarketTradeQuote offerQuote = marketOfferQuote(marketEntry);
                String offer = fitMarketText(marketOfferLabel(marketEntry, offerQuote), width - 34);
                g.drawString(font, offer, left + 24, top + 13, marketOfferColor(marketEntry, offerQuote));
                drawSupplyBar(g, left + 24, top + 24, width - 30, marketEntry, marketEntry.stock(), false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                selectedMarketIndex = marketList.children().indexOf(this);
                marketList.setSelected(this);
                marketDonate = false;
                marketQuantityBox.setValue(String.valueOf(MarketPricingEngine.tradeBatchSize(
                        marketEntry.config(), marketEntry.stock(), marketDemandContext(marketEntry),
                        marketSide(marketEntry))));
                updateMarketControls();
                refreshMarketList();
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(marketEntry.displayName());
            }
        }
    }

    private String fitMarketText(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int textWidth = Math.max(1, maxWidth - font.width(ellipsis));
        return font.plainSubstrByWidth(text, textWidth) + ellipsis;
    }

    private MarketTradeQuote marketOfferQuote(BankMenu.MarketEntry entry) {
        TradeSide side = entry.config().tradeType() == MarketTradeType.FIXED
                && entry.config().supportsFixedBuy() ? TradeSide.BUY : TradeSide.SELL;
        int batch = MarketPricingEngine.tradeBatchSize(entry.config(), entry.stock(),
                marketDemandContext(entry), side);
        return marketQuote(entry, batch, side);
    }

    private void resetMarketQuantityToBatch() {
        BankMenu.MarketEntry entry = selectedMarketEntry();
        if (marketQuantityBox == null || entry == null) {
            return;
        }
        marketQuantityBox.setValue(String.valueOf(MarketPricingEngine.tradeBatchSize(
                entry.config(), entry.stock(), marketDemandContext(entry), marketSide(entry))));
    }

    private MarketTradeQuote marketQuote(BankMenu.MarketEntry entry, int batch, TradeSide side) {
        return MarketPricingEngine.quote(entry.config(), entry.stock(),
                marketDemandContext(entry), batch, side);
    }

    private String marketOfferLabel(BankMenu.MarketEntry entry, MarketTradeQuote quote) {
        if (isMapTrade(entry) && !hasMapSalePermission(entry)) {
            return "Requires bank opinion +" + MarketTradeService.MAP_SALE_BANK_OPINION_THRESHOLD;
        }
        if (!quote.valid()) {
            return "Unavailable";
        }
        if (quote.side() == TradeSide.BUY && quote.emeraldAmount() == 0) {
            return "Bank has too few " + pluralMarketItemName(entry.displayName());
        }
        String emeraldLabel = quote.emeraldAmount() == 1 ? "Emerald" : "Emeralds";
        if (quote.side() == TradeSide.BUY) {
            return quote.emeraldAmount() + " " + emeraldLabel + " -> "
                    + quote.quantity() + " " + entry.displayName();
        }
        return quote.quantity() + " " + entry.displayName() + " -> "
                + quote.emeraldAmount() + " " + emeraldLabel;
    }

    private String pluralMarketItemName(String displayName) {
        String itemName = displayName.toLowerCase(java.util.Locale.ROOT);
        // Bread is a mass noun; "breads" is not the plural used for this item.
        return "bread".equals(itemName) ? itemName : itemName + "s";
    }

    private int marketOfferColor(BankMenu.MarketEntry entry, MarketTradeQuote quote) {
        return !quote.valid() || (isMapTrade(entry) && !hasMapSalePermission(entry))
                || quote.emeraldAmount() == 0 ? WARN_COLOR : VALUE_COLOR;
    }

    private boolean isMarketUnavailableForBank(BankMenu.MarketEntry entry) {
        TradeSide side = marketAvailabilitySide(entry);
        int batch = MarketPricingEngine.tradeBatchSize(entry.config(), entry.stock(),
                marketDemandContext(entry), side);
        MarketTradeQuote quote = marketQuote(entry, batch, side);
        // Keep zero-payout sell offers visually marked as unavailable in the market list.
        return !quote.valid() || (side == TradeSide.SELL && quote.emeraldAmount() <= 0);
    }

    private TradeSide marketAvailabilitySide(BankMenu.MarketEntry entry) {
        if (entry.config().tradeType() == MarketTradeType.FIXED) {
            if (marketBuy && entry.config().supportsFixedBuy()) {
                return TradeSide.BUY;
            }
            if (!marketBuy && entry.config().supportsFixedSell()) {
                return TradeSide.SELL;
            }
            return entry.config().supportsFixedBuy() ? TradeSide.BUY : TradeSide.SELL;
        }
        return marketSide(entry);
    }

    private int marketSortPriority(BankMenu.MarketEntry entry) {
        if (isMapTrade(entry) && !hasMapSalePermission(entry)) {
            return 2;
        }
        return isMarketUnavailableForBank(entry) ? 1 : 0;
    }

    private boolean isMapTrade(BankMenu.MarketEntry entry) {
        return ECAPItems.ABANDONED_VAULT_MAP.getId().toString().equals(entry.itemId());
    }

    private boolean hasMapSalePermission(BankMenu.MarketEntry entry) {
        return !isMapTrade(entry)
                || menu.getBankOpinion() >= MarketTradeService.MAP_SALE_BANK_OPINION_THRESHOLD;
    }

    private String mapSalePermissionMessage() {
        return "Requires bank opinion of at least +"
                + MarketTradeService.MAP_SALE_BANK_OPINION_THRESHOLD + ".";
    }

    private boolean supportsBothFixedDirections(BankMenu.MarketEntry entry) {
        return entry.config().supportsFixedBuy() && entry.config().supportsFixedSell();
    }

}
