package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Server-side transition rules for the village Governor and Governor-Candidate roles. */
public final class VillageGovernance {

    private VillageGovernance() {
    }

    public static boolean hasLivingMayor(ServerLevel level, VillageRecord village) {
        return !level.getEntitiesOfClass(Villager.class, village.getBoundingBox(),
                villager -> villager.isAlive()
                        && villager.getVillagerData().getProfession() == ECAPVillagerProfessions.MAYOR.get())
                .isEmpty();
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
