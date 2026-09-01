package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.world.bank.BankAccountData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmSavedData;
import com.orangevillager61.emeraldcapitalism.util.DimensionDataStorageCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class SavedDataPersistenceGameTests {

    private static final String BANK_DATA_NAME = "emeraldcapitalism_bank_accounts";
    private static final String FARM_DATA_NAME = "emeraldcapitalism_village_farms";
    private static final String REGISTRY_DATA_NAME = "emeraldcapitalism_village_registry";

    private SavedDataPersistenceGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void savedDataFactoriesReloadThroughDimensionStorage(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel();
        BankAccountData worldBank = BankAccountData.get(overworld);

        ServerLevel nether = overworld.getServer().getLevel(Level.NETHER);
        if (nether == null || BankAccountData.get(nether) != worldBank) {
            helper.fail("BankAccountData did not resolve to the overworld-owned instance");
            return;
        }

        UUID villagerId = UUID.randomUUID();
        BankAccountData bank = new BankAccountData();
        bank.openAccount(villagerId);
        bank.deposit(villagerId, 12);

        VillageFarmSavedData farms = new VillageFarmSavedData();
        BlockPos farmCenter = new BlockPos(-16, 64, 16);
        farms.markVillageDetected(farmCenter);
        farms.markFarmsPlaced(farmCenter);

        VillageRegistryData registry = new VillageRegistryData();
        UUID villageId = UUID.randomUUID();
        VillageRecord village = registry.getOrCreateVillage(
                villageId, new BlockPos(0, 64, 0), new AABB(-4, 60, -4, 4, 68, 4));
        village.setName("SavedData Reload Village");
        ChunkPos processedChunk = new ChunkPos(-3, 4);
        registry.markVillageRegistered(processedChunk);
        registry.markBankGenerated(villageId);
        BlockPos bankPosition = new BlockPos(2, 64, 2);
        registry.registerBankPosition(villageId, bankPosition);
        BoundingBox pendingBox = new BoundingBox(-8, 10, -6, 12, 22, 14);
        registry.addPendingManagerPlacement(villageId, pendingBox);

        Path storageDirectory;
        try {
            storageDirectory = Files.createTempDirectory("ecap-saved-data-gametest-");
            DimensionDataStorage storage = DimensionDataStorageCompat.create(
                    storageDirectory, overworld.getServer().getFixerUpper(), overworld.registryAccess());
            storage.set(BANK_DATA_NAME, bank);
            storage.set(FARM_DATA_NAME, farms);
            storage.set(REGISTRY_DATA_NAME, registry);
            DimensionDataStorageCompat.save(storage);
        } catch (IOException ex) {
            helper.fail("Could not create the SavedData storage fixture: " + ex.getMessage());
            return;
        }

        helper.runAfterDelay(10, () -> reloadAndAssert(helper, overworld, storageDirectory,
                villagerId, farmCenter, villageId, processedChunk, bankPosition));
    }

    private static void reloadAndAssert(
            GameTestHelper helper,
            ServerLevel level,
            Path storageDirectory,
            UUID villagerId,
            BlockPos farmCenter,
            UUID villageId,
            ChunkPos processedChunk,
            BlockPos bankPosition
    ) {
        try {
            DimensionDataStorage reloadedStorage = DimensionDataStorageCompat.create(
                    storageDirectory, level.getServer().getFixerUpper(), level.registryAccess());
            BankAccountData bank = reloadedStorage.computeIfAbsent(
                    new net.minecraft.world.level.saveddata.SavedData.Factory<>(
                            BankAccountData::new, BankAccountData::load, null),
                    "emeraldcapitalism_bank_accounts");
            VillageFarmSavedData farms = reloadedStorage.computeIfAbsent(
                    new net.minecraft.world.level.saveddata.SavedData.Factory<>(
                            VillageFarmSavedData::new, VillageFarmSavedData::load, null),
                    "emeraldcapitalism_village_farms");
            VillageRegistryData registry = reloadedStorage.computeIfAbsent(
                    new net.minecraft.world.level.saveddata.SavedData.Factory<>(
                            VillageRegistryData::new, VillageRegistryData::load, null),
                    "emeraldcapitalism_village_registry");

            helper.assertValueEqual(bank.getBalance(villagerId), 12,
                    "bank balance did not survive DimensionDataStorage reload");
            helper.assertTrue(farms.isVillageDetected(farmCenter),
                    "detected village did not survive DimensionDataStorage reload");
            helper.assertTrue(farms.areFarmsPlaced(farmCenter),
                    "farm placement did not survive DimensionDataStorage reload");
            helper.assertTrue(!farms.markVillageDetected(farmCenter)
                            && !farms.markFarmsPlaced(farmCenter),
                    "farm duplicate suppression did not survive DimensionDataStorage reload");
            VillageRecord village = registry.getVillages().get(villageId);
            helper.assertTrue(village != null,
                    "village record did not survive DimensionDataStorage reload");
            helper.assertTrue(registry.getVillageFor(new BlockPos(0, 64, 0)) == village,
                    "village lookup by bounds did not survive DimensionDataStorage reload");
            helper.assertTrue(registry.getNearestVillage(new BlockPos(100, 64, 100)) == village,
                    "nearest-bell lookup did not survive DimensionDataStorage reload");
            helper.assertTrue(registry.isVillageRegistered(processedChunk),
                    "processed chunk suppression did not survive DimensionDataStorage reload");
            helper.assertTrue(registry.hasGeneratedBank(villageId),
                    "generated-bank suppression did not survive DimensionDataStorage reload");
            helper.assertTrue(bankPosition.equals(registry.getBankPos(villageId)),
                    "bank lookup did not survive DimensionDataStorage reload");
            VillageRegistryData.PendingManagerPlacement pending = registry.getPendingManagerPlacements().stream()
                    .filter(placement -> placement.villageId().equals(villageId))
                    .findFirst()
                    .orElse(null);
            helper.assertTrue(pending != null,
                    "pending manager placement did not survive DimensionDataStorage reload");
            registry.removePendingManagerPlacement(pending);
            helper.assertTrue(registry.getPendingManagerPlacements().stream()
                            .noneMatch(placement -> placement.villageId().equals(villageId)),
                    "pending manager placement could not be processed after reload");
            helper.succeed();
        } catch (RuntimeException ex) {
            helper.fail("SavedData world-storage reload failed: " + ex.getMessage());
        } finally {
            deleteTemporaryStorage(storageDirectory);
        }
    }

    private static void deleteTemporaryStorage(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The test result already reports the functional assertion.
                }
            });
        } catch (IOException ignored) {
            // Temporary test data is not part of the mod's persisted world state.
        }
    }
}
