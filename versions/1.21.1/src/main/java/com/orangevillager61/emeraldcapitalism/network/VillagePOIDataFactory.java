package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRelationship;
import com.orangevillager61.emeraldcapitalism.world.village.VillageGovernance;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds POI payloads from village state. */
public final class VillagePOIDataFactory {

    private VillagePOIDataFactory() {}

    /**
     * Resolves the bank name for a village by looking up its village manager and the
     * bank registered with it. Returns an empty string if no bank is linked.
     */
    private static String resolveBankName(VillageRecord village, ServerLevel level) {
        BankBlockEntity bank = resolveBank(village, level);
        return bank == null ? "" : bank.getBankName();
    }

    @Nullable
    static BankBlockEntity resolveBank(VillageRecord village, ServerLevel level) {
        BlockPos vmPos = VillageRegistryData.get(level).getVMPos(village.getVillageId());
        if (vmPos == null) return null;
        if (!(level.getBlockEntity(vmPos) instanceof VillageManagerBlockEntity vm)) return null;
        BlockPos bankPos = vm.getBankPos();
        if (bankPos == null) return null;
        return level.getBlockEntity(bankPos) instanceof BankBlockEntity bank ? bank : null;
    }

    public static VillagePOIDataPacket build(VillageRecord village, ServerLevel level, boolean isOp) {
        return build(village, level, isOp, null);
    }

    /** Builds a viewer-specific snapshot for a ledger/overlay requester. */
    public static VillagePOIDataPacket build(VillageRecord village, ServerLevel level, boolean isOp,
                                             @Nullable ServerPlayer requester) {
        boolean includeDetailedCoordinates = isOp || !Config.redactNonOpVillagePoiDetails;
        Map<UUID, VillagerPOIRecord> snapshot = village.getMembersSnapshot();
        List<VillagerPOIRecord> records = includeDetailedCoordinates
                ? withViewerOpinions(snapshot, village, level, requester)
                : List.of();
        int[] beds = village.countBeds(level);
        int villagerCount = snapshot.size();
        int ironGolemCapacity = villagerCount / 10;
        int ironGolemsPresent = level.getEntitiesOfClass(
                IronGolem.class,
                village.getBoundingBox(),
                golem -> golem.isAlive() && !(golem instanceof EmeraldGolem)
        ).size();
        int emeraldGolemsPresent = level.getEntitiesOfClass(
                EmeraldGolem.class, village.getBoundingBox(), EmeraldGolem::isAlive).size();
        BankBlockEntity bank = resolveBank(village, level);
        int emeraldGolemCapacity = bank == null
                ? 0
                : bank.getExpectedEmeraldGolemCount();
        int villageOpinionOfPlayer = requester == null ? 0 : village.getVillageOpinion(level, requester);
        VillageRelationship relationship = requester == null
                ? VillageRelationship.NEUTRAL
                : village.getPlayerRelationship(level, requester);
        boolean canBecomeGovernorCandidate = requester != null
                && relationship == VillageRelationship.NEUTRAL
                && village.getGovernorCandidateId() == null
                && VillageRelationship.canBecomeGovernorCandidate(
                villageOpinionOfPlayer, Config.governorCandidateOpinionThreshold)
                && VillageGovernance.hasLivingMayor(level, village);
        AABB box = village.getBoundingBox();

        // Look up bank name from the village manager's registered bank
        String bankName = resolveBankName(village, level);

        return new VillagePOIDataPacket(
                true,
                new VillagePOIDataPacket.Status(
                        village.isFullScanInProgress(), village.isCacheInitialized()),
                new VillagePOIDataPacket.Identity(
                        village.getVillageId(), village.getName(), isOp,
                        includeDetailedCoordinates ? village.getBellPosition() : BlockPos.ZERO),
                records,
                new VillagePOIDataPacket.Totals(
                        beds[0], beds[1],
                        includeDetailedCoordinates ? village.getJobSites() : List.of(),
                        includeDetailedCoordinates ? village.getBedPositions() : List.of()),
                new VillagePOIDataPacket.RepairData(
                        village.getFarmlandRegistry().size(), village.getDoorRegistry().size(),
                        village.getRepairQueue().size(), village.isFarmlandRepairEnabled(),
                        village.isDoorRepairEnabled(),
                        includeDetailedCoordinates ? new ArrayList<>(village.getRepairQueue()) : List.of()),
                new VillagePOIDataPacket.EntityCounts(
                        ironGolemCapacity, ironGolemsPresent, emeraldGolemsPresent,
                        emeraldGolemCapacity),
                new VillagePOIDataPacket.RelationshipData(
                        villageOpinionOfPlayer, relationship, canBecomeGovernorCandidate),
                new VillagePOIDataPacket.Bounds(
                        includeDetailedCoordinates ? box.minX : 0,
                        includeDetailedCoordinates ? box.minY : 0,
                        includeDetailedCoordinates ? box.minZ : 0,
                        includeDetailedCoordinates ? box.maxX : 0,
                        includeDetailedCoordinates ? box.maxY : 0,
                        includeDetailedCoordinates ? box.maxZ : 0),
                new VillagePOIDataPacket.Messages(village.getWelcomeMessage(), bankName));
    }

    private static List<VillagerPOIRecord> withViewerOpinions(Map<UUID, VillagerPOIRecord> snapshot,
                                                               VillageRecord village, ServerLevel level,
                                                               @Nullable ServerPlayer requester) {
        if (requester == null || snapshot.isEmpty()) {
            return new ArrayList<>(snapshot.values());
        }

        Map<UUID, Villager> villagersById = new HashMap<>();
        for (Villager villager : level.getEntitiesOfClass(Villager.class, village.getBoundingBox(), Villager::isAlive)) {
            villagersById.put(villager.getUUID(), villager);
        }

        List<VillagerPOIRecord> records = new ArrayList<>(snapshot.size());
        for (VillagerPOIRecord record : snapshot.values()) {
            Villager villager = villagersById.get(record.getVillagerUUID());
            int opinion = villager == null ? 0 : villager.getPlayerReputation(requester);
            records.add(record.copyWithOpinionOfPlayer(opinion));
        }
        return records;
    }
}
