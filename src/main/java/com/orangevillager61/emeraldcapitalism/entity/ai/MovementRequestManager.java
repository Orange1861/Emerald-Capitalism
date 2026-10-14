package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.navigation.MovementIntent;
import com.orangevillager61.emeraldcapitalism.navigation.MovementRequestLedger;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Narrow adapter between mod behaviors and Minecraft's MoveToTargetSink.
 *
 * <p>The manager is server-thread confined. A request records the exact brain
 * objects it installed, and cleanup removes only those objects. Vanilla may
 * consume WALK_TARGET while recording CANT_REACH_WALK_TARGET_SINCE; the
 * failure timestamp is therefore tracked independently of the walk target
 * object and is cleared only when the manager saw the matching sink attempt.</p>
 */
public final class MovementRequestManager {

    public static final int PRIORITY_AMBIENT = 10;
    public static final int PRIORITY_WORK = 40;
    public static final int PRIORITY_ESCAPE = 100;

    private static final Map<Villager, State> STATES = new WeakHashMap<>();

    private MovementRequestManager() {
    }

    public static MovementRequestSubmission submit(Villager villager,
                                                    MovementRequestSpec spec) {
        return PerformanceTimingCounters.measure(
                PerformanceTimingCounters.Operation.MOVEMENT_MANAGER_TIME,
                () -> submitInternal(villager, spec));
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
            return state.unchangedResult;
        }
        MovementRequestLedger.Submission submission = state.ledger.submit(toIntent(spec));
        MovementRequestOutcome outcome = MovementRequestOutcome.valueOf(submission.outcome().name());
        if (!submission.accepted()) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_REJECTION);
            return new MovementRequestSubmission(outcome, null);
        }

        MovementRequestHandle handle = submission.changed()
                ? toHandle(submission.handle())
                : state.activeHandle;
        if (handle == null) {
            handle = toHandle(submission.handle());
        }
        applyAcceptedSubmission(villager, state, spec, submission, outcome, handle);
        return submission.changed() ? new MovementRequestSubmission(outcome, handle)
                : state.unchangedResult;
    }

    /**
     * Reasserts this request after the vanilla sink has ended or lost a route.
     * Equal intent is idempotent; meaningful changes use the same handoff
     * boundary as a fresh submission.
     */
    public static MovementRequestSubmission maintain(Villager villager,
                                                      MovementRequestHandle handle,
                                                      MovementRequestSpec spec) {
        return PerformanceTimingCounters.measure(
                PerformanceTimingCounters.Operation.MOVEMENT_MANAGER_TIME,
                () -> maintainInternal(villager, handle, spec));
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
                    state.unchangedLedgerSubmission,
                    MovementRequestOutcome.UNCHANGED,
                    state.activeHandle);
            return state.unchangedResult;
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
            if (replacingRequest) {
                // The old MoveToTargetSink may still be active for this tick.
                // Leave its live navigation route running so its next tick can
                // adopt the new WALK_TARGET instead of stopping and erasing
                // the higher-priority request's executor state.
                cleanupOwnedExecutor(villager, state, false);
            }
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_SUBMISSION);
            if (outcome == MovementRequestOutcome.REPLACED) {
                PerformanceTimingCounters.recordEvent(
                        PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_REPLAN);
            } else if (outcome == MovementRequestOutcome.PREEMPTED) {
                PerformanceTimingCounters.recordEvent(
                        PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_PREEMPTION);
                recordTermination(MovementRequestTerminationReason.PREEMPTED);
            }
            state.activeHandle = handle;
            state.activeSpec = spec;
            state.requestAcceptedGameTime = currentGameTime(villager);
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
        return PerformanceTimingCounters.measure(
                PerformanceTimingCounters.Operation.MOVEMENT_MANAGER_TIME,
                () -> cancelInternal(villager, handle, reason));
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
        recordTermination(reason);
        cleanupOwnedExecutor(villager, state);
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

    /**
     * Writes and claims a manually-created look tracker for an active request.
     * Cleanup retains any later tracker because it compares the exact object.
     */
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
                                              long gameTime, boolean started) {
        if (!(owner instanceof Villager villager)) {
            return;
        }
        PerformanceTimingCounters.recordEvent(
                PerformanceTimingCounters.Operation.MOVEMENT_PATH_PLAN_ATTEMPT);
        PerformanceTimingCounters.recordEvent(started
                ? PerformanceTimingCounters.Operation.MOVEMENT_PATH_PLAN_SUCCESS
                : PerformanceTimingCounters.Operation.MOVEMENT_PATH_PLAN_FAILURE);
        State state = STATES.get(villager);
        if (state == null || state.activeHandle == null || state.ownedWalkTarget != target) {
            return;
        }
        rememberOwnedFailure(villager, state, target, gameTime);
    }

    /** Captures the exact target before vanilla can consume it during sink startup. */
    public static void observeVanillaSinkStart(Mob owner, long gameTime) {
        if (!(owner instanceof Villager villager)) {
            return;
        }
        State state = STATES.get(villager);
        WalkTarget target = villager.getBrain()
                .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        if (state != null && state.activeHandle != null && target == state.ownedWalkTarget) {
            state.pendingSinkTarget = target;
            state.pendingSinkGameTime = gameTime;
        } else if (state != null) {
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
            rememberOwnedFailure(villager, state, state.pendingSinkTarget, gameTime);
            state.pendingSinkTarget = null;
        }
    }

    /** Releases all transient ownership when an entity or server disappears. */
    public static void release(Villager villager) {
        if (villager != null) {
            State state = STATES.remove(villager);
            if (state != null) {
                MovementRequestHandle handle = state.activeHandle;
                cleanupOwnedExecutor(villager, state);
                state.ledger.clear();
                if (handle != null) {
                    MovementDiagnostics.cleanup(villager, handle);
                }
            }
        }
    }

    public static void clearAll() {
        for (Map.Entry<Villager, State> entry : STATES.entrySet()) {
            State state = entry.getValue();
            MovementRequestHandle handle = state.activeHandle;
            cleanupOwnedExecutor(entry.getKey(), state);
            if (handle != null) {
                MovementDiagnostics.cleanup(entry.getKey(), handle);
            }
        }
        STATES.clear();
    }

    private static boolean writeRequestMemories(Villager villager, State state,
                                                MovementRequestSpec spec) {
        boolean repaired = repairStalePath(villager, state);
        repaired |= repairStaleFailure(villager, state);

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
        if (currentWalkTarget == null
                || !(currentWalkTarget.getTarget() instanceof BlockPosTracker)
                || !spec.destination().equals(currentWalkTarget.getTarget().currentBlockPosition())
                || Float.compare(spec.speedModifier(), currentWalkTarget.getSpeedModifier()) != 0
                || spec.closeEnough() != currentWalkTarget.getCloseEnoughDist()) {
            WalkTarget walkTarget = new WalkTarget(new BlockPosTracker(spec.destination()),
                    spec.speedModifier(), spec.closeEnough());
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, walkTarget);
            state.ownedWalkTarget = walkTarget;
            state.ownedCantReachWalkTargetSince = null;
            repaired = true;
        } else if (state.ownedWalkTarget == null) {
            state.ownedWalkTarget = currentWalkTarget;
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
            if (state.activeHandle != null && state.ownedPath == null
                    && villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null)
                    == state.ownedWalkTarget) {
                state.ownedPath = brainPath;
            }
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

    private static boolean repairStaleFailure(Villager villager, State state) {
        Long currentFailure = villager.getBrain()
                .getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null);
        if (currentFailure == null) {
            return false;
        }
        if (state.ownedCantReachWalkTargetSince != null
                && state.ownedCantReachWalkTargetSince.equals(currentFailure)) {
            villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            state.ownedCantReachWalkTargetSince = null;
            return true;
        }
        if (!state.memorySnapshotInitialized && state.ownedCantReachWalkTargetSince == null) {
            villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            return true;
        }
        return false;
    }

    /**
     * Stops and erases only executor state this request can identify as its
     * own. A consumed WALK_TARGET does not make the matching failure timestamp
     * unowned: the sink mixin records that timestamp before vanilla erases the
     * target.
     */
    private static void cleanupOwnedExecutor(Villager villager, State state) {
        cleanupOwnedExecutor(villager, state, true);
    }

    private static void cleanupOwnedExecutor(Villager villager, State state,
                                             boolean stopNavigation) {
        Path currentNavigationPath = villager.getNavigation().getPath();
        if (stopNavigation && state.ownedPath != null && currentNavigationPath == state.ownedPath) {
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
        if (state.ownedWalkTarget != null && currentWalkTarget == state.ownedWalkTarget) {
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            if (state.ownedCantReachWalkTargetSince != null
                    && state.ownedCantReachWalkTargetSince.equals(currentFailure)) {
                villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            }
        } else if (currentWalkTarget == null
                && state.ownedCantReachWalkTargetSince != null
                && state.ownedCantReachWalkTargetSince.equals(currentFailure)) {
            // MoveToTargetSink erases WALK_TARGET after a failed plan. The
            // exact timestamp is the remaining identity for that failure.
            villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        }

        if (state.ownedLookTarget != null
                && villager.getBrain().getMemory(MemoryModuleType.LOOK_TARGET).orElse(null)
                == state.ownedLookTarget) {
            villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        }
        state.clearExecutorOwnership();
        snapshotMemoryState(villager, state);
    }

    private static void snapshotMemoryState(Villager villager, State state) {
        state.lastObservedBrainPath = villager.getBrain()
                .getMemory(MemoryModuleType.PATH).orElse(null);
        state.lastObservedWalkTarget = villager.getBrain()
                .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        state.lastObservedLookTarget = villager.getBrain()
                .getMemory(MemoryModuleType.LOOK_TARGET).orElse(null);
        state.lastObservedCantReachWalkTargetSince = villager.getBrain()
                .getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null);
        state.memorySnapshotInitialized = true;
    }

    private static long currentGameTime(Villager villager) {
        return villager.level().getGameTime();
    }

    private static void recordTermination(MovementRequestTerminationReason reason) {
        PerformanceTimingCounters.Operation operation = switch (reason) {
            case ARRIVED -> PerformanceTimingCounters.Operation.MOVEMENT_ARRIVAL;
            case ABANDONED -> PerformanceTimingCounters.Operation.MOVEMENT_ABANDONED;
            case FAILED -> PerformanceTimingCounters.Operation.MOVEMENT_FAILURE;
            case PREEMPTED -> PerformanceTimingCounters.Operation.MOVEMENT_PREEMPTED;
        };
        PerformanceTimingCounters.recordEvent(operation);
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

    private static final class State {
        private final MovementRequestLedger ledger = new MovementRequestLedger();
        @Nullable
        private MovementRequestSpec activeSpec;
        @Nullable
        private MovementRequestHandle activeHandle;
        @Nullable
        private MovementRequestLedger.Submission unchangedLedgerSubmission;
        @Nullable
        private MovementRequestSubmission unchangedResult;
        @Nullable
        private Path lastObservedPath;
        @Nullable
        private Path lastObservedBrainPath;
        @Nullable
        private WalkTarget lastObservedWalkTarget;
        @Nullable
        private PositionTracker lastObservedLookTarget;
        @Nullable
        private Long lastObservedCantReachWalkTargetSince;
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
        private boolean memorySnapshotInitialized;
        private long requestAcceptedGameTime = Long.MIN_VALUE;
        private int routeGeneration;

        private void clearExecutorOwnership() {
            activeSpec = null;
            lastObservedPath = null;
            ownedPath = null;
            ownedWalkTarget = null;
            ownedLookTarget = null;
            ownedCantReachWalkTargetSince = null;
            pendingSinkTarget = null;
            pendingSinkGameTime = Long.MIN_VALUE;
            requestAcceptedGameTime = Long.MIN_VALUE;
        }
    }

    private static void rememberOwnedFailure(Villager villager, State state,
                                             WalkTarget target, long gameTime) {
        if (target != state.ownedWalkTarget) {
            return;
        }
        Long currentFailure = villager.getBrain()
                .getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null);
        if (currentFailure != null && currentFailure.longValue() == gameTime) {
            state.ownedCantReachWalkTargetSince = currentFailure;
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
            if (currentPath != null
                    && villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null)
                    == state.ownedWalkTarget) {
                state.ownedPath = currentPath;
            }
            state.routeGeneration++;
            MovementDiagnostics.route(villager, handle, state.routeGeneration, currentPath);
        }
        if (villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null)
                == state.ownedWalkTarget) {
            state.ownedCantReachWalkTargetSince = villager.getBrain()
                    .getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null);
        }
        snapshotMemoryState(villager, state);
    }
}
