package com.orangevillager61.emeraldcapitalism.util;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Server-side lookup helpers for Bank employee membership. */
public final class BankEmployeeLookup {

    private BankEmployeeLookup() {
    }

    public static boolean isEmployee(ServerLevel level, Villager villager) {
        return findEmployeeBank(level, villager) != null;
    }

    /** Finds the loaded Bank registered to the villager's village, regardless of employee status. */
    @Nullable
    public static BankBlockEntity findVillageBank(ServerLevel level, Villager villager) {
        VillageRegistryData registry = VillageRegistryData.get(level);
        // Prefer the villager's durable membership when it is available. This
        // avoids choosing an unrelated overlapping village during structure
        // placement or while neighboring village bounds are still broad.
        for (VillageRecord village : registry.getVillages().values()) {
            if (village.hasMember(villager.getUUID())) {
                BankBlockEntity bank = findBankAt(level, registry.getBankPos(village.getVillageId()));
                if (bank != null) {
                    return bank;
                }
            }
        }

        // A newly spawned villager may not have been registered yet. Prefer the
        // nearest containing village so overlapping bounds remain deterministic.
        VillageRecord containingVillage = registry.getNearestVillage(villager.blockPosition());
        if (containingVillage != null && containingVillage.getBoundingBox().contains(
                villager.getX(), villager.getY(), villager.getZ())) {
            return findBankAt(level, registry.getBankPos(containingVillage.getVillageId()));
        }
        return null;
    }

    /** Finds the village whose registered bank owns this employee, if any. */
    @Nullable
    public static VillageRecord findEmployeeVillage(ServerLevel level, Villager villager) {
        BankBlockEntity bank = findEmployeeBank(level, villager);
        if (bank == null) {
            return null;
        }

        VillageRegistryData registry = VillageRegistryData.get(level);
        for (VillageRecord village : registry.getVillages().values()) {
            BlockPos bankPos = registry.getBankPos(village.getVillageId());
            if (bankPos != null && bankPos.equals(bank.getBlockPos())) {
                return village;
            }
        }
        return null;
    }

    /**
     * Finds the loaded Bank that owns a villager employee, including employees
     * that are temporarily outside their village bounds.
     */
    @Nullable
    public static BankBlockEntity findEmployeeBank(ServerLevel level, Villager villager) {
        VillageRegistryData registry = VillageRegistryData.get(level);
        VillageRecord containingVillage = registry.getVillageFor(villager.blockPosition());
        if (containingVillage != null) {
            BankBlockEntity bank = findEmployeeBankAt(level,
                    registry.getBankPos(containingVillage.getVillageId()), villager);
            if (bank != null) {
                return bank;
            }
        }

        // Employees may temporarily be outside their village bounds. Check loaded
        // registered Banks as a fallback without forcing chunks to load.
        for (VillageRecord village : registry.getVillages().values()) {
            if (containingVillage != null && village.getVillageId().equals(containingVillage.getVillageId())) {
                continue;
            }
            BankBlockEntity bank = findEmployeeBankAt(level,
                    registry.getBankPos(village.getVillageId()), villager);
            if (bank != null) {
                return bank;
            }
        }
        return null;
    }

    @Nullable
    private static BankBlockEntity findEmployeeBankAt(ServerLevel level, BlockPos bankPos, Villager villager) {
        if (bankPos == null) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(bankPos);
        return blockEntity instanceof BankBlockEntity bank && bank.isEmployee(villager.getUUID())
                ? bank : null;
    }

    @Nullable
    private static BankBlockEntity findBankAt(ServerLevel level, @Nullable BlockPos bankPos) {
        if (bankPos == null) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(bankPos);
        return blockEntity instanceof BankBlockEntity bank ? bank : null;
    }
}
