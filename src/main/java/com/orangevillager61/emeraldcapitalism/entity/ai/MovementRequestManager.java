package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.orangevillager61.emeraldcapitalism.navigation.MovementIntent;
import com.orangevillager61.emeraldcapitalism.navigation.MovementRequestLedger;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
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
 * <p>The manager is server-thread confined: all callers enter from entity or
 * server lifecycle callbacks. Keeping that map local to the server thread
 * avoids a JVM-wide monitor on every active villager tick. Memory cleanup is
 * identity-checked, so a later brain writer cannot be erased by stale cleanup.</p>
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
                || !state.ledger.owns(toLedgerHandle(handle))) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_REJECTION);
            return new MovementRequestSubmission(
                    MovementRequestOutcome.REJECTED_STALE_HANDLE, null);
        }

        MovementRequestLedger.Submission submission = state.activeSpec != null
                && state.activeSpec.equals(spec)
                ? new MovementRequestLedger.Submission(
                        MovementRequestLedger.Outcome.UNCHANGED,
                        state.ledger.activeHandle())
                : state.ledger.submit(toIntent(spec));
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
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_SUBMISSION);
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_PATH_PLAN_ATTEMPT);
            if (outcome == MovementRequestOutcome.REPLACED) {
                PerformanceTimingCounters.recordEvent(
                        PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_REPLAN);
            } else if (outcome == MovementRequestOutcome.PREEMPTED) {
                PerformanceTimingCounters.recordEvent(
                        PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_PREEMPTION);
            }
            cleanupOwnedExecutor(villager, state);
            MovementDiagnostics.transition(villager, outcome, handle, spec);
        }
        state.activeSpec = spec;
        boolean repairedExecutor = writeRequestMemories(villager, state, spec);
        if (repairedExecutor && !submission.changed()) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_RETRY);
        }
        observeRoute(villager, state, handle);
    }

    /** Clears only the request represented by {@code handle}. */
    public static boolean cancel(Villager villager, MovementRequestHandle handle,
                                 boolean clearLookTarget) {
        return PerformanceTimingCounters.measure(
                PerformanceTimingCounters.Operation.MOVEMENT_MANAGER_TIME,
                () -> cancelInternal(villager, handle, clearLookTarget));
    }

    private static boolean cancelInternal(Villager villager, MovementRequestHandle handle,
                                          boolean clearLookTarget) {
        State state = STATES.get(villager);
        if (state == null || !state.ledger.owns(toLedgerHandle(handle))) {
            return false;
        }
        if (state.activeSpec != null && reachedDestination(villager, state.activeSpec)) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_ARRIVAL);
        } else {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_ABANDONED);
        }
        state.ledger.clear(toLedgerHandle(handle));
        cleanupOwnedExecutor(villager, state);
        state.activeSpec = null;
        MovementDiagnostics.cleanup(villager, handle);
        return true;
    }

    public static boolean isActive(Villager villager, MovementRequestHandle handle) {
        State state = STATES.get(villager);
        return state != null && state.ledger.owns(toLedgerHandle(handle));
    }

    @Nullable
    public static MovementRequestHandle activeHandle(Villager villager) {
        State state = STATES.get(villager);
        if (state == null || state.ledger.activeHandle() == null) {
            return null;
        }
        return toHandle(state.ledger.activeHandle());
    }

    /**
     * Writes and claims a manually-created look tracker for an active request.
     * The exact tracker instance is retained so cleanup can preserve a later
     * brain writer, including an EntityTracker supplied by a behavior.
     */
    public static boolean claimLookTarget(Villager villager, MovementRequestHandle handle,
                                          PositionTracker tracker) {
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(tracker, "tracker");
        State state = STATES.get(villager);
        if (state == null || !state.ledger.owns(toLedgerHandle(handle))) {
            return false;
        }
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, tracker);
        state.ownedLookTarget = tracker;
        return true;
    }

    /** Releases all transient ownership when an entity or server disappears. */
    public static void release(Villager villager) {
        if (villager != null) {
            State state = STATES.remove(villager);
            if (state != null) {
                MovementRequestLedger.Handle activeHandle = state.ledger.activeHandle();
                cleanupOwnedExecutor(villager, state);
                state.ledger.clear();
                if (activeHandle != null) {
                    MovementDiagnostics.cleanup(villager, toHandle(activeHandle));
                }
            }
        }
    }

    public static void clearAll() {
        for (Map.Entry<Villager, State> entry : STATES.entrySet()) {
            MovementRequestLedger.Handle activeHandle = entry.getValue().ledger.activeHandle();
            cleanupOwnedExecutor(entry.getKey(), entry.getValue());
            if (activeHandle != null) {
                MovementDiagnostics.cleanup(entry.getKey(), toHandle(activeHandle));
            }
        }
        STATES.clear();
    }

    private static boolean writeRequestMemories(Villager villager, State state,
                                                MovementRequestSpec spec) {
        boolean repaired = false;
        Path currentNavigationPath = villager.getNavigation().getPath();
        Path currentBrainPath = villager.getBrain()
                .getMemory(MemoryModuleType.PATH).orElse(null);
        if (!reachedDestination(villager, spec)
                && state.ownedPath != null
                && currentBrainPath == state.ownedPath
                && currentNavigationPath != state.ownedPath) {
            // The sink or another executor has lost the route represented by
            // this PATH memory. Erase only this request's stale memory and
            // leave navigation untouched so the sink can replan once.
            villager.getBrain().eraseMemory(MemoryModuleType.PATH);
            state.ownedPath = null;
            state.lastObservedPath = currentNavigationPath;
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
        if (currentWalkTarget == null
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
        return repaired;
    }

    /**
     * Stops and erases only executor state that this request can identify as
     * its own. A later WALK_TARGET, PATH, or look tracker is intentionally left
     * untouched.
     */
    private static void cleanupOwnedExecutor(Villager villager, State state) {
        Path currentNavigationPath = villager.getNavigation().getPath();
        if (state.ownedPath != null && currentNavigationPath == state.ownedPath) {
            villager.getNavigation().stop();
        }

        if (state.ownedPath != null
                && villager.getBrain().getMemory(MemoryModuleType.PATH).orElse(null)
                == state.ownedPath) {
            villager.getBrain().eraseMemory(MemoryModuleType.PATH);
        }
        if (state.ownedWalkTarget != null
                && villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null)
                == state.ownedWalkTarget) {
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            Object currentCantReach = villager.getBrain()
                    .getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null);
            if (state.ownedCantReachWalkTargetSince != null
                    && state.ownedCantReachWalkTargetSince.equals(currentCantReach)) {
                villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            }
        }
        if (state.ownedLookTarget != null
                && villager.getBrain().getMemory(MemoryModuleType.LOOK_TARGET).orElse(null)
                == state.ownedLookTarget) {
            villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        }
        state.clearExecutorOwnership();
    }

    private static MovementRequestLedger.Handle toLedgerHandle(MovementRequestHandle handle) {
        return handle == null ? null : new MovementRequestLedger.Handle(handle.owner(), handle.generation());
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
                spec.hasLookTarget() ? spec.lookTarget().asLong() : 0L,
                spec.clearLookTargetOnHandoff());
    }

    private static boolean reachedDestination(Villager villager, MovementRequestSpec spec) {
        double reach = Math.max(1.5D, spec.closeEnough() + 1.0D);
        return villager.blockPosition().distSqr(spec.destination()) <= reach * reach;
    }

    private static final class State {
        private final MovementRequestLedger ledger = new MovementRequestLedger();
        @Nullable
        private MovementRequestSpec activeSpec;
        @Nullable
        private Path lastObservedPath;
        @Nullable
        private Path ownedPath;
        @Nullable
        private WalkTarget ownedWalkTarget;
        @Nullable
        private PositionTracker ownedLookTarget;
        @Nullable
        private Long ownedCantReachWalkTargetSince;
        private int routeGeneration;

        private void clearExecutorOwnership() {
            activeSpec = null;
            lastObservedPath = null;
            ownedPath = null;
            ownedWalkTarget = null;
            ownedLookTarget = null;
            ownedCantReachWalkTargetSince = null;
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
    }
}
