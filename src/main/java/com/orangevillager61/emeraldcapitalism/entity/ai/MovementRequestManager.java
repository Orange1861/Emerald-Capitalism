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

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Narrow adapter between mod behaviors and Minecraft's MoveToTargetSink.
 *
 * <p>This class owns request identity and the handoff boundary. Minecraft's
 * movement sink remains the only route planner and path executor. The adapter
 * writes WALK_TARGET, and only removes executor memories when the request that
 * authorized the cleanup is still current.</p>
 */
public final class MovementRequestManager {

    public static final int PRIORITY_AMBIENT = 10;
    public static final int PRIORITY_WORK = 40;
    public static final int PRIORITY_ESCAPE = 100;

    private static final Map<Villager, State> STATES = new WeakHashMap<>();

    private MovementRequestManager() {
    }

    public static synchronized MovementRequestSubmission submit(Villager villager,
                                                                  MovementRequestSpec spec) {
        if (villager == null || spec == null) {
            throw new IllegalArgumentException("villager and spec are required");
        }
        State state = STATES.computeIfAbsent(villager, ignored -> new State());
        MovementIntent intent = toIntent(spec);
        MovementRequestLedger.Submission submission = state.ledger.submit(intent);
        MovementRequestHandle handle = new MovementRequestHandle(
                submission.handle().owner(), submission.handle().generation());
        MovementRequestOutcome outcome = MovementRequestOutcome.valueOf(submission.outcome().name());

        if (!submission.accepted()) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_REJECTION);
            return new MovementRequestSubmission(outcome, handle);
        }

        if (submission.changed()) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_SUBMISSION);
            if (outcome == MovementRequestOutcome.REPLACED) {
                PerformanceTimingCounters.recordEvent(
                        PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_REPLAN);
            } else if (outcome == MovementRequestOutcome.PREEMPTED) {
                PerformanceTimingCounters.recordEvent(
                        PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_PREEMPTION);
            }
            resetExecutorForHandoff(villager, state.lookTargetOwned);
            state.lookTargetOwned = ownsLookTarget(spec);
            MovementDiagnostics.transition(villager, outcome, handle, spec);
        }
        writeRequestMemories(villager, spec);
        observeRoute(villager, state, handle);
        return new MovementRequestSubmission(outcome, handle);
    }

    /**
     * Reasserts this request after the vanilla sink has ended a route. Equal
     * intent is idempotent; meaningful changes use the same handoff boundary
     * as a fresh submission and return the new capability handle.
     */
    public static synchronized MovementRequestSubmission maintain(Villager villager,
                                                                   MovementRequestHandle handle,
                                                                   MovementRequestSpec spec) {
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(spec, "spec");
        if (!handle.owner().equals(spec.owner()) || !isActive(villager, handle)) {
            PerformanceTimingCounters.recordEvent(
                    PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_REJECTION);
            return new MovementRequestSubmission(
                    MovementRequestOutcome.REJECTED_STALE_HANDLE, handle);
        }
        // submit() is deliberately used for the active request as well. It
        // preserves idempotence for equal intent, but advances generation and
        // resets the vanilla executor when destination, speed, policy, or look
        // target changes.
        return submit(villager, spec);
    }

    /** Clears only the request represented by {@code handle}. */
    public static synchronized boolean cancel(Villager villager,
                                               MovementRequestHandle handle,
                                               boolean clearLookTarget) {
        State state = STATES.get(villager);
        if (state == null || !state.ledger.owns(toLedgerHandle(handle))) {
            return false;
        }
        state.ledger.clear(toLedgerHandle(handle));
        villager.getNavigation().stop();
        eraseExecutorMemories(villager, clearLookTarget || state.lookTargetOwned);
        state.lookTargetOwned = false;
        MovementDiagnostics.cleanup(villager, handle);
        return true;
    }

    public static synchronized boolean isActive(Villager villager,
                                                MovementRequestHandle handle) {
        State state = STATES.get(villager);
        return state != null && state.ledger.owns(toLedgerHandle(handle));
    }

    public static synchronized MovementRequestHandle activeHandle(Villager villager) {
        State state = STATES.get(villager);
        if (state == null || state.ledger.activeHandle() == null) {
            return null;
        }
        MovementRequestLedger.Handle handle = state.ledger.activeHandle();
        return new MovementRequestHandle(handle.owner(), handle.generation());
    }

    /** Releases all transient ownership when an entity or server disappears. */
    public static synchronized void release(Villager villager) {
        if (villager != null) {
            State state = STATES.remove(villager);
            if (state != null) {
                cleanupExecutor(villager, state);
                state.ledger.clear();
            }
        }
    }

    public static synchronized void clearAll() {
        for (Map.Entry<Villager, State> entry : STATES.entrySet()) {
            cleanupExecutor(entry.getKey(), entry.getValue());
        }
        STATES.clear();
    }

    private static void writeRequestMemories(Villager villager, MovementRequestSpec spec) {
        if (spec.hasLookTarget()) {
            PositionTracker currentLookTarget = villager.getBrain()
                    .getMemory(MemoryModuleType.LOOK_TARGET).orElse(null);
            if (!(currentLookTarget instanceof BlockPosTracker)
                    || !spec.lookTarget().equals(currentLookTarget.currentBlockPosition())) {
                villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                        new BlockPosTracker(spec.lookTarget()));
            }
        }
        WalkTarget currentWalkTarget = villager.getBrain()
                .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        if (currentWalkTarget == null
                || !(currentWalkTarget.getTarget() instanceof BlockPosTracker)
                || !spec.destination().equals(currentWalkTarget.getTarget().currentBlockPosition())
                || Float.compare(spec.speedModifier(), currentWalkTarget.getSpeedModifier()) != 0
                || spec.closeEnough() != currentWalkTarget.getCloseEnoughDist()) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(new BlockPosTracker(spec.destination()),
                            spec.speedModifier(), spec.closeEnough()));
        }
    }

    private static void resetExecutorForHandoff(Villager villager, boolean clearLookTarget) {
        // PATH is owned by MoveToTargetSink, but it must be absent before the
        // sink admits a newly meaningful request. Stop and erase only at this
        // explicit handoff boundary; normal route replacement is not task
        // completion.
        villager.getNavigation().stop();
        villager.getBrain().eraseMemory(MemoryModuleType.PATH);
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        if (clearLookTarget) {
            villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        }
    }

    private static void eraseExecutorMemories(Villager villager, boolean clearLookTarget) {
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.PATH);
        villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        if (clearLookTarget) {
            villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        }
    }

    private static MovementRequestLedger.Handle toLedgerHandle(MovementRequestHandle handle) {
        return handle == null ? null : new MovementRequestLedger.Handle(handle.owner(), handle.generation());
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

    private static boolean ownsLookTarget(MovementRequestSpec spec) {
        return spec.hasLookTarget() || spec.clearLookTargetOnHandoff();
    }

    private static void cleanupExecutor(Villager villager, State state) {
        if (state.ledger.activeHandle() == null) {
            return;
        }
        villager.getNavigation().stop();
        eraseExecutorMemories(villager, state.lookTargetOwned);
        MovementRequestLedger.Handle ledgerHandle = state.ledger.activeHandle();
        MovementRequestHandle handle = new MovementRequestHandle(
                ledgerHandle.owner(), ledgerHandle.generation());
        MovementDiagnostics.cleanup(villager, handle);
        state.lookTargetOwned = false;
    }

    private static final class State {
        private final MovementRequestLedger ledger = new MovementRequestLedger();
        private Path lastObservedPath;
        private int routeGeneration;
        private boolean lookTargetOwned;
    }

    private static void observeRoute(Villager villager, State state,
                                     MovementRequestHandle handle) {
        if (state == null || handle == null) {
            return;
        }
        Path currentPath = villager.getNavigation().getPath();
        if (currentPath == state.lastObservedPath) {
            return;
        }
        state.lastObservedPath = currentPath;
        state.routeGeneration++;
        MovementDiagnostics.route(villager, handle, state.routeGeneration, currentPath);
    }
}
