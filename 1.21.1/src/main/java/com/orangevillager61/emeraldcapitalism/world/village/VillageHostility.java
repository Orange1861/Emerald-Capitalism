package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Server-side lookup helpers for village hostility-driven entity behavior. */
public final class VillageHostility {

    private VillageHostility() {
    }

    @Nullable
    public static VillageRecord findVillage(ServerLevel level, BlockPos position) {
        return VillageRegistryData.get(level).getVillageFor(position);
    }

    public static boolean isHostilePlayer(ServerLevel level, VillageRecord village, Player player) {
        return player.isAlive()
                && !player.isSpectator()
                && village.getPlayerRelationship(level, player) == VillageRelationship.HOSTILE;
    }

    public static boolean isHostilePlayer(LivingEntity resident, Player player) {
        if (!(resident.level() instanceof ServerLevel level)) {
            return false;
        }
        VillageRecord village = findVillage(level, resident.blockPosition());
        return village != null && isHostilePlayer(level, village, player);
    }

    /** Returns whether an emerald golem should attack the mayor in its village. */
    public static boolean isHostileMayor(LivingEntity resident, Villager mayor) {
        if (!(resident.level() instanceof ServerLevel level)
                || !mayor.isAlive()
                || mayor.getVillagerData().getProfession()
                        != ECAPVillagerProfessions.MAYOR.get()) {
            return false;
        }

        VillageRecord residentVillage = findVillage(level, resident.blockPosition());
        VillageRecord mayorVillage = findVillage(level, mayor.blockPosition());
        return residentVillage != null
                && mayorVillage != null
                && residentVillage.getVillageId().equals(mayorVillage.getVillageId())
                && mayorVillage.getGovernorCandidateId() != null;
    }

    /** Finds the nearest visible hostile player within the resident's village context. */
    @Nullable
    public static ServerPlayer findClosestVisibleHostilePlayer(ServerLevel level,
                                                                VillageRecord village,
                                                                LivingEntity resident,
                                                                double range) {
        AABB searchArea = resident.getBoundingBox().inflate(range);
        List<ServerPlayer> players = level.getEntitiesOfClass(ServerPlayer.class, searchArea,
                player -> isHostilePlayer(level, village, player)
                        && resident.hasLineOfSight(player));
        ServerPlayer closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (ServerPlayer player : players) {
            double distance = resident.distanceToSqr(player);
            if (distance < closestDistance) {
                closest = player;
                closestDistance = distance;
            }
        }
        return closest;
    }
}
