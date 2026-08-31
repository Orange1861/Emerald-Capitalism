package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPPoiTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Server-side transition rules for village Mayor and Governor roles. */
public final class VillageGovernance {

    private VillageGovernance() {
    }

    public static boolean hasLivingMayor(ServerLevel level, VillageRecord village) {
        return !level.getEntitiesOfClass(Villager.class, village.getBoundingBox(),
                villager -> villager.isAlive()
                        && villager.getVillagerData().getProfession() == ECAPVillagerProfessions.MAYOR.get())
                .isEmpty();
    }

    /**
     * Assigns the village manager job to an eligible adult villager when its
     * current Mayor is gone or has released the manager POI.
     *
     * <p>Bank employees are intentionally excluded because their Bank role is
     * not transferable through this succession path. Other employed villagers
     * may succeed the Mayor; their existing job-site ticket is released before
     * the manager ticket is assigned.</p>
     */
    public static boolean refreshMayorIfVacant(ServerLevel level, VillageRecord village) {
        VillageRegistryData registry = VillageRegistryData.get(level);
        BlockPos managerPos = registry.getVMPos(village.getVillageId());
        if (managerPos == null
                || !level.getBlockState(managerPos).is(ECAPBlocks.VILLAGE_MANAGER.get())
                || level.getPoiManager().getType(managerPos)
                .filter(type -> type.is(ECAPPoiTypes.MAYOR.getKey())).isEmpty()) {
            return false;
        }

        List<Villager> currentMayors = level.getEntitiesOfClass(
                Villager.class,
                village.getBoundingBox(),
                villager -> villager.getVillagerData().getProfession()
                        == ECAPVillagerProfessions.MAYOR.get());
        if (currentMayors.stream().anyMatch(mayor -> holdsManagerJobSite(level, mayor, managerPos))) {
            return false;
        }

        Set<UUID> staleMayorIds = new HashSet<>();
        for (Villager staleMayor : currentMayors) {
            staleMayorIds.add(staleMayor.getUUID());
            releaseCurrentJobSite(level, staleMayor);
            if (!staleMayor.isAlive()) {
                continue;
            }
            staleMayor.setVillagerData(
                    staleMayor.getVillagerData().setProfession(VillagerProfession.NONE));
            staleMayor.refreshBrain(level);
        }

        List<Villager> candidates = level.getEntitiesOfClass(
                        Villager.class,
                        village.getBoundingBox(),
                        villager -> villager.isAlive()
                                && !villager.isBaby()
                                && !staleMayorIds.contains(villager.getUUID())
                                && villager.getVillagerData().getProfession()
                                != ECAPVillagerProfessions.MAYOR.get()
                                && !BankEmployeeLookup.isEmployee(level, villager))
                .stream()
                .sorted(Comparator.comparingDouble((Villager villager) -> villager.distanceToSqr(managerPos.getCenter()))
                        .thenComparing(villager -> villager.getUUID().toString()))
                .toList();

        for (Villager candidate : candidates) {
            if (level.getPoiManager().take(
                    type -> type.is(ECAPPoiTypes.MAYOR.getKey()),
                    (type, pos) -> pos.equals(managerPos),
                    managerPos,
                    1).isEmpty()) {
                return false;
            }

            releaseCurrentJobSite(level, candidate);
            candidate.setVillagerData(
                    candidate.getVillagerData().setProfession(ECAPVillagerProfessions.MAYOR.get()));
            candidate.refreshBrain(level);
            candidate.getBrain().setMemory(
                    MemoryModuleType.JOB_SITE,
                    GlobalPos.of(level.dimension(), managerPos));
            return true;
        }
        return false;
    }

    private static boolean holdsManagerJobSite(ServerLevel level, Villager villager, BlockPos managerPos) {
        return villager.isAlive()
                && level.getPoiManager().getFreeTickets(managerPos) == 0
                && villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(jobSite -> level.dimension().equals(jobSite.dimension()))
                .map(GlobalPos::pos)
                .filter(managerPos::equals)
                .isPresent();
    }

    /** Releases a successor's old POI ticket even when its profession changed first. */
    private static void releaseCurrentJobSite(ServerLevel level, Villager villager) {
        GlobalPos jobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        if (jobSite != null) {
            ServerLevel jobSiteLevel = level.getServer().getLevel(jobSite.dimension());
            if (jobSiteLevel != null
                    && jobSiteLevel.getPoiManager().getType(jobSite.pos()).isPresent()) {
                jobSiteLevel.getPoiManager().release(jobSite.pos());
            }
        }
        villager.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
    }

    @Nullable
    public static BankBlockEntity findBank(ServerLevel level, VillageRecord village) {
        BlockPos bankPos = VillageRegistryData.get(level).getBankPos(village.getVillageId());
        if (bankPos == null) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(bankPos);
        return blockEntity instanceof BankBlockEntity bank ? bank : null;
    }

    /** Returns true when this candidate is internally marked as a contested Governor. */
    public static boolean isContestedGovernor(ServerLevel level, VillageRecord village, UUID playerId) {
        BankBlockEntity bank = findBank(level, village);
        boolean hasBank = VillageRegistryData.get(level).getBankPos(village.getVillageId()) != null;
        return village.isGovernorCandidate(playerId)
                && hasBank
                && (bank == null || !bank.isControlledBy(playerId));
    }

    /**
     * Applies candidate invalidation, Mayor-loss penalties, and promotion. This is
     * intentionally server-only and should be called after governance-affecting events.
     */
    public static boolean refresh(ServerLevel level, VillageRecord village) {
        UUID candidateId = village.getGovernorCandidateId();
        if (candidateId == null) {
            return false;
        }

        var candidate = level.getServer().getPlayerList().getPlayer(candidateId);
        boolean hasMayor = hasLivingMayor(level, village);
        // Offline candidates cannot contribute live villager gossip; retain the
        // persisted candidate until they return, except that a Mayor death is
        // still authoritative while the candidate is offline.
        if (candidate == null && hasMayor) {
            return promoteIfBankQualifies(level, village, candidateId);
        }

        int opinion = candidate == null ? village.getOpinionModifier(candidateId)
                : village.getVillageOpinion(level, candidate);

        int floor = VillageRelationship.candidateFloor(Config.governorCandidateOpinionThreshold);
        if (opinion < floor) {
            return village.clearGovernorCandidate();
        }

        if (!hasMayor) {
            if (opinion > floor) {
                village.adjustOpinionModifier(candidateId, floor - opinion);
            }
            return village.clearGovernorCandidate();
        }

        return promoteIfBankQualifies(level, village, candidateId);
    }

    private static boolean promoteIfBankQualifies(ServerLevel level, VillageRecord village,
                                                   UUID candidateId) {
        BlockPos bankPos = VillageRegistryData.get(level).getBankPos(village.getVillageId());
        BankBlockEntity bank = findBank(level, village);
        boolean bankQualifies = bankPos == null || (bank != null && bank.isControlledBy(candidateId));
        if (bankQualifies) {
            village.setGovernor(candidateId);
            return true;
        }
        return false;
    }
}
