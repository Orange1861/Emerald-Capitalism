package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.world.village.JobSiteEntry;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRelationship;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Client-side cache holding the last received village POI snapshot. */
public final class VillagePOIClientCache {

    private static VillagePOIDataPacket data = VillagePOIDataPacket.empty();
    private static long updateTimestamp;

    private VillagePOIClientCache() {
    }

    public static void update(VillagePOIDataPacket packet) {
        data = Objects.requireNonNull(packet, "packet");
        updateTimestamp = System.currentTimeMillis();
    }

    public static void clear() {
        data = VillagePOIDataPacket.empty();
        updateTimestamp = 0;
    }

    @Nullable
    public static UUID getVillageId() {
        return data.hasData() ? data.identity().villageId() : null;
    }

    public static String getVillageName() {
        return data.identity().villageName();
    }

    public static boolean isOperator() {
        return data.identity().isOperator();
    }

    @Nullable
    public static BlockPos getBellPosition() {
        return data.hasData() ? data.identity().bellPosition() : null;
    }

    public static List<VillagerPOIRecord> getRecords() {
        return data.records();
    }

    public static int getTotalBeds() {
        return data.totals().totalBeds();
    }

    public static int getAvailableBeds() {
        return data.totals().availableBeds();
    }

    public static List<JobSiteEntry> getJobSites() {
        return data.totals().jobSites();
    }

    public static List<BlockPos> getBedPositions() {
        return data.totals().bedPositions();
    }

    public static int getFarmlandCount() {
        return data.repair().farmlandCount();
    }

    public static int getDoorCount() {
        return data.repair().doorCount();
    }

    public static int getRepairQueueCount() {
        return data.repair().repairQueueCount();
    }

    public static boolean isFarmlandRepairEnabled() {
        return data.repair().farmlandRepairEnabled();
    }

    public static boolean isDoorRepairEnabled() {
        return data.repair().doorRepairEnabled();
    }

    public static List<BlockPos> getRepairQueuePositions() {
        return data.repair().repairQueuePositions();
    }

    public static int getIronGolemCapacity() {
        return data.entityCounts().ironGolemCapacity();
    }

    public static int getIronGolemsPresent() {
        return data.entityCounts().ironGolemsPresent();
    }

    public static int getEmeraldGolemsPresent() {
        return data.entityCounts().emeraldGolemsPresent();
    }

    public static int getEmeraldGolemCapacity() {
        return data.entityCounts().emeraldGolemCapacity();
    }

    static void updateDynamic(VillagePOIDynamicDataPacket packet) {
        if (!data.hasData() || !packet.hasData()
                || !packet.villageId().equals(data.identity().villageId())) {
            return;
        }

        HashMap<UUID, VillagePOIDynamicDataPacket.VillagerState> statesById = new HashMap<>();
        for (VillagePOIDynamicDataPacket.VillagerState state : packet.villagers()) {
            statesById.put(state.villagerId(), state);
        }
        List<VillagerPOIRecord> updatedRecords = data.records().stream().map(record -> {
            VillagePOIDynamicDataPacket.VillagerState state = statesById.get(record.getVillagerUUID());
            return state == null
                    ? record
                    : record.copyWithDynamicState(state.health(), state.opinionOfPlayer());
        }).toList();
        data = data.withDynamicState(updatedRecords, packet);
        updateTimestamp = System.currentTimeMillis();
    }

    public static int getVillageOpinionOfPlayer() {
        return data.relationshipData().villageOpinionOfPlayer();
    }

    public static VillageRelationship getRelationship() {
        return data.relationshipData().relationship();
    }

    public static boolean canBecomeGovernorCandidate() {
        return data.relationshipData().canBecomeGovernorCandidate();
    }

    @Nullable
    public static AABB getBoundingBox() {
        if (!data.hasData()) {
            return null;
        }
        VillagePOIDataPacket.Bounds bounds = data.bounds();
        return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    public static String getWelcomeMessage() {
        return data.messages().welcomeMessage();
    }

    public static String getBankName() {
        return data.messages().bankName();
    }

    public static long getUpdateTimestamp() {
        return updateTimestamp;
    }

    public static boolean hasData() {
        return data.hasData();
    }

    public static boolean isScanInProgress() {
        return data.status().scanInProgress();
    }

    public static boolean hasCompletedScan() {
        return data.status().hasCompletedScan();
    }
}
