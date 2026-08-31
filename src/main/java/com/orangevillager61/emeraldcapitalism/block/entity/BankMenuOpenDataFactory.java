package com.orangevillager61.emeraldcapitalism.block.entity;

import com.orangevillager61.emeraldcapitalism.menu.BankMenu;
import com.orangevillager61.emeraldcapitalism.menu.BankMenuOpenData;
import com.orangevillager61.emeraldcapitalism.market.MarketItem;
import com.orangevillager61.emeraldcapitalism.market.MarketRegistry;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldSkrimisher;
import com.orangevillager61.emeraldcapitalism.world.bank.BankAccountData;
import com.orangevillager61.emeraldcapitalism.world.bank.BankReputationData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds the server-authoritative snapshot used when a bank menu opens. */
final class BankMenuOpenDataFactory {
    private BankMenuOpenDataFactory() {
    }

    static BankMenuOpenData create(BankBlockEntity bank, @Nullable UUID viewerId) {
        Level level = bank.getLevel();
        UUID villageId = bank.getVillageId();
        String villageName = resolveVillageName(villageId, level);
        int bankOpinion = resolveBankOpinion(viewerId, level);

        return new BankMenuOpenData(
                bank.getBlockPos(), bank.getBankName(), villageId, villageName,
                bank.isBankIndependent(), bank.getControllerId(), bankOpinion,
                new BankMenuOpenData.EntityCounts(
                        bank.getDepositQueueSizeForMenu(), bank.getEmployeeCount(),
                        bank.getEmeraldGolemCount(), bank.getExpectedEmeraldGolemCount()),
                new BankMenuOpenData.Targets(
                        bank.getPumpkinTarget(), bank.getBreadTarget(),
                        bank.getPlankTarget(), bank.getCoalTarget()),
                bank.getControlSettings(),
                new BankMenuOpenData.Totals(
                        bank.getTotalEmeraldCount(), bank.getTotalEmeraldOreCount(),
                        bank.getTotalPumpkinCount(), bank.getTotalWheatCount(),
                        bank.getTotalBreadCount(), bank.getTotalCoalCount(),
                        bank.getTotalEmeraldGreenDyeCount(), bank.getTotalPlankCount()),
                bank.getChestCount(),
                bank.getCachedChestPositions().stream()
                        .limit(BankMenuOpenData.MAX_CHEST_POSITIONS)
                        .toList(),
                buildAccountsList(bank),
                buildEmployees(bank),
                buildMarketEntries(bank)
        );
    }

    private static String resolveVillageName(@Nullable UUID villageId, Level level) {
        if (villageId == null || !(level instanceof ServerLevel serverLevel)) {
            return "";
        }
        ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return "";
        }
        VillageRecord record = VillageRegistryData.get(overworld).getVillages().get(villageId);
        return record == null ? "" : record.getName();
    }

    private static int resolveBankOpinion(@Nullable UUID viewerId, Level level) {
        if (viewerId == null || !(level instanceof ServerLevel serverLevel)) {
            return 0;
        }
        return BankReputationData.get(serverLevel).getReputation(viewerId);
    }

    static List<BankMenu.MarketEntry> buildMarketEntries(BankBlockEntity bank) {
        Level level = bank.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return List.of();
        }

        int population = bank.getMarketPopulation(serverLevel);
        List<BankMenu.MarketEntry> entries = new ArrayList<>();
        for (MarketItem marketItem : MarketRegistry.entries()) {
            int stock = bank.getMarketStock(serverLevel, marketItem.item());
            entries.add(new BankMenu.MarketEntry(
                    marketItem.config().id(),
                    marketItem.itemId().toString(),
                    marketItem.item().getDescription().getString(),
                    stock,
                    population,
                    bank.getMarketTarget(serverLevel, marketItem),
                    marketItem.config()));
            if (entries.size() == BankMenuOpenData.MAX_MARKET_ENTRIES) {
                break;
            }
        }
        return List.copyOf(entries);
    }

    private static List<BankMenu.AccountEntry> buildAccountsList(BankBlockEntity bank) {
        Level level = bank.getLevel();
        UUID villageId = bank.getVillageId();
        if (!(level instanceof ServerLevel serverLevel) || villageId == null) {
            return List.of();
        }

        ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return List.of();
        }

        VillageRecord record = VillageRegistryData.get(overworld).getVillages().get(villageId);
        if (record == null) {
            return List.of();
        }

        Map<UUID, Integer> queuePositions = new HashMap<>();
        UUID currentDepositor = bank.getCurrentDepositorForMenu();
        if (currentDepositor != null) {
            queuePositions.put(currentDepositor, 0);
        }
        int position = 1;
        for (UUID uuid : bank.getDepositQueueSnapshotForMenu()) {
            queuePositions.put(uuid, position++);
        }

        BankAccountData accountData = BankAccountData.get(serverLevel);
        List<BankMenu.AccountEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, VillagerPOIRecord> entry : record.getMembers().entrySet()) {
            UUID uuid = entry.getKey();
            if (!accountData.hasAccount(uuid)) {
                continue;
            }
            entries.add(new BankMenu.AccountEntry(
                    entry.getValue().getDisplayName(),
                    accountData.getBalance(uuid),
                    queuePositions.getOrDefault(uuid, -1)));
        }
        entries.sort(Comparator.comparing(BankMenu.AccountEntry::name));
        return List.copyOf(entries);
    }

    private static List<BankMenu.EmployeeEntry> buildEmployees(BankBlockEntity bank) {
        if (!(bank.getLevel() instanceof ServerLevel serverLevel)) {
            return List.of();
        }

        ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
        VillageRecord village = bank.getVillageId() == null || overworld == null
                ? null
                : VillageRegistryData.get(overworld).getVillages().get(bank.getVillageId());
        List<BankMenu.EmployeeEntry> entries = new ArrayList<>();
        for (UUID employeeId : bank.getEmployeeIds()) {
            if (entries.size() >= BankMenuOpenData.MAX_EMPLOYEE_ENTRIES) {
                return List.copyOf(entries);
            }
            Entity entity = serverLevel.getEntity(employeeId);
            if (entity instanceof Villager villager) {
                entries.add(new BankMenu.EmployeeEntry(
                        villager.getName().getString(), "Villager", professionName(villager)));
                continue;
            }

            VillagerPOIRecord record = village == null ? null : village.getMembers().get(employeeId);
            entries.add(new BankMenu.EmployeeEntry(
                    record == null ? "Unloaded villager" : record.getDisplayName(),
                    "Villager", record == null ? "Unknown" : record.getProfession()));
        }

        for (UUID employeeId : bank.getEmeraldGolemEmployeeIds()) {
            if (entries.size() >= BankMenuOpenData.MAX_EMPLOYEE_ENTRIES) {
                break;
            }
            Entity entity = serverLevel.getEntity(employeeId);
            String entityType = entity instanceof EmeraldSkrimisher
                    ? "Emerald Skrimisher" : "Emerald Golem";
            String name = entity == null ? entityType : entity.getName().getString();
            entries.add(new BankMenu.EmployeeEntry(name, entityType, "—"));
        }
        return List.copyOf(entries);
    }

    private static String professionName(Villager villager) {
        var professionId = BuiltInRegistries.VILLAGER_PROFESSION
                .getKey(villager.getVillagerData().getProfession());
        return professionId == null ? "Unknown" : professionId.getPath();
    }
}
