package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.UUID;

/** Makes a village Mayor accompany the live player standing as governor candidate. */
public final class MayorFollowGovernorCandidateGoal extends Goal {

    /** Election-time escort must preempt ordinary villager work and wandering. */
    public static final int GOAL_PRIORITY = 1;
    private static final double STOP_DISTANCE_SQ = 9.0D;
    private static final double SPEED = 0.6D;
    private static final int PATH_NODE_MALUS = 1;
    private static final long MIN_REPLAN_INTERVAL_TICKS = 10L;
    private static final double MIN_REPLAN_DISTANCE_SQ = 4.0D;
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
    private final VillagerMovementRequestBinding movement = new VillagerMovementRequestBinding();
    @Nullable
    private BlockPos lastRequestedCandidatePosition;
    @Nullable
    private UUID cachedMovementCandidateId;
    @Nullable
    private String cachedMovementDestinationIdentity;
    private long lastPathRequestTick = Long.MIN_VALUE;

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
        lastRequestedCandidatePosition = null;
        cachedMovementCandidateId = null;
        cachedMovementDestinationIdentity = null;
        lastPathRequestTick = Long.MIN_VALUE;

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
        EmeraldCapitalism.LOGGER.info(
                "[ECAP][MayorFollow] START mayor={} candidate={} mayorPos={} candidatePos={}",
                mayor.getUUID(), resolvedCandidate.getUUID(), mayor.blockPosition(),
                resolvedCandidate.blockPosition());
        if (!tryPathToCandidate(level, true)) {
            EmeraldCapitalism.LOGGER.warn(
                    "[ECAP][MayorFollow] START path failed mayor={} candidate={} mayorPos={} candidatePos={}",
                    mayor.getUUID(), resolvedCandidate.getUUID(), mayor.blockPosition(),
                    resolvedCandidate.blockPosition());
            markPathFailure(level, resolvedCandidate);
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (pathingFailed
                || !(mayor.level() instanceof ServerLevel level)
                || !mayor.isAlive()
                || mayor.isSleeping()
                || mayor.isTrading()
                || mayor.getVillagerData().getProfession() != ECAPVillagerProfessions.MAYOR.get()) {
            return false;
        }

        ServerPlayer resolvedCandidate = resolveCandidate(level, findVillage(level));
        if (resolvedCandidate == null) {
            return false;
        }

        candidate = resolvedCandidate;
        if (mayor.distanceToSqr(resolvedCandidate) <= STOP_DISTANCE_SQ) {
            movement.complete(mayor);
            return false;
        }
        if (mayor.getBrain().hasMemoryValue(
                MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)) {
            markPathFailure(level, resolvedCandidate);
            return false;
        }
        return mayor.distanceToSqr(resolvedCandidate) > STOP_DISTANCE_SQ
                && movement.isActive(mayor);
    }

    @Override
    public void tick() {
        if (!(mayor.level() instanceof ServerLevel level) || candidate == null) {
            pathingFailed = true;
            return;
        }

        mayor.getLookControl().setLookAt(candidate);
        if (mayor.distanceToSqr(candidate) <= STOP_DISTANCE_SQ) {
            movement.complete(mayor);
            return;
        }

        BlockPos candidatePosition = candidate.blockPosition();
        boolean targetMoved = lastRequestedCandidatePosition == null
                || lastRequestedCandidatePosition.distSqr(candidatePosition) >= MIN_REPLAN_DISTANCE_SQ;
        boolean intervalElapsed = level.getGameTime() >= lastPathRequestTick + MIN_REPLAN_INTERVAL_TICKS;
        if (intervalElapsed && (mayor.getNavigation().isDone() || targetMoved)
                && !tryPathToCandidate(level, false)) {
            markPathFailure(level, candidate);
        }
    }

    @Override
    public void stop() {
        EmeraldCapitalism.LOGGER.debug("[ECAP][MayorFollow] STOP mayor={} candidate={} pathingFailed={}",
                mayor.getUUID(), candidate == null ? null : candidate.getUUID(), pathingFailed);
        movement.cancel(mayor, MovementRequestTerminationReason.ABANDONED);
        candidate = null;
        lastRequestedCandidatePosition = null;
        cachedMovementCandidateId = null;
        cachedMovementDestinationIdentity = null;
        lastPathRequestTick = Long.MIN_VALUE;
        pathingFailed = false;
    }

    @Nullable
    private VillageRecord findVillage(ServerLevel level) {
        VillageRecord village = VillageRegistryData.get(level).getVillageFor(mayor.blockPosition());
        return village != null && village.hasMember(mayor.getUUID()) ? village : null;
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

    private boolean tryPathToCandidate(ServerLevel level, boolean initialAdmission) {
        if (candidate == null || candidate.level() != level) {
            return false;
        }

        if (initialAdmission) {
            // The synchronous start contract historically rejects an
            // unreachable candidate before the goal reports itself as
            // continuable. Moving-target retries below deliberately skip this
            // preflight: the vanilla sink is the sole planner after admission
            // and retries are rate-limited by time/distance.
            Path admissionPath = PerformanceTimingCounters.measurePathPlan(
                    PerformanceTimingCounters.Operation.MOVEMENT_HELPER_PATH_PLAN_ATTEMPT,
                    () -> mayor.getNavigation().createPath(
                            candidate.blockPosition(), PATH_NODE_MALUS));
            if (admissionPath != null) {
                PerformanceTimingCounters.recordEvent(
                        PerformanceTimingCounters.Operation.MOVEMENT_HELPER_PATH_OBJECT_CREATED);
            }
            if (admissionPath == null || !admissionPath.canReach()) {
                return false;
            }
        }

        // MoveToTargetSink is the route planner. Do not preflight with
        // createPath here because that would plan the same moving-target route
        // a second time. Failed sink attempts are retried on the bounded
        // interval in tick().
        boolean retry = lastRequestedCandidatePosition != null;
        UUID candidateId = candidate.getUUID();
        if (!candidateId.equals(cachedMovementCandidateId)) {
            cachedMovementCandidateId = candidateId;
            cachedMovementDestinationIdentity = "candidate:" + candidateId;
        }
        boolean submitted = movement.submit(mayor,
                "emeraldcapitalism:mayor_follow_candidate",
                cachedMovementDestinationIdentity,
                candidate.blockPosition(),
                (float) SPEED,
                3,
                "follow-moving-target-v1",
                "ground-ladder-v1",
                MovementRequestManager.PRIORITY_ESCAPE,
                candidate.blockPosition());
        if (submitted) {
            lastRequestedCandidatePosition = candidate.blockPosition().immutable();
            lastPathRequestTick = level.getGameTime();
            if (retry) {
                PerformanceTimingCounters.recordEvent(
                        PerformanceTimingCounters.Operation.MOVEMENT_RETRY);
            }
        }
        return submitted;
    }

    private void markPathFailure(ServerLevel level, ServerPlayer failedCandidate) {
        pathingFailed = true;
        failedCandidateId = failedCandidate.getUUID();
        nextPathAttemptTick = level.getGameTime() + PATH_FAILURE_COOLDOWN;
        movement.cancel(mayor, MovementRequestTerminationReason.FAILED);
    }
}
