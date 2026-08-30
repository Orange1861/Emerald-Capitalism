package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.UUID;

/** Makes a village Mayor accompany the live player standing as governor candidate. */
public final class MayorFollowGovernorCandidateGoal extends Goal {

    private static final double STOP_DISTANCE_SQ = 9.0D;
    private static final double SPEED = 0.6D;
    private static final int PATH_NODE_MALUS = 1;
    private static final long PATH_FAILURE_COOLDOWN = 100L;

    private final Villager mayor;

    @Nullable
    private ServerPlayer candidate;
    @Nullable
    private final ServerPlayer candidateOverride;
    @Nullable
    private UUID failedCandidateId;
    private long nextPathAttemptTick;
    private boolean pathingFailed;

    public MayorFollowGovernorCandidateGoal(Villager mayor) {
        this(mayor, null);
    }

    /** Constructor for AI tests that need a detached candidate entity. */
    MayorFollowGovernorCandidateGoal(Villager mayor, @Nullable ServerPlayer candidateOverride) {
        this.mayor = mayor;
        this.candidateOverride = candidateOverride;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(mayor.level() instanceof ServerLevel level)
                || mayor.isBaby()
                || mayor.isSleeping()
                || mayor.isTrading()
                || mayor.getVillagerData().getProfession() != ECAPVillagerProfessions.MAYOR.get()) {
            return false;
        }

        VillageRecord village = findVillage(level);
        ServerPlayer resolvedCandidate = resolveCandidate(level, village);
        if (resolvedCandidate == null) {
            return false;
        }

        UUID candidateId = resolvedCandidate.getUUID();
        if (candidateId.equals(failedCandidateId)) {
            if (level.getGameTime() < nextPathAttemptTick) {
                return false;
            }
        } else {
            failedCandidateId = null;
            pathingFailed = false;
        }

        return mayor.distanceToSqr(resolvedCandidate) > STOP_DISTANCE_SQ;
    }

    @Override
    public void start() {
        pathingFailed = false;
        candidate = null;

        if (!(mayor.level() instanceof ServerLevel level)) {
            pathingFailed = true;
            return;
        }

        ServerPlayer resolvedCandidate = resolveCandidate(level, findVillage(level));
        if (resolvedCandidate == null) {
            pathingFailed = true;
            return;
        }

        candidate = resolvedCandidate;
        if (!tryPathToCandidate(level)) {
            markPathFailure(level, resolvedCandidate);
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (pathingFailed
                || !(mayor.level() instanceof ServerLevel level)
                || mayor.isSleeping()
                || mayor.isTrading()) {
            return false;
        }

        ServerPlayer resolvedCandidate = resolveCandidate(level, findVillage(level));
        if (resolvedCandidate == null) {
            return false;
        }

        candidate = resolvedCandidate;
        return true;
    }

    @Override
    public void tick() {
        if (!(mayor.level() instanceof ServerLevel level) || candidate == null) {
            pathingFailed = true;
            return;
        }

        mayor.getLookControl().setLookAt(candidate);
        if (mayor.distanceToSqr(candidate) <= STOP_DISTANCE_SQ) {
            mayor.getNavigation().stop();
            return;
        }

        if (mayor.getNavigation().isDone() && !tryPathToCandidate(level)) {
            markPathFailure(level, candidate);
        }
    }

    @Override
    public void stop() {
        mayor.getNavigation().stop();
        candidate = null;
        pathingFailed = false;
    }

    @Nullable
    private VillageRecord findVillage(ServerLevel level) {
        VillageRegistryData data = VillageRegistryData.get(level);
        VillageRecord containing = data.getVillageFor(mayor.blockPosition());
        if (containing != null) {
            return containing;
        }

        UUID mayorId = mayor.getUUID();
        return data.getVillages().values().stream()
                .filter(village -> village.hasMember(mayorId))
                .findFirst()
                .orElse(null);
    }

    @Nullable
    private ServerPlayer resolveCandidate(ServerLevel level, @Nullable VillageRecord village) {
        if (village == null) {
            return null;
        }

        UUID candidateId = village.getGovernorCandidateId();
        if (candidateId == null) {
            return null;
        }

        ServerPlayer resolved = candidateOverride != null
                ? candidateOverride
                : level.getServer().getPlayerList().getPlayer(candidateId);
        if (resolved == null
                || !resolved.getUUID().equals(candidateId)
                || !resolved.isAlive()
                || resolved.isSpectator()
                || resolved.level() != level) {
            return null;
        }
        return resolved;
    }

    private boolean tryPathToCandidate(ServerLevel level) {
        if (candidate == null || candidate.level() != level) {
            return false;
        }

        Path path = mayor.getNavigation().createPath(candidate.blockPosition(), PATH_NODE_MALUS);
        return path != null && path.canReach()
                && mayor.getNavigation().moveTo(path, SPEED);
    }

    private void markPathFailure(ServerLevel level, ServerPlayer failedCandidate) {
        pathingFailed = true;
        failedCandidateId = failedCandidate.getUUID();
        nextPathAttemptTick = level.getGameTime() + PATH_FAILURE_COOLDOWN;
        mayor.getNavigation().stop();
    }
}
