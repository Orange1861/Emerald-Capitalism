package com.orangevillager61.emeraldcapitalism.menu;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.market.MarketItemConfig;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/** Container menu for the Bank block. */
public class BankMenu extends AbstractContainerMenu {

    public record AccountEntry(String name, int balance, int queuePosition) {
        public boolean isQueued() {
            return queuePosition >= 0;
        }
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
    private int bankOpinion;
    private List<MarketEntry> marketEntries;
    private BankMenuOpenData.ControlSettings controlSettings;

    /** Server-side constructor; display data is sent through the menu-open payload. */
    public BankMenu(int containerId, Inventory playerInventory, BankBlockEntity blockEntity,
                    @Nullable UUID viewerId) {
        super(ECAPMenuTypes.BANK_MENU.get(), containerId);
        this.openData = BankMenuOpenData.empty(blockEntity.getBlockPos());
        this.viewerId = viewerId;
        this.bankOpinion = 0;
        this.marketEntries = List.of();
        this.controlSettings = this.openData.controlSettings();
    }

    /** Client-side constructor for the server-authoritative open snapshot. */
    public BankMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        super(ECAPMenuTypes.BANK_MENU.get(), containerId);
        this.openData = BankMenuOpenData.read(buf);
        this.viewerId = null;
        this.bankOpinion = openData.bankOpinion();
        this.marketEntries = openData.marketEntries();
        this.controlSettings = openData.controlSettings();
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        BlockPos blockPos = getBlockPos();
        return player.level().getBlockState(blockPos).is(ECAPBlocks.BANK.get())
                && player.level().getBlockEntity(blockPos) instanceof BankBlockEntity
                && player.distanceToSqr(
                blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5) <= 64.0;
    }

    public BlockPos getBlockPos() {
        return openData.blockPos();
    }

    public String getBankName() {
        return openData.bankName();
    }

    public boolean isBankIndependent() {
        return openData.bankIndependent();
    }

    @Nullable
    public UUID getControllerId() {
        return openData.controllerId();
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

    public int getDepositQueueSize() {
        return openData.entityCounts().depositQueueSize();
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
}
