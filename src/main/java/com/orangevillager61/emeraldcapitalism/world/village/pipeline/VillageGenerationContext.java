package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.world.village.VillageManagerPlacement;
import com.orangevillager61.emeraldcapitalism.world.village.VillagePathBlocks;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRoadPathGenerator;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.ChunkLoadBudget;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmSavedData;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmSiteSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.util.SpawnReasonCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

import java.util.List;
import java.util.UUID;

/** Shared immutable village inputs plus per-task caches, budgets, and reservations. */
public final class VillageGenerationContext {
    private static final ResourceLocation MANAGER_RESERVATION = ModIds.id("village_manager");

    private final ServerLevel level;
    private final UUID villageId;
    private final BlockPos structureCenter;
    private final BlockPos bellPos;
    private final BoundingBox villageBox;
    private final List<StructurePiece> pieces;
    private final boolean abandonedVillage;
    private final String biomeType;
    private final ChunkLoadBudget chunkLoadBudget = new ChunkLoadBudget();
    private final VillageGenerationReservations reservations = new VillageGenerationReservations();
    private final VillageRoadPathGenerator roadGenerator = new VillageRoadPathGenerator();
    private final VillageFarmSiteSelector farmSiteSelector = new VillageFarmSiteSelector();

    private VillageRoadPathGenerator.PreparedVillageRoads preparedRoads;
    private VillageRoadPathGenerator.PreparedVillageRoads finalPreparedRoads;
    private VillageFarmSiteSelector.VillageSpatialCache spatialCache;
    private BlockPos plannedManagerPos;
    private VillageManagerBlockEntity manager;
    private boolean managerResidentNeeded;
    private int preloadChunkX;
    private int preloadChunkZ;
    private int preloadMaxChunkX;
    private int preloadMaxChunkZ;
    private boolean preloadInitialized;
    private boolean cachesBuilt;
    private boolean pipelineCompletedSuccessfully;

    public VillageGenerationContext(ServerLevel level, UUID villageId, BlockPos structureCenter,
                                    BlockPos bellPos, BoundingBox villageBox,
                                    List<StructurePiece> pieces, boolean abandonedVillage) {
        this.level = level;
        this.villageId = villageId;
        this.structureCenter = structureCenter.immutable();
        this.bellPos = bellPos.immutable();
        this.villageBox = villageBox;
        this.pieces = List.copyOf(pieces);
        this.abandonedVillage = abandonedVillage;
        this.biomeType = VillagePathBlocks.inferBiomeType(level, bellPos, pieces);
    }

    /**
     * One cache pass for every provider. The two-chunk border covers farm candidates
     * and prevents the old per-farm structure-start rescans.
     */
    public boolean buildSharedCachesStep(int chunkLoadsPerTick) {
        if (cachesBuilt) {
            return true;
        }
        if (!preloadInitialized) {
            preloadChunkX = (villageBox.minX() >> 4) - 2;
            preloadMaxChunkX = (villageBox.maxX() >> 4) + 2;
            preloadChunkZ = (villageBox.minZ() >> 4) - 2;
            preloadMaxChunkZ = (villageBox.maxZ() >> 4) + 2;
            preloadInitialized = true;
        }

        int examined = 0;
        int loadedAtStart = chunkLoadBudget.chunksLoaded();
        while (preloadChunkX <= preloadMaxChunkX && examined < 64
                && chunkLoadBudget.chunksLoaded() - loadedAtStart < chunkLoadsPerTick) {
            if (!chunkLoadBudget.ensureLoaded(level, preloadChunkX, preloadChunkZ)) {
                // The shared failsafe was reached. Build a partial snapshot instead
                // of spending later ticks repeatedly attempting the remaining chunks.
                preloadChunkX = preloadMaxChunkX + 1;
                break;
            }
            examined++;
            if (++preloadChunkZ > preloadMaxChunkZ) {
                preloadChunkZ = (villageBox.minZ() >> 4) - 2;
                preloadChunkX++;
            }
        }
        if (preloadChunkX <= preloadMaxChunkX) {
            return false;
        }

        preparedRoads = roadGenerator.prepare(level, pieces);
        spatialCache = farmSiteSelector.buildSpatialCache(level, structureCenter, villageBox, pieces);

        // Abandoned villages retain their vanilla zombie-village presentation. They
        // receive the dedicated vault-ruins building, but not the normal village
        // ledger (manager) or its resident villager.
        if (!abandonedVillage) {
            BlockPos existingManager = registryData().getVMPos(villageId);
            plannedManagerPos = existingManager != null ? existingManager
                    : VillageManagerPlacement.findPlacementNearBell(level, bellPos);
            if (plannedManagerPos != null) {
                reservations.reserveBuilding(MANAGER_RESERVATION, new BoundingBox(
                        plannedManagerPos.getX(), Integer.MIN_VALUE / 2, plannedManagerPos.getZ(),
                        plannedManagerPos.getX(), Integer.MAX_VALUE / 2, plannedManagerPos.getZ()));
            }
        }
        cachesBuilt = true;
        return true;
    }

    /** Places or reconnects the manager only after every provider has planned around it. */
    public void placeManager() {
        if (abandonedVillage) {
            return;
        }
        BlockPos existingPos = registryData().getVMPos(villageId);
        if (existingPos != null
                && level.getBlockEntity(existingPos) instanceof VillageManagerBlockEntity existing) {
            manager = existing;
            return;
        }
        if (plannedManagerPos == null) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Could not reserve a Village Manager position near bell {}",
                    bellPos);
            return;
        }

        level.setBlock(plannedManagerPos, ECAPBlocks.VILLAGE_MANAGER.get().defaultBlockState(), 3);
        BlockEntity blockEntity = level.getBlockEntity(plannedManagerPos);
        if (!(blockEntity instanceof VillageManagerBlockEntity placedManager)) {
            EmeraldCapitalism.LOGGER.error(
                    "[ECAP] Village Manager at {} has no matching block entity",
                    plannedManagerPos);
            return;
        }
        placedManager.setVillageId(villageId);
        registryData().registerVillageManager(villageId, plannedManagerPos);
        manager = placedManager;
        managerResidentNeeded = true;
    }

    /** Deferred until all terrain and path mutations are finished. */
    public void spawnManagerResident() {
        if (abandonedVillage || !managerResidentNeeded || plannedManagerPos == null) {
            return;
        }
        BlockPos spawnPos = VillageManagerPlacement.findSafeVillagerSpawnNear(level, plannedManagerPos);
        if (spawnPos == null) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Could not find a safe manager villager spawn near {}",
                    plannedManagerPos);
            return;
        }
        Villager villager = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(EntityType.VILLAGER, level);
        if (villager == null) {
            return;
        }
        villager.moveTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D,
                level.getRandom().nextFloat() * 360.0F, 0.0F);
        SpawnReasonCompat.finalizeStructure(villager, level, level.getCurrentDifficultyAt(spawnPos), null);
        // finalizeMobSpawn posts FinalizeSpawnEvent, which initializes structure supplies once.
        if (!level.addFreshEntity(villager)) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP] Failed to add manager villager near {}", plannedManagerPos);
        }
    }

    public void linkBank(BlockPos bankPos) {
        if (level.getBlockEntity(bankPos) instanceof BankBlockEntity bank) {
            bank.setVillageId(villageId);
        }
        registryData().registerBankPosition(villageId, bankPos);
        if (manager != null) {
            manager.registerBank(bankPos);
        }
    }

    void markPipelineCompletedSuccessfully() {
        pipelineCompletedSuccessfully = true;
    }

    public boolean pipelineCompletedSuccessfully() {
        return pipelineCompletedSuccessfully;
    }

    public ServerLevel level() { return level; }
    public UUID villageId() { return villageId; }
    public BlockPos structureCenter() { return structureCenter; }
    public BlockPos bellPos() { return bellPos; }
    public BoundingBox villageBox() { return villageBox; }
    public List<StructurePiece> pieces() { return pieces; }
    public boolean abandonedVillage() { return abandonedVillage; }
    public String biomeType() { return biomeType; }
    public ChunkLoadBudget chunkLoadBudget() { return chunkLoadBudget; }
    public VillageGenerationReservations reservations() { return reservations; }
    public VillageRoadPathGenerator roadGenerator() { return roadGenerator; }
    public VillageRoadPathGenerator.PreparedVillageRoads preparedRoads() { return preparedRoads; }
    public VillageRoadPathGenerator.PreparedVillageRoads preparedRoadsWithReservations() {
        return finalPreparedRoads != null ? finalPreparedRoads
                : preparedRoads.withAdditionalBuildings(reservations.buildingBoxes());
    }
    public void freezeFinalRoadObstacles() {
        finalPreparedRoads = preparedRoads.withAdditionalBuildings(reservations.buildingBoxes());
    }
    public VillageFarmSiteSelector farmSiteSelector() { return farmSiteSelector; }
    public BlockPos plannedManagerPos() { return plannedManagerPos; }
    public VillageManagerBlockEntity manager() { return manager; }

    public VillageFarmSiteSelector.VillageSpatialCache spatialCacheWithReservations() {
        return spatialCache.withPipelineReservations(
                reservations.buildingBoxes(), reservations.pathColumns());
    }

    public VillageRegistryData registryData() {
        return VillageRegistryData.get(level);
    }

    public VillageFarmSavedData farmSavedData() {
        ServerLevel overworld = level.getServer().getLevel(Level.OVERWORLD);
        return VillageFarmSavedData.get(overworld != null ? overworld : level);
    }
}
