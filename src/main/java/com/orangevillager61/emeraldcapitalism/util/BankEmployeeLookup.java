package com.orangevillager61.emeraldcapitalism.util;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

/** Server-side lookup helpers for Bank employee membership. */
public final class BankEmployeeLookup {

    private static final long LOOKUP_CACHE_TICKS = 20L;
    private static final int MAX_CACHE_ENTRIES_PER_LEVEL = 4096;
    private static final Map<ServerLevel, Map<UUID, CachedLookup>> VILLAGE_BANK_CACHE =
            new WeakHashMap<>();
    private static final Map<ServerLevel, Map<UUID, CachedLookup>> EMPLOYEE_BANK_CACHE =
            new WeakHashMap<>();

    private BankEmployeeLookup() {
    }

    public static boolean isEmployee(ServerLevel level, Villager villager) {
        return findEmployeeBank(level, villager) != null;
    }

    /** Finds the loaded Bank registered to the villager's village, regardless of employee status. */
    @Nullable
    public static BankBlockEntity findVillageBank(ServerLevel level, Villager villager) {
        long gameTime = level.getGameTime();
        UUID villagerId = villager.getUUID();
        Map<UUID, CachedLookup> cache = VILLAGE_BANK_CACHE.computeIfAbsent(
                level, ignored -> newLookupCache());
        CachedLookup cached = cache.get(villagerId);
        if (cached != null && gameTime < cached.nextLookupTick()) {
            return findCachedBank(level, cached);
        }

        VillageRegistryData registry = VillageRegistryData.get(level);
        // Prefer the villager's durable membership when it is available. This
        // avoids choosing an unrelated overlapping village during structure
        // placement or while neighboring village bounds are still broad.
        for (VillageRecord village : registry.getVillages().values()) {
            if (village.hasMember(villager.getUUID())) {
                BankBlockEntity bank = findBankAt(level, registry.getBankPos(village.getVillageId()));
                if (bank != null) {
                    cache.put(villagerId, new CachedLookup(
                            gameTime + LOOKUP_CACHE_TICKS, bank.getBlockPos().immutable()));
                    return bank;
                }
            }
        }

        // A newly spawned villager may not have been registered yet. Check every
        // containing village because an automatically-created overlapping record
        // can be nearer than the bank-backed village.
        double x = villager.getX();
        double y = villager.getY();
        double z = villager.getZ();
        BankBlockEntity nearestBank = null;
        double nearestDistance = Double.MAX_VALUE;
        for (VillageRecord village : registry.getVillages().values()) {
            if (!village.getBoundingBox().contains(x, y, z)) {
                continue;
            }
            BankBlockEntity bank = findBankAt(level, registry.getBankPos(village.getVillageId()));
            if (bank == null) {
                continue;
            }
            double distance = bank.getBlockPos().distSqr(villager.blockPosition());
            if (distance < nearestDistance
                    || (Double.compare(distance, nearestDistance) == 0
                    && (nearestBank == null
                    || bank.getBlockPos().asLong() < nearestBank.getBlockPos().asLong()))) {
                nearestBank = bank;
                nearestDistance = distance;
            }
        }
        cache.put(villagerId, new CachedLookup(
                gameTime + LOOKUP_CACHE_TICKS,
                nearestBank == null ? null : nearestBank.getBlockPos().immutable()));
        return nearestBank;
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
        long gameTime = level.getGameTime();
        UUID villagerId = villager.getUUID();
        Map<UUID, CachedLookup> cache = EMPLOYEE_BANK_CACHE.computeIfAbsent(
                level, ignored -> newLookupCache());
        CachedLookup cached = cache.get(villagerId);
        if (cached != null && gameTime < cached.nextLookupTick()) {
            return findCachedBank(level, cached);
        }

        VillageRegistryData registry = VillageRegistryData.get(level);
        // Employees may be inside an overlapping unbanked record. Check every
        // containing record before falling back to employees outside their bounds.
        double x = villager.getX();
        double y = villager.getY();
        double z = villager.getZ();
        for (VillageRecord village : registry.getVillages().values()) {
            if (!village.getBoundingBox().contains(x, y, z)) {
                continue;
            }
            BankBlockEntity bank = findEmployeeBankAt(level,
                    registry.getBankPos(village.getVillageId()), villager);
            if (bank != null) {
                cache.put(villagerId, new CachedLookup(
                        gameTime + LOOKUP_CACHE_TICKS, bank.getBlockPos().immutable()));
                return bank;
            }
        }

        // Employees may temporarily be outside their village bounds. Check loaded
        // registered Banks as a fallback without forcing chunks to load.
        for (VillageRecord village : registry.getVillages().values()) {
            BankBlockEntity bank = findEmployeeBankAt(level,
                    registry.getBankPos(village.getVillageId()), villager);
            if (bank != null) {
                cache.put(villagerId, new CachedLookup(
                        gameTime + LOOKUP_CACHE_TICKS, bank.getBlockPos().immutable()));
                return bank;
            }
        }
        cache.put(villagerId, new CachedLookup(gameTime + LOOKUP_CACHE_TICKS, null));
        return null;
    }

    @Nullable
    private static BankBlockEntity findEmployeeBankAt(ServerLevel level, BlockPos bankPos, Villager villager) {
        if (bankPos == null) {
            return null;
        }
        BlockEntity blockEntity = getLoadedBlockEntity(level, bankPos);
        return blockEntity instanceof BankBlockEntity bank && bank.isEmployee(villager.getUUID())
                ? bank : null;
    }

    @Nullable
    private static BankBlockEntity findBankAt(ServerLevel level, @Nullable BlockPos bankPos) {
        if (bankPos == null) {
            return null;
        }
        BlockEntity blockEntity = getLoadedBlockEntity(level, bankPos);
        return blockEntity instanceof BankBlockEntity bank ? bank : null;
    }

    /** Returns a block entity only when its full chunk is already loaded. */
    @Nullable
    public static BlockEntity getLoadedBlockEntity(ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null
                ? null
                : chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
    }

    /** Returns a block state only when its full chunk is already loaded. */
    @Nullable
    public static BlockState getLoadedBlockState(ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    @Nullable
    private static BankBlockEntity findCachedBank(ServerLevel level, CachedLookup cached) {
        if (cached.bankPos() == null) {
            return null;
        }
        return getLoadedBlockEntity(level, cached.bankPos()) instanceof BankBlockEntity bank
                && !bank.isRemoved() ? bank : null;
    }

    private record CachedLookup(long nextLookupTick, @Nullable BlockPos bankPos) {
    }

    private static Map<UUID, CachedLookup> newLookupCache() {
        return new LinkedHashMap<>(128, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, CachedLookup> eldest) {
                return size() > MAX_CACHE_ENTRIES_PER_LEVEL;
            }
        };
    }
}
