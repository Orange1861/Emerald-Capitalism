package com.orangevillager61.emeraldcapitalism.menu;

import com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity;
import com.orangevillager61.emeraldcapitalism.network.ProtocolStringLimits;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Container menu for the Village Manager block.
 * Syncs village summary data from server to client via the extra data buffer.
 */
public class VillageManagerMenu extends AbstractContainerMenu {

    private final BlockPos blockPos;
    @Nullable
    private final UUID villageId;
    private final String villageName;
    private final BlockPos bellPos;
    private final double minX, minY, minZ, maxX, maxY, maxZ;
    private final int memberCount;

    // Server-side constructor
    public VillageManagerMenu(int containerId, Inventory playerInventory, VillageManagerBlockEntity blockEntity) {
        super(ECAPMenuTypes.VILLAGE_MANAGER_MENU.get(), containerId);
        this.blockPos = blockEntity.getBlockPos();
        this.villageId = blockEntity.getVillageId();

        // These will be sent to the client via writeMenuOpenData
        this.villageName = "";
        this.bellPos = BlockPos.ZERO;
        this.minX = 0;
        this.minY = 0;
        this.minZ = 0;
        this.maxX = 0;
        this.maxY = 0;
        this.maxZ = 0;
        this.memberCount = 0;
    }

    // Client-side constructor (receives data from FriendlyByteBuf)
    public VillageManagerMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        super(ECAPMenuTypes.VILLAGE_MANAGER_MENU.get(), containerId);
        this.blockPos = extraData.readBlockPos();
        boolean hasVillage = extraData.readBoolean();
        this.villageId = hasVillage ? extraData.readUUID() : null;
        this.villageName = extraData.readUtf(ProtocolStringLimits.MAX_VILLAGE_NAME_LENGTH);
        this.bellPos = extraData.readBlockPos();
        this.minX = extraData.readDouble();
        this.minY = extraData.readDouble();
        this.minZ = extraData.readDouble();
        this.maxX = extraData.readDouble();
        this.maxY = extraData.readDouble();
        this.maxZ = extraData.readDouble();
        this.memberCount = extraData.readInt();
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockState(blockPos).is(ECAPBlocks.VILLAGE_MANAGER.get())
                && player.level().getBlockEntity(blockPos) instanceof VillageManagerBlockEntity
                && player.distanceToSqr(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5) <= 64.0;
    }

    // Getters for the screen

    @Nullable
    public UUID getVillageId() {
        return villageId;
    }

    public String getVillageName() {
        return villageName;
    }

    public BlockPos getBellPos() {
        return bellPos;
    }

    public double getMinX() { return minX; }
    public double getMinY() { return minY; }
    public double getMinZ() { return minZ; }
    public double getMaxX() { return maxX; }
    public double getMaxY() { return maxY; }
    public double getMaxZ() { return maxZ; }

    public int getMemberCount() {
        return memberCount;
    }
}
