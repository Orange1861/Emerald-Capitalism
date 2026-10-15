package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.navigation.MovementIntent;
import com.orangevillager61.emeraldcapitalism.navigation.MovementRequestLifecycleCoordinator;
import com.orangevillager61.emeraldcapitalism.navigation.MovementRequestLedger;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Narrow adapter between mod behaviors and Minecraft's MoveToTargetSink.
 *
 * <p>The manager is server-thread confined. A request records the exact brain
 * objects it installed, and cleanup removes only those objects. Replacing a
 * request stops the old route before the new generation is published; a sink
 * callback that was already scheduled is generation-checked by the mixin.</p>
 */
public final class MovementRequestManager {

    public static final int PRIORITY_AMBIENT = 10;
    public static final int PRIORITY_WORK = 40;
    public static final int PRIORITY_ESCAPE = 100;
    /** The old sink is allowed one scheduler turn to observe the handoff. */
    public static final int MAX_HANDOFF_TICKS = 1;
    /** Failed maintained requests are retried at most once per cooldown. */
    public static final long FAILURE_RETRY_COOLDOWN_TICKS = 40L;

    private static final Map<Villager, State> STATES = new WeakHashMap<>();
    private static final MovementRequestLifecycleCoordinator LIFECYCLE =
            new MovementRequestLifecycleCoordinator();
    private static long sinkEpochSequence;
    private static long entitySessionSequence;

    private MovementRequestManager() {
    }

    public static MovementRequestSubmission submit(Villager villager,
                                                    MovementRequestSpec spec) {
        long started = PerformanceTimingCounters.startTiming(
                PerformanceTimingCounters.Operation.MOVEMENT_MANAGER_TIME);
        try {
            return submitInternal(villager, spec);
        } finally {
            PerformanceTimingCounters.finishTiming(
                    PerformanceTimingCounters.Operation.MOVEMENT_MANAGER_TIME, started);
        }
    }

    private static MovementRequestSubmission submitInternal(Villager villager,
                                                            MovementRequestSpec spec) {
        if (villager == null || spec == null) {
            throw new IllegalArgumentException("villager and spec are required");
        }
        State state = STATES.computeIfAbsent(villager, ignored -> new State());
        if (state.activeSpec != null && state.activeSpec.equals(spec)
                && state.activeHandle != null && state.unchangedLedgerSubmission != null) {
            applyAcceptedSubmission(villager, state, spec,
                    state.unchangedLedgerSubmission,
                    MovementRequestOutcome.UNCHANGED,
                    state.activeHandle);
            return Objects.requireNonNull(state.unchangedResult);
        }

        MovementRequestLedger.Submission submission = state.ledger.submit(toIntent(spec));
        MovementRequestOutcome outcome = MovementRequestOutcome.valueOf(submission.outcome().name());
        if (!submission.accepted()) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_REJECTION);
            return new MovementRequestSubmission(outcome, null);
        }

        MovementRequestHandle handle = toHandle(submission.handle());
        applyAcceptedSubmission(villager, state, spec, submission, outcome, handle);
        return new MovementRequestSubmission(outcome, handle);
    }

    /**
     * Reasserts this request after the vanilla sink has ended or lost a route.
     * Equal intent is idempotent; meaningful changes use the same handoff
     * boundary as a fresh submission.
     */
    public static MovementRequestSubmission maintain(Villager villager,
                                                      MovementRequestHandle handle,
                                                      MovementRequestSpec spec) {
        long started = PerformanceTimingCounters.startTiming(
                PerformanceTimingCounters.Operation.MOVEMENT_MANAGER_TIME);
        try {
            return maintainInternal(villager, handle, spec);
        } finally {
            PerformanceTimingCounters.finishTiming(
                    PerformanceTimingCounters.Operation.MOVEMENT_MANAGER_TIME, started);
        }
    }

    private static MovementRequestSubmission maintainInternal(Villager villager,
                                                              MovementRequestHandle handle,
                                                              MovementRequestSpec spec) {
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(spec, "spec");
        State state = STATES.get(villager);
        if (!handle.owner().equals(spec.owner())
                || state == null
                || !state.ledger.owns(handle.owner(), handle.generation())) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_REJECTION);
            return new MovementRequestSubmission(
                    MovementRequestOutcome.REJECTED_STALE_HANDLE, null);
        }

        if (state.activeSpec != null && state.activeSpec.equals(spec)) {
            applyAcceptedSubmission(villager, state, spec,
                    Objects.requireNonNull(state.unchangedLedgerSubmission),
                    MovementRequestOutcome.UNCHANGED,
                    Objects.requireNonNull(state.activeHandle));
            return Objects.requireNonNull(state.unchangedResult);
        }

        MovementRequestLedger.Submission submission = state.ledger.submit(toIntent(spec));
        MovementRequestOutcome outcome = MovementRequestOutcome.valueOf(submission.outcome().name());
        if (!submission.accepted()) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_REJECTION);
            return new MovementRequestSubmission(outcome, null);
        }

        MovementRequestHandle currentHandle = toHandle(submission.handle());
        applyAcceptedSubmission(villager, state, spec, submission, outcome, currentHandle);
        return new MovementRequestSubmission(outcome, currentHandle);
    }

    private static void applyAcceptedSubmission(Villager villager, State state,
                                                MovementRequestSpec spec,
                                                MovementRequestLedger.Submission submission,
                                                MovementRequestOutcome outcome,
                                                MovementRequestHandle handle) {
        if (submission.changed()) {
            boolean replacingRequest = state.activeHandle != null;
            MovementRequestHandle previousHandle = state.activeHandle;
            if (!replacingRequest) {
                state.clearForeignSnapshot();
            }
            state.sinkEpoch = nextSinkEpoch();
            state.pathPlanAttempts = 0;
            state.partialDestinationPathPlans = 0;
            rememberForeignMemories(villager, state);
            if (replacingRequest) {
                finishRequest(villager, state, previousHandle,
                        outcome == MovementRequestOutcome.REPLACED
                                ? MovementRequestTerminationReason.REPLACED
                                : MovementRequestTerminationReason.PREEMPTED);
                // Stop the old executor before publishing the new generation.
                // A sink callback already scheduled for this tick is suppressed
                // only when its captured generation is now stale.
                cleanupOwnedExecutor(villager, state, true, false);
            }
            handoffNavigation(villager, state);
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_SUBMISSION);
            if (outcome == MovementRequestOutcome.REPLACED) {
                PerformanceTimingCounters.recordEvent(
                        PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_REPLAN);
            } else if (outcome == MovementRequestOutcome.PREEMPTED) {
                PerformanceTimingCounters.recordEvent(
                        PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_PREEMPTION);
            }
            state.activeHandle = handle;
            state.activeSpec = spec;
            state.lastPublishedHandle = handle;
            if (!LIFECYCLE.register(state.sessionToken, handle.owner(), handle.generation())) {
                // Normal villager AI must not take down the server when a
                // delayed publication repeats. The ledger remains the
                // authoritative executor owner; the duplicate is ignored by
                // lifecycle accounting.
                com.orangevillager61.emeraldcapitalism.EmeraldCapitalism.LOGGER.warn(
                        "[ECAP][Movement] ignored duplicate lifecycle publication session={} handle={}",
                        state.sessionToken, handle);
            }
            MovementDiagnostics.transition(villager, outcome, handle, spec);
        }

        state.activeSpec = spec;
        state.activeHandle = handle;
        boolean repairedExecutor = writeRequestMemories(villager, state, spec);
        if (repairedExecutor && !submission.changed()) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_RETRY);
        }
        observeRoute(villager, state, handle);
        if (submission.changed()) {
            state.unchangedLedgerSubmission = new MovementRequestLedger.Submission(
                    MovementRequestLedger.Outcome.UNCHANGED, submission.handle());
            state.unchangedResult = new MovementRequestSubmission(
                    MovementRequestOutcome.UNCHANGED, handle);
        }
    }

    /** Cancels only the request represented by {@code handle}. */
    public static boolean cancel(Villager villager, MovementRequestHandle handle) {
        return cancel(villager, handle, MovementRequestTerminationReason.ABANDONED);
    }

    /** Ends a request with the caller's explicit terminal reason. */
    public static boolean cancel(Villager villager, MovementRequestHandle handle,
                                 MovementRequestTerminationReason reason) {
        Objects.requireNonNull(reason, "reason");
        long started = PerformanceTimingCounters.startTiming(
                PerformanceTimingCounters.Operation.MOVEMENT_MANAGER_TIME);
        try {
            return cancelInternal(villager, handle, reason);
        } finally {
            PerformanceTimingCounters.finishTiming(
                    PerformanceTimingCounters.Operation.MOVEMENT_MANAGER_TIME, started);
        }
    }

    public static boolean complete(Villager villager, MovementRequestHandle handle) {
        return cancel(villager, handle, MovementRequestTerminationReason.ARRIVED);
    }

    private static boolean cancelInternal(Villager villager, MovementRequestHandle handle,
                                          MovementRequestTerminationReason reason) {
        if (villager == null || handle == null) {
            return false;
        }
        State state = STATES.get(villager);
        if (state == null || !state.ledger.owns(handle.owner(), handle.generation())) {
            return false;
        }
        state.ledger.clear(handle.owner(), handle.generation());
        state.sinkEpoch = nextSinkEpoch();
        finishRequest(villager, state, handle, reason);
        rememberForeignMemories(villager, state);
        cleanupOwnedExecutor(villager, state, true, true);
        state.activeHandle = null;
        state.activeSpec = null;
        state.unchangedLedgerSubmission = null;
        state.unchangedResult = null;
        MovementDiagnostics.cleanup(villager, handle);
        return true;
    }

    public static boolean isActive(Villager villager, MovementRequestHandle handle) {
        if (villager == null || handle == null) {
            return false;
        }
        State state = STATES.get(villager);
        return state != null && state.ledger.owns(handle.owner(), handle.generation());
    }

    @Nullable
    public static MovementRequestHandle activeHandle(Villager villager) {
        State state = STATES.get(villager);
        return state == null ? null : state.activeHandle;
    }

    /** Returns the active handle only when it belongs to the requested owner. */
    @Nullable
    public static MovementRequestHandle activeHandle(Villager villager, String owner) {
        MovementRequestHandle handle = activeHandle(villager);
        return handle != null && handle.owner().equals(owner) ? handle : null;
    }

    /** Returns the most recently published handle, including a request completed this tick. */
    @Nullable
    public static MovementRequestHandle lastPublishedHandle(Villager villager) {
        State state = STATES.get(villager);
        return state == null ? null : state.lastPublishedHandle;
    }

    /** Returns terminal transitions for this loaded villager session only. */
    public static long terminalCount(Villager villager,
                                     MovementRequestTerminationReason reason) {
        if (villager == null || reason == null) {
            return 0L;
        }
        State state = STATES.get(villager);
        return state == null ? 0L : state.terminalCounts.getOrDefault(reason, 0L);
    }

    /** Returns terminal transitions for one request owner in this villager session. */
    public static long terminalCount(Villager villager, String owner,
                                     MovementRequestTerminationReason reason) {
        if (villager == null || owner == null || reason == null) {
            return 0L;
        }
        State state = STATES.get(villager);
        if (state == null) {
            return 0L;
        }
        EnumMap<MovementRequestTerminationReason, Long> counts =
                state.terminalCountsByOwner.get(owner);
        return counts == null ? 0L : counts.getOrDefault(reason, 0L);
    }

    /** Writes and claims a manually-created look tracker for an active request. */
    public static boolean claimLookTarget(Villager villager, MovementRequestHandle handle,
                                          PositionTracker tracker) {
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(tracker, "tracker");
        State state = STATES.get(villager);
        if (state == null || !state.ledger.owns(handle.owner(), handle.generation())) {
            return false;
        }
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, tracker);
        state.ownedLookTarget = tracker;
        snapshotMemoryState(villager, state);
        return true;
    }

    /** Called by the MoveToTargetSink mixin after its real path-plan attempt. */
    public static void observeVanillaPathPlan(Mob owner, WalkTarget target,
                                              long gameTime, boolean pathCreated,
                                              boolean requestedPathCreated,
                                              boolean destinationReachable) {
        observeVanillaPathPlan(owner, target, gameTime, pathCreated, requestedPathCreated,
                destinationReachable, null);
    }

    /**
     * Observes the sink's result without confusing a useful partial/fallback
     * route with a pathing failure. Vanilla's exact destination flag is not a
     * route-liveness flag: a non-null, unfinished path is still executable.
     */
    public static void observeVanillaPathPlan(Mob owner, WalkTarget target,
                                              long gameTime, boolean pathCreated,
                                              boolean requestedPathCreated,
                                              boolean destinationReachable,
                                              @Nullable Path livePath) {
        if (!(owner instanceof Villager villager)) {
            return;
        }
        State state = STATES.get(villager);
        if (state == null || state.activeHandle == null || state.ownedWalkTarget != target) {
            return;
        }
        // Check ownership before touching global counters. Unmanaged vanilla
        // villagers must not contribute to manager-owned diagnostics.
        PerformanceTimingCounters.recordEvent(
                PerformanceTimingCounters.Operation.MOVEMENT_PATH_PLAN_ATTEMPT);
        boolean countPathDetails = PerformanceTimingCounters.isEventCountingEnabled();
        if (countPathDetails) {
            state.pathPlanAttempts++;
        }
        if (pathCreated) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_PATH_OBJECT_CREATED);
        }
        if (pathCreated) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_VANILLA_PATH_OBJECT_CREATED);
        }
        MovementDiagnostics.pathPlan(villager, state.activeHandle, state.activeSpec,
                livePath, requestedPathCreated, destinationReachable);
        if (destinationReachable) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_DESTINATION_REACHABLE);
            // Vanilla clears CANT_REACH when it obtains a usable path.
            villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            state.ownedCantReachWalkTargetSince = null;
            state.failureRetryNotBefore = Long.MIN_VALUE;
        } else if (pathCreated && livePath != null && !livePath.isDone()) {
            // A partial exact-destination path or a fallback path is useful
            // work. Keep WALK_TARGET and do not arm failure retry cleanup.
            if (countPathDetails) {
                state.partialDestinationPathPlans++;
            }
            villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            state.ownedCantReachWalkTargetSince = null;
            state.failureRetryNotBefore = Long.MIN_VALUE;
        } else {
            if (countPathDetails && requestedPathCreated) {
                state.partialDestinationPathPlans++;
            }
            rememberOwnedFailure(villager, state, target, gameTime);
        }
    }

    /** Captures the exact target before vanilla can consume it during sink startup. */
    public static void observeVanillaSinkStart(Mob owner, long gameTime) {
        if (!(owner instanceof Villager villager)) {
            return;
        }
        State state = STATES.get(villager);
        WalkTarget target = villager.getBrain()
                .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        if (state == null) {
            return;
        }
        if (state.activeHandle != null && target == state.ownedWalkTarget) {
            state.pendingSinkTarget = target;
            state.pendingSinkGameTime = gameTime;
            return;
        }
        if (state.activeHandle != null && state.ownedWalkTarget != null) {
            // A Brain activity can publish a fresh WALK_TARGET between manager
            // ticks. Once a manager request is active, allowing that foreign
            // target to reach MoveToTargetSink is the source of the short-lived
            // job-block circles and ladder-top stalls. Reclaim the semantic
            // manager target at the sink boundary and discard only the foreign
            // executor that was built for it.
            Path brainPath = villager.getBrain().getMemory(MemoryModuleType.PATH).orElse(null);
            Path navigationPath = villager.getNavigation().getPath();
            if (navigationPath != null && !navigationPath.isDone()
                    && navigationPath != state.ownedPath) {
                villager.getNavigation().stop();
            }
            if (brainPath != null && brainPath != state.ownedPath) {
                villager.getBrain().eraseMemory(MemoryModuleType.PATH);
            }
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, state.ownedWalkTarget);
            state.pendingSinkTarget = state.ownedWalkTarget;
            state.pendingSinkGameTime = gameTime;
            return;
        }
        if (state != null) {
            state.pendingSinkTarget = null;
        }
    }

    /** Records the failure timestamp after vanilla's same-tick WALK_TARGET erase. */
    public static void observeVanillaSinkResult(Mob owner, long gameTime) {
        if (!(owner instanceof Villager villager)) {
            return;
        }
        State state = STATES.get(villager);
        if (state != null && state.pendingSinkTarget != null
                && state.pendingSinkGameTime == gameTime) {
            Long failure = villager.getBrain()
                    .getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null);
            if (failure != null) {
                rememberOwnedFailure(villager, state, state.pendingSinkTarget, gameTime);
            } else {
                state.ownedCantReachWalkTargetSince = null;
                state.failureRetryNotBefore = Long.MIN_VALUE;
            }
            state.pendingSinkTarget = null;
        }
    }

    /** Captures the ownership epoch observed before an individual sink starts. */
    public static long captureVanillaSinkEpoch(Mob owner) {
        if (!(owner instanceof Villager villager)) {
            return Long.MIN_VALUE;
        }
        State state = STATES.get(villager);
        return state == null ? 0L : state.sinkEpoch;
    }

    /** Returns true only when manager ownership changed after this sink captured its epoch. */
    public static boolean isStaleVanillaSink(Mob owner, long capturedEpoch,
                                             @Nullable WalkTarget capturedTarget) {
        if (!(owner instanceof Villager villager) || capturedEpoch == Long.MIN_VALUE) {
            return false;
        }
        State state = STATES.get(villager);
        if (state == null || state.sinkEpoch == capturedEpoch) {
            return false;
        }

        WalkTarget currentWalkTarget = villager.getBrain()
                .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        Path brainPath = villager.getBrain().getMemory(MemoryModuleType.PATH).orElse(null);
        Path navigationPath = villager.getNavigation().getPath();
        if (state.activeHandle != null) {
            return currentWalkTarget == state.ownedWalkTarget
                    || (state.ownedPath != null
                    && (brainPath == state.ownedPath || navigationPath == state.ownedPath));
        }

        // A canceled request restores the displaced vanilla objects. Protect
        // that exact route from the already-captured sink's delayed stop, but
        // allow unrelated vanilla sinks to perform their normal cleanup.
        boolean restoredForeignTarget = currentWalkTarget != null
                && currentWalkTarget == state.foreignWalkTarget;
        boolean capturedRetiredTarget = capturedTarget != null
                && currentWalkTarget == capturedTarget
                && state.retiredOwnedWalkTargets.contains(capturedTarget);
        // A sink entered through a direct start can miss the target-capture
        // callback. Its stale epoch is still sufficient to identify delayed
        // cleanup when the manager has restored the exact foreign target.
        if (restoredForeignTarget || capturedRetiredTarget) {
            return true;
        }
        // Once a manager handoff has captured a foreign snapshot, any
        // non-empty WALK_TARGET that survives cancellation belongs to a
        // later owner. A stale sink must not erase that later state merely
        // because the target object was replaced while it was delayed.
        return state.foreignSnapshotInitialized && currentWalkTarget != null;
    }

    /** Records the exact route object installed by MoveToTargetSink.start. */
    public static void observeVanillaSinkStarted(Mob owner, WalkTarget target,
                                                 @Nullable Path path) {
        if (!(owner instanceof Villager villager)) {
            return;
        }
        State state = STATES.get(villager);
        if (state == null || state.activeHandle == null || state.ownedWalkTarget != target) {
            return;
        }
        state.ownedPath = path;
    }

    /** Test-visible per-request attempt count, unaffected by other GameTests. */
    public static int pathPlanAttempts(Villager villager, MovementRequestHandle handle) {
        State state = STATES.get(villager);
        return state != null && state.ledger.owns(handle.owner(), handle.generation())
                ? state.pathPlanAttempts : 0;
    }

    /** Test-visible count of requested-destination paths that existed but could not reach it. */
    public static int partialDestinationPathPlans(Villager villager,
                                                  MovementRequestHandle handle) {
        State state = STATES.get(villager);
        return state != null && state.ledger.owns(handle.owner(), handle.generation())
                ? state.partialDestinationPathPlans : 0;
    }

    /** Test-visible retry deadline for the active generation, or MIN_VALUE when ungated. */
    public static long failureRetryNotBefore(Villager villager, MovementRequestHandle handle) {
        State state = STATES.get(villager);
        return state != null && state.ledger.owns(handle.owner(), handle.generation())
                ? state.failureRetryNotBefore : Long.MIN_VALUE;
    }

    @Nullable
    public static WalkTarget ownedWalkTarget(Villager villager, MovementRequestHandle handle) {
        State state = STATES.get(villager);
        return state != null && state.ledger.owns(handle.owner(), handle.generation())
                ? state.ownedWalkTarget : null;
    }

    /** Releases all transient ownership when an entity disappears. */
    public static void release(Villager villager) {
        if (villager == null) {
            return;
        }
        State state = STATES.remove(villager);
        if (state == null) {
            return;
        }
        MovementRequestHandle handle = state.activeHandle;
        if (handle != null) {
            state.sinkEpoch = nextSinkEpoch();
            finishRequest(villager, state, handle, MovementRequestTerminationReason.UNLOADED);
        }
        rememberForeignMemories(villager, state);
        cleanupOwnedExecutor(villager, state, true, true);
        state.ledger.clear();
        if (handle != null) {
            MovementDiagnostics.cleanup(villager, handle);
        }
    }

    /** Session reset used at server start; no request was necessarily terminal. */
    public static void clearAll() {
        clearAll(null);
    }

    /** Clears all requests, optionally recording server-shutdown terminals. */
    public static void clearAll(@Nullable MovementRequestTerminationReason reason) {
        for (Map.Entry<Villager, State> entry : STATES.entrySet()) {
            State state = entry.getValue();
            MovementRequestHandle handle = state.activeHandle;
            if (handle != null && reason != null) {
                state.sinkEpoch = nextSinkEpoch();
                finishRequest(entry.getKey(), state, handle, reason);
            }
            rememberForeignMemories(entry.getKey(), state);
            // Shutdown must not reactivate a displaced vanilla route while the
            // server is tearing down chunks. Ordinary session resets still
            // restore the foreign executor normally.
            cleanupOwnedExecutor(entry.getKey(), state, true, reason == null);
            if (handle != null) {
                MovementDiagnostics.cleanup(entry.getKey(), handle);
            }
        }
        STATES.clear();
        LIFECYCLE.clear();
    }

    /** Test-visible count used to verify the shutdown lifecycle accounts each live request once. */
    public static int activeRequestCount() {
        return LIFECYCLE.activeCount();
    }

    private static boolean writeRequestMemories(Villager villager, State state,
                                                MovementRequestSpec spec) {
        rememberForeignMemories(villager, state);
        boolean repairedPath = repairStalePath(villager, state);
        boolean repaired = repairedPath;
        long gameTime = currentGameTime(villager);
        Long currentFailure = villager.getBrain()
                .getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null);
        if (state.ownedCantReachWalkTargetSince != null
                && currentFailure != null
                && !state.ownedCantReachWalkTargetSince.equals(currentFailure)) {
            // An erased failure or a later foreign failure is no longer this
            // generation's retry gate. Never erase the later foreign value.
            state.ownedCantReachWalkTargetSince = null;
            state.failureRetryNotBefore = Long.MIN_VALUE;
        }
        if (state.ownedCantReachWalkTargetSince != null && currentFailure == null
                && gameTime < state.failureRetryNotBefore) {
            // A later vanilla cleanup can erase the failure memory after the
            // sink consumed WALK_TARGET. Keep the exact owned timestamp visible
            // until the retry deadline, while leaving foreign timestamps alone.
            currentFailure = state.ownedCantReachWalkTargetSince;
            villager.getBrain().setMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
                    currentFailure);
            repaired = true;
        }
        if (state.ownedCantReachWalkTargetSince != null
                && state.ownedCantReachWalkTargetSince.equals(currentFailure)) {
            if (gameTime < state.failureRetryNotBefore) {
                // MoveToTargetSink consumed WALK_TARGET on failure. Keep the
                // failure observable and do not restore the target every tick.
                WalkTarget currentWalkTarget = villager.getBrain()
                        .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
                if (currentWalkTarget == state.ownedWalkTarget) {
                    villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                    repaired = true;
                }
                snapshotMemoryState(villager, state);
                return repaired;
            }
            villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            currentFailure = null;
            state.ownedCantReachWalkTargetSince = null;
            state.failureRetryNotBefore = Long.MIN_VALUE;
            repaired = true;
        }

        if (state.ownedCantReachWalkTargetSince != null
                && currentFailure == null
                && gameTime >= state.failureRetryNotBefore) {
            state.ownedCantReachWalkTargetSince = null;
            state.failureRetryNotBefore = Long.MIN_VALUE;
        }

        if (currentFailure != null && state.ownedCantReachWalkTargetSince == null) {
            // A failure timestamp captured before this request, or written by
            // another behavior during it, must not gate this request's sink.
            // Keep its value in the foreign snapshot so terminal cleanup can
            // restore it without erasing a later writer's memory.
            state.foreignFailureTime = currentFailure;
            villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            currentFailure = null;
            repaired = true;
        }

        if (spec.hasLookTarget()) {
            PositionTracker currentLookTarget = villager.getBrain()
                    .getMemory(MemoryModuleType.LOOK_TARGET).orElse(null);
            if (!(currentLookTarget instanceof BlockPosTracker)
                    || !spec.lookTarget().equals(currentLookTarget.currentBlockPosition())) {
                BlockPosTracker tracker = new BlockPosTracker(spec.lookTarget());
                villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, tracker);
                state.ownedLookTarget = tracker;
                repaired = true;
            }
        }

        WalkTarget currentWalkTarget = villager.getBrain()
                .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        if (currentWalkTarget == null || currentWalkTarget != state.ownedWalkTarget
                || !(currentWalkTarget.getTarget() instanceof BlockPosTracker)
                || !spec.destination().equals(currentWalkTarget.getTarget().currentBlockPosition())
                || Float.compare(spec.speedModifier(), currentWalkTarget.getSpeedModifier()) != 0
                || spec.closeEnough() != currentWalkTarget.getCloseEnoughDist()) {
            WalkTarget walkTarget = new WalkTarget(new BlockPosTracker(spec.destination()),
                    spec.speedModifier(), spec.closeEnough());
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, walkTarget);
            state.ownedWalkTarget = walkTarget;
            repaired = true;
        }
        snapshotMemoryState(villager, state);
        return repaired;
    }

    private static boolean repairStalePath(Villager villager, State state) {
        Path brainPath = villager.getBrain().getMemory(MemoryModuleType.PATH).orElse(null);
        if (brainPath == null) {
            return false;
        }
        Path navigationPath = villager.getNavigation().getPath();
        boolean liveMatchingPath = navigationPath != null
                && !navigationPath.isDone()
                && navigationPath == brainPath;
        if (liveMatchingPath) {
            return false;
        }

        boolean changedSinceObservation = state.memorySnapshotInitialized
                && brainPath != state.lastObservedBrainPath;
        if (state.ownedPath != brainPath && changedSinceObservation) {
            return false;
        }
        villager.getBrain().eraseMemory(MemoryModuleType.PATH);
        if (state.ownedPath == brainPath) {
            state.ownedPath = null;
        }
        return true;
    }

    /** Stops and erases only executor objects identified as this generation's. */
    private static void cleanupOwnedExecutor(Villager villager, State state) {
        cleanupOwnedExecutor(villager, state, true, false);
    }

    private static void cleanupOwnedExecutor(Villager villager, State state,
                                             boolean stopNavigation) {
        cleanupOwnedExecutor(villager, state, stopNavigation, false);
    }

    private static void cleanupOwnedExecutor(Villager villager, State state,
                                             boolean stopNavigation,
                                             boolean restoreForeignMemories) {
        Path currentNavigationPath = villager.getNavigation().getPath();
        if (stopNavigation && state.ownedPath != null
                && currentNavigationPath == state.ownedPath) {
            villager.getNavigation().stop();
        }
        if (state.ownedPath != null
                && villager.getBrain().getMemory(MemoryModuleType.PATH).orElse(null)
                == state.ownedPath) {
            villager.getBrain().eraseMemory(MemoryModuleType.PATH);
        }

        WalkTarget currentWalkTarget = villager.getBrain()
                .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        Long currentFailure = villager.getBrain()
                .getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null);
        boolean ownsWalkTarget = state.ownedWalkTarget != null
                && currentWalkTarget == state.ownedWalkTarget;
        if (ownsWalkTarget) {
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
        if (state.ownedWalkTarget != null) {
            state.retiredOwnedWalkTargets.add(state.ownedWalkTarget);
        }
        boolean erasingOwnedFailure = currentFailure != null
                && ((state.ownedCantReachWalkTargetSince != null
                && state.ownedCantReachWalkTargetSince.equals(currentFailure))
                || (state.ownedCantReachWalkTargetSince == null
                && state.foreignFailureTime == null));
        if (erasingOwnedFailure) {
            // MoveToTargetSink erases WALK_TARGET after a failed plan. The
            // exact timestamp is the remaining identity for that failure.
            villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            if (state.foreignFailureTime != null
                    && state.foreignFailureTime.equals(currentFailure)) {
                state.foreignFailureTime = null;
            }
        }

        if (state.ownedLookTarget != null
                && villager.getBrain().getMemory(MemoryModuleType.LOOK_TARGET).orElse(null)
                == state.ownedLookTarget) {
            villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        }
        state.clearExecutorOwnership();
        if (restoreForeignMemories) {
            restoreForeignMemories(villager, state);
        }
        snapshotMemoryState(villager, state);
    }

    private static void handoffNavigation(Villager villager, State state) {
        Path navigationPath = villager.getNavigation().getPath();
        boolean liveNavigation = navigationPath != null && !navigationPath.isDone();
        if (liveNavigation && navigationPath != state.ownedPath) {
            state.foreignNavigationPath = navigationPath;
            WalkTarget foreignTarget = state.foreignWalkTarget;
            state.foreignNavigationSpeed = foreignTarget == null
                    ? 1.0D : foreignTarget.getSpeedModifier();
        }
        if (liveNavigation) {
            villager.getNavigation().stop();
        }
        // MoveToTargetSink requires PATH to be absent before it plans. Preserve
        // the exact displaced object for cancellation, while invalidating only
        // the route whose ownership was explicitly handed to this request.
        if (villager.getBrain().hasMemoryValue(MemoryModuleType.PATH)) {
            villager.getBrain().eraseMemory(MemoryModuleType.PATH);
        }
        state.ownedPath = null;
        state.lastObservedPath = null;
    }

    private static void rememberForeignMemories(Villager villager, State state) {
        WalkTarget walkTarget = villager.getBrain()
                .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        Path path = villager.getBrain().getMemory(MemoryModuleType.PATH).orElse(null);
        PositionTracker lookTarget = villager.getBrain()
                .getMemory(MemoryModuleType.LOOK_TARGET).orElse(null);
        Long failure = villager.getBrain()
                .getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null);

        if (!state.foreignSnapshotInitialized) {
            state.foreignWalkTarget = walkTarget;
            state.foreignPath = path;
            state.foreignLookTarget = lookTarget;
            state.foreignFailureTime = failure;
            state.foreignSnapshotInitialized = true;
            return;
        }
        if (walkTarget != null && walkTarget != state.ownedWalkTarget) {
            state.foreignWalkTarget = walkTarget;
        }
        if (path != null && path != state.ownedPath) {
            state.foreignPath = path;
        }
        if (lookTarget != null && lookTarget != state.ownedLookTarget) {
            state.foreignLookTarget = lookTarget;
        }
        if (failure != null && !failure.equals(state.ownedCantReachWalkTargetSince)) {
            state.foreignFailureTime = failure;
        }
    }

    private static void restoreForeignMemories(Villager villager, State state) {
        if (!state.foreignSnapshotInitialized) {
            return;
        }
        if (!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                && state.foreignWalkTarget != null) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, state.foreignWalkTarget);
        }
        boolean hasSemanticRoute = state.foreignWalkTarget != null;
        if (!hasSemanticRoute
                && !villager.getBrain().hasMemoryValue(MemoryModuleType.PATH)
                && state.foreignPath != null) {
            villager.getBrain().setMemory(MemoryModuleType.PATH, state.foreignPath);
        }
        if (!villager.getBrain().hasMemoryValue(MemoryModuleType.LOOK_TARGET)
                && state.foreignLookTarget != null) {
            villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, state.foreignLookTarget);
        }
        if (!villager.getBrain().hasMemoryValue(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
                && state.foreignFailureTime != null) {
            villager.getBrain().setMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
                    state.foreignFailureTime);
        }
        Path navigationPath = state.foreignNavigationPath;
        Path currentNavigationPath = villager.getNavigation().getPath();
        if (!hasSemanticRoute && navigationPath != null && !navigationPath.isDone()
                && (currentNavigationPath == null || currentNavigationPath.isDone())) {
            // Target-backed routes are replanned by the vanilla sink instead
            // of restoring a mutable Path object that may have become stale.
            villager.getNavigation().moveTo(navigationPath, state.foreignNavigationSpeed);
        }
    }

    private static void snapshotMemoryState(Villager villager, State state) {
        state.lastObservedBrainPath = villager.getBrain()
                .getMemory(MemoryModuleType.PATH).orElse(null);
        state.memorySnapshotInitialized = true;
    }

    private static long currentGameTime(Villager villager) {
        return villager.level().getGameTime();
    }

    private static void finishRequest(Villager villager, State state,
                                      MovementRequestHandle handle,
                                      MovementRequestTerminationReason reason) {
        if (handle == null || !LIFECYCLE.terminate(new MovementRequestLifecycleCoordinator.RequestKey(
                state.sessionToken, handle.owner(), handle.generation()),
                lifecycleReason(reason))) {
            return;
        }
        state.terminalCounts.merge(reason, 1L, Long::sum);
        state.terminalCountsByOwner
                .computeIfAbsent(handle.owner(), ignored -> new EnumMap<>(MovementRequestTerminationReason.class))
                .merge(reason, 1L, Long::sum);
        recordTermination(reason);
        MovementDiagnostics.terminal(villager, handle, reason);
    }

    private static MovementRequestLifecycleCoordinator.TerminalReason lifecycleReason(
            MovementRequestTerminationReason reason) {
        return switch (reason) {
            case ARRIVED -> MovementRequestLifecycleCoordinator.TerminalReason.ARRIVED;
            case ABANDONED -> MovementRequestLifecycleCoordinator.TerminalReason.ABANDONED;
            case FAILED -> MovementRequestLifecycleCoordinator.TerminalReason.FAILED;
            case PREEMPTED -> MovementRequestLifecycleCoordinator.TerminalReason.PREEMPTED;
            case REPLACED -> MovementRequestLifecycleCoordinator.TerminalReason.REPLACED;
            case UNLOADED -> MovementRequestLifecycleCoordinator.TerminalReason.UNLOADED;
            case SERVER_SHUTDOWN -> MovementRequestLifecycleCoordinator.TerminalReason.SERVER_SHUTDOWN;
        };
    }

    private static void recordTermination(MovementRequestTerminationReason reason) {
        PerformanceTimingCounters.Operation operation = switch (reason) {
            case ARRIVED -> PerformanceTimingCounters.Operation.MOVEMENT_ARRIVAL;
            case ABANDONED -> PerformanceTimingCounters.Operation.MOVEMENT_ABANDONED;
            case FAILED -> PerformanceTimingCounters.Operation.MOVEMENT_FAILURE;
            case PREEMPTED -> PerformanceTimingCounters.Operation.MOVEMENT_PREEMPTED;
            case REPLACED -> PerformanceTimingCounters.Operation.MOVEMENT_REPLACED;
            case UNLOADED -> PerformanceTimingCounters.Operation.MOVEMENT_UNLOADED;
            case SERVER_SHUTDOWN -> PerformanceTimingCounters.Operation.MOVEMENT_SHUTDOWN;
        };
        PerformanceTimingCounters.recordEvent(operation);
    }

    private static long nextSinkEpoch() {
        return ++sinkEpochSequence;
    }

    private static MovementRequestHandle toHandle(MovementRequestLedger.Handle handle) {
        return new MovementRequestHandle(handle.owner(), handle.generation());
    }

    private static MovementIntent toIntent(MovementRequestSpec spec) {
        return new MovementIntent(
                spec.owner(),
                spec.destinationIdentity(),
                spec.destination().asLong(),
                Float.floatToIntBits(spec.speedModifier()),
                spec.closeEnough(),
                spec.arrivalPolicy(),
                spec.traversalPolicy(),
                spec.priority(),
                spec.hasLookTarget(),
                spec.hasLookTarget() ? spec.lookTarget().asLong() : 0L);
    }

    private static long nextEntitySessionToken() {
        if (++entitySessionSequence <= 0L) {
            entitySessionSequence = 1L;
        }
        return entitySessionSequence;
    }

    private static final class State {
        private final long sessionToken = nextEntitySessionToken();
        private final MovementRequestLedger ledger = new MovementRequestLedger();
        private final EnumMap<MovementRequestTerminationReason, Long> terminalCounts =
                new EnumMap<>(MovementRequestTerminationReason.class);
        private final Map<String, EnumMap<MovementRequestTerminationReason, Long>> terminalCountsByOwner =
                new java.util.HashMap<>();
        @Nullable
        private MovementRequestSpec activeSpec;
        @Nullable
        private MovementRequestHandle activeHandle;
        @Nullable
        private MovementRequestHandle lastPublishedHandle;
        @Nullable
        private MovementRequestLedger.Submission unchangedLedgerSubmission;
        @Nullable
        private MovementRequestSubmission unchangedResult;
        @Nullable
        private Path lastObservedPath;
        @Nullable
        private Path lastObservedBrainPath;
        @Nullable
        private Path ownedPath;
        @Nullable
        private WalkTarget ownedWalkTarget;
        @Nullable
        private PositionTracker ownedLookTarget;
        @Nullable
        private Long ownedCantReachWalkTargetSince;
        @Nullable
        private WalkTarget pendingSinkTarget;
        private long pendingSinkGameTime = Long.MIN_VALUE;
        private long failureRetryNotBefore = Long.MIN_VALUE;
        private boolean memorySnapshotInitialized;
        private boolean foreignSnapshotInitialized;
        @Nullable
        private WalkTarget foreignWalkTarget;
        @Nullable
        private Path foreignPath;
        @Nullable
        private Path foreignNavigationPath;
        private double foreignNavigationSpeed = 1.0D;
        @Nullable
        private PositionTracker foreignLookTarget;
        @Nullable
        private Long foreignFailureTime;
        private long sinkEpoch;
        private final Set<WalkTarget> retiredOwnedWalkTargets =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private int routeGeneration;
        private int pathPlanAttempts;
        private int partialDestinationPathPlans;

        private void clearExecutorOwnership() {
            lastObservedPath = null;
            ownedPath = null;
            ownedWalkTarget = null;
            ownedLookTarget = null;
            ownedCantReachWalkTargetSince = null;
            pendingSinkTarget = null;
            pendingSinkGameTime = Long.MIN_VALUE;
            failureRetryNotBefore = Long.MIN_VALUE;
        }

        private void clearForeignSnapshot() {
            foreignSnapshotInitialized = false;
            foreignWalkTarget = null;
            foreignPath = null;
            foreignNavigationPath = null;
            foreignNavigationSpeed = 1.0D;
            foreignLookTarget = null;
            foreignFailureTime = null;
        }
    }

    private static void rememberOwnedFailure(Villager villager, State state,
                                             WalkTarget target, long gameTime) {
        if (target != state.ownedWalkTarget) {
            return;
        }
        Long currentFailure = villager.getBrain()
                .getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null);
        if (currentFailure != null) {
            state.ownedCantReachWalkTargetSince = currentFailure;
            state.failureRetryNotBefore = currentFailure + FAILURE_RETRY_COOLDOWN_TICKS;
            if (currentFailure.equals(state.foreignFailureTime)) {
                state.foreignFailureTime = null;
            }
            if (villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null)
                    == target) {
                villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            }
        }
    }

    private static void observeRoute(Villager villager, State state,
                                     MovementRequestHandle handle) {
        if (state == null || handle == null) {
            return;
        }
        Path currentPath = villager.getNavigation().getPath();
        if (currentPath != state.lastObservedPath) {
            state.lastObservedPath = currentPath;
            state.routeGeneration++;
            MovementDiagnostics.route(villager, handle, state.routeGeneration, currentPath);
        }
        snapshotMemoryState(villager, state);
    }
}
