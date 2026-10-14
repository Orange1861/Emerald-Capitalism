package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestOutcome;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestSpec;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestSubmission;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestTerminationReason;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Direct regression coverage for brain movement ownership and vanilla failure memory. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class MovementRequestManagerRegressionGameTests {

    private MovementRequestManagerRegressionGameTests() {
    }

    @GameTest(template = "empty_20x3x20", timeoutTicks = 500)
    public static void staleOwnerCannotStopARealBrainJourneyAfterPriorityPreemption(
            GameTestHelper helper) {
        installFloor(helper, 20);
        Villager villager = spawnBrainJourneyVillager(helper);
        BlockPos firstDestination = helper.absolutePos(new BlockPos(14, 1, 1));
        BlockPos higherDestination = helper.absolutePos(new BlockPos(14, 1, 3));
        MovementRequestSubmission first = MovementRequestManager.submit(villager,
                spec("regression:low-priority", "low", firstDestination,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(first.accepted(), "initial brain journey was rejected");
        waitForLivePathBeforePreemption(helper, villager, first, firstDestination,
                higherDestination, 180);
    }

    @GameTest(template = "empty_20x3x20", timeoutTicks = 500)
    public static void stalePathAndFailureDoNotBlockFreshVanillaSinkJourney(GameTestHelper helper) {
        installFloor(helper, 20);
        for (int x = 0; x < 20; x++) {
            helper.setBlock(new BlockPos(x, 1, 0), Blocks.STONE.defaultBlockState());
            helper.setBlock(new BlockPos(x, 2, 0), Blocks.STONE.defaultBlockState());
            helper.setBlock(new BlockPos(x, 1, 2), Blocks.STONE.defaultBlockState());
            helper.setBlock(new BlockPos(x, 2, 2), Blocks.STONE.defaultBlockState());
        }
        Villager villager = spawnBrainJourneyVillager(helper);
        BlockPos origin = villager.blockPosition();
        BlockPos staleDestination = helper.absolutePos(new BlockPos(18, 1, 18));
        Path stalePath = new Path(
                List.of(new Node(origin.getX(), origin.getY(), origin.getZ()),
                        new Node(staleDestination.getX(), staleDestination.getY(), staleDestination.getZ())),
                staleDestination, true);
        villager.getBrain().setMemory(MemoryModuleType.PATH, stalePath);
        villager.getBrain().setMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
                helper.getLevel().getGameTime() - 20L);

        BlockPos destination = helper.absolutePos(new BlockPos(8, 1, 1));
        MovementRequestSubmission submission = MovementRequestManager.submit(villager,
                spec("regression:fresh-after-stale", "fresh", destination,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(submission.accepted(), "fresh request was rejected after stale state");
        helper.assertFalse(villager.getBrain().hasMemoryValue(MemoryModuleType.PATH),
                "orphaned PATH memory was not repaired before the fresh request");
        helper.assertFalse(villager.getBrain().hasMemoryValue(
                        MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE),
                "stale CANT_REACH memory blocked the fresh request");
        helper.assertTrue(villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
                "fresh request did not install WALK_TARGET");
        awaitArrival(helper, villager, submission, spec("regression:fresh-after-stale", "fresh",
                destination, MovementRequestManager.PRIORITY_WORK), destination, 350);
    }

    @GameTest(template = "empty_3x3x3", timeoutTicks = 500)
    public static void cancelAfterVanillaConsumedWalkTargetClearsFailureForLaterJourney(
            GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        villager.goalSelector.removeAllGoals(ignored -> true);
        BlockPos unreachable = villager.blockPosition().above(12);
        MovementRequestSubmission failed = MovementRequestManager.submit(villager,
                spec("regression:forced-failure", "unreachable", unreachable,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(failed.accepted(), "forced-failure request was rejected");
        awaitVanillaFailure(helper, villager, failed, 160);
    }

    @GameTest(template = "empty_3x3x3", timeoutTicks = 300)
    public static void maintainedUnreachableRequestUsesBoundedRetryCooldown(
            GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        BlockPos unreachable = villager.blockPosition().above(12);
        MovementRequestSpec request = spec("regression:bounded-failure", "unreachable",
                unreachable, MovementRequestManager.PRIORITY_WORK);
        MovementRequestSubmission submission = MovementRequestManager.submit(villager, request);
        helper.assertTrue(submission.accepted(), "bounded-failure request was rejected");
        boolean previousEvents = PerformanceTimingCounters.isEventCountingEnabled();
        PerformanceTimingCounters.setEventCountingEnabled(true);
        PerformanceTimingCounters.clear();
        WalkTarget ownedTarget = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                .orElseThrow();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        long failureTime = helper.getLevel().getGameTime();
        villager.getBrain().setMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
                failureTime);
        MovementRequestManager.observeVanillaPathPlan(villager, ownedTarget, failureTime, false);

        for (int maintenance = 0; maintenance < 64; maintenance++) {
            MovementRequestSubmission maintained = MovementRequestManager.maintain(
                    villager, submission.handle(), request);
            helper.assertTrue(maintained.accepted(), "active unreachable request lost its owner");
            helper.assertValueEqual(villager.getBrain().getMemory(
                            MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null),
                    failureTime, "active failure timestamp changed during retry cooldown");
            helper.assertFalse(villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
                    "unreachable maintained request restored WALK_TARGET during cooldown");
        }
        PerformanceTimingCounters.Snapshot attempts = PerformanceTimingCounters.snapshot()
                .get(PerformanceTimingCounters.Operation.MOVEMENT_PATH_PLAN_ATTEMPT);
        helper.assertTrue(attempts.calls() <= 4,
                "unreachable maintained request replanned without a bound: " + attempts.calls());
        PerformanceTimingCounters.setEventCountingEnabled(previousEvents);
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void staleOwnerCancellationPreservesLaterForeignBrainState(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        BlockPos origin = villager.blockPosition();
        MovementRequestSubmission owner = MovementRequestManager.submit(villager,
                new MovementRequestSpec(
                        "regression:stale-owner", "owned", origin, 0.5F, 0,
                        "arrival-v1", "ground-v1", MovementRequestManager.PRIORITY_WORK,
                        origin));
        helper.assertTrue(owner.accepted(), "ownership fixture request was rejected");

        BlockPos foreignPosition = origin.offset(0, 0, 1);
        WalkTarget foreignWalkTarget = new WalkTarget(new BlockPosTracker(foreignPosition),
                0.4F, 0);
        BlockPosTracker foreignLookTarget = new BlockPosTracker(foreignPosition);
        Path foreignPath = new Path(
                List.of(new Node(origin.getX(), origin.getY(), origin.getZ()),
                        new Node(foreignPosition.getX(), foreignPosition.getY(), foreignPosition.getZ())),
                foreignPosition, true);
        Long laterFailure = helper.getLevel().getGameTime() + 1000L;
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, foreignWalkTarget);
        villager.getBrain().setMemory(MemoryModuleType.PATH, foreignPath);
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, foreignLookTarget);
        villager.getBrain().setMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
                laterFailure);

        helper.assertTrue(MovementRequestManager.cancel(villager, owner.handle()),
                "stale owner did not cancel its own request");
        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                        .orElse(null) == foreignWalkTarget,
                "stale cancellation erased a later foreign WALK_TARGET");
        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.PATH)
                        .orElse(null) == foreignPath,
                "stale cancellation erased a later foreign PATH");
        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.LOOK_TARGET)
                        .orElse(null) == foreignLookTarget,
                "stale cancellation erased a later foreign LOOK_TARGET");
        helper.assertValueEqual(villager.getBrain().getMemory(
                        MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null), laterFailure,
                "stale cancellation erased later foreign failure state");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void terminalReasonCountersAreExplicit(GameTestHelper helper) {
        boolean previousEvents = PerformanceTimingCounters.isEventCountingEnabled();
        PerformanceTimingCounters.setEventCountingEnabled(true);
        PerformanceTimingCounters.clear();

        Villager unmanagedVanilla = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 3, 1, 1);
        WalkTarget unmanagedTarget = new WalkTarget(
                new BlockPosTracker(unmanagedVanilla.blockPosition().offset(1, 0, 0)),
                0.6F, 0);
        MovementRequestManager.observeVanillaPathPlan(unmanagedVanilla, unmanagedTarget,
                helper.getLevel().getGameTime(), false);
        helper.assertValueEqual(PerformanceTimingCounters.snapshot().get(
                        PerformanceTimingCounters.Operation.MOVEMENT_PATH_PLAN_ATTEMPT).calls(), 0L,
                "unmanaged vanilla path plan incremented manager counters");

        Villager arrival = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        MovementRequestSubmission arrivalRequest = MovementRequestManager.submit(arrival,
                spec("regression:terminal-arrival", "arrival", arrival.blockPosition(),
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(arrivalRequest.accepted(), "arrival fixture was rejected");
        helper.assertTrue(MovementRequestManager.complete(arrival, arrivalRequest.handle()),
                "arrival fixture could not complete");

        Villager failure = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 2);
        MovementRequestSubmission failureRequest = MovementRequestManager.submit(failure,
                spec("regression:terminal-failure", "failure", failure.blockPosition().above(8),
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(failureRequest.accepted(), "failure fixture was rejected");
        helper.assertTrue(MovementRequestManager.cancel(failure, failureRequest.handle(),
                        MovementRequestTerminationReason.FAILED),
                "failure fixture could not cancel");

        Villager abandonment = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        MovementRequestSubmission abandonmentRequest = MovementRequestManager.submit(abandonment,
                spec("regression:terminal-abandonment", "abandonment",
                        abandonment.blockPosition().offset(1, 0, 0),
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(abandonmentRequest.accepted(), "abandonment fixture was rejected");
        helper.assertTrue(MovementRequestManager.cancel(abandonment, abandonmentRequest.handle()),
                "abandonment fixture could not cancel");

        Villager preemption = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 2);
        MovementRequestSubmission low = MovementRequestManager.submit(preemption,
                spec("regression:terminal-low", "low", preemption.blockPosition().offset(2, 0, 0),
                        MovementRequestManager.PRIORITY_WORK));
        MovementRequestSubmission high = MovementRequestManager.submit(preemption,
                spec("regression:terminal-high", "high", preemption.blockPosition().offset(0, 0, 2),
                        MovementRequestManager.PRIORITY_ESCAPE));
        helper.assertTrue(low.accepted() && high.outcome() == MovementRequestOutcome.PREEMPTED,
                "preemption fixture did not hand off ownership");
        helper.assertTrue(MovementRequestManager.complete(preemption, high.handle()),
                "preempting request could not complete");

        Villager unloaded = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        MovementRequestSubmission unloadRequest = MovementRequestManager.submit(unloaded,
                spec("regression:terminal-unload", "unload", unloaded.blockPosition().above(1),
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(unloadRequest.accepted(), "unload fixture was rejected");
        MovementRequestManager.release(unloaded);

        Villager shutdown = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 2);
        MovementRequestSubmission shutdownRequest = MovementRequestManager.submit(shutdown,
                spec("regression:terminal-shutdown", "shutdown", shutdown.blockPosition().above(1),
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(shutdownRequest.accepted(), "shutdown fixture was rejected");
        helper.assertTrue(MovementRequestManager.cancel(shutdown, shutdownRequest.handle(),
                        MovementRequestTerminationReason.SERVER_SHUTDOWN),
                "shutdown fixture could not record its terminal release");

        helper.assertValueEqual(PerformanceTimingCounters.snapshot().get(
                        PerformanceTimingCounters.Operation.MOVEMENT_ARRIVAL).calls(), 2L,
                "arrival terminal count was not exact");
        helper.assertValueEqual(PerformanceTimingCounters.snapshot().get(
                        PerformanceTimingCounters.Operation.MOVEMENT_FAILURE).calls(), 1L,
                "failure terminal count was not exact");
        helper.assertValueEqual(PerformanceTimingCounters.snapshot().get(
                        PerformanceTimingCounters.Operation.MOVEMENT_ABANDONED).calls(), 1L,
                "abandonment terminal count was not exact");
        helper.assertValueEqual(PerformanceTimingCounters.snapshot().get(
                        PerformanceTimingCounters.Operation.MOVEMENT_PREEMPTED).calls(), 1L,
                "preemption terminal count was not exact");
        helper.assertValueEqual(PerformanceTimingCounters.snapshot().get(
                        PerformanceTimingCounters.Operation.MOVEMENT_UNLOADED).calls(), 1L,
                "unload terminal count was not exact");
        helper.assertValueEqual(PerformanceTimingCounters.snapshot().get(
                        PerformanceTimingCounters.Operation.MOVEMENT_SHUTDOWN).calls(), 1L,
                "shutdown terminal count was not exact");
        PerformanceTimingCounters.setEventCountingEnabled(previousEvents);
        helper.succeed();
    }

    private static MovementRequestSpec spec(String owner, String identity, BlockPos destination,
                                            int priority) {
        return new MovementRequestSpec(owner, identity, destination, 0.6F, 0,
                "arrival-v1", "ground-v1", priority, null);
    }

    private static void waitForLivePathBeforePreemption(GameTestHelper helper, Villager villager,
                                                         MovementRequestSubmission first,
                                                         BlockPos firstDestination,
                                                         BlockPos higherDestination,
                                                         int remainingTicks) {
        Path brainPath = villager.getBrain().getMemory(MemoryModuleType.PATH).orElse(null);
        if (brainPath != null && brainPath == villager.getNavigation().getPath()
                && !brainPath.isDone()) {
            MovementRequestSubmission higher = MovementRequestManager.submit(villager,
                    spec("regression:high-priority", "high", higherDestination,
                            MovementRequestManager.PRIORITY_ESCAPE));
            helper.assertTrue(higher.accepted(), "higher-priority request was rejected");
            helper.assertValueEqual(higher.outcome().name(), "PREEMPTED",
                    "priority handoff did not record preemption");
            WalkTarget handoffTarget = villager.getBrain()
                    .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
            helper.assertTrue(handoffTarget != null
                            && handoffTarget.getTarget().currentBlockPosition()
                            .equals(higherDestination),
                    "higher-priority generation did not own WALK_TARGET immediately");
            helper.assertFalse(villager.blockPosition().distSqr(firstDestination) <= 2.25D,
                    "villager completed the old journey before the handoff took effect");
            helper.assertFalse(MovementRequestManager.cancel(villager, first.handle()),
                    "stale owner reclaimed the preempted request");
            helper.runAfterDelay(MovementRequestManager.MAX_HANDOFF_TICKS + 1,
                    () -> awaitNewGenerationRoute(helper, villager, higher, higherDestination, 300));
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("brain journey never exposed a live PATH/navigation route before preemption");
            return;
        }
        helper.runAfterDelay(1, () -> waitForLivePathBeforePreemption(
                helper, villager, first, firstDestination, higherDestination,
                remainingTicks - 1));
    }

    private static void awaitVanillaFailure(GameTestHelper helper, Villager villager,
                                             MovementRequestSubmission failed, int remainingTicks) {
        if (!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                && villager.getBrain().hasMemoryValue(
                MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)) {
            helper.assertTrue(MovementRequestManager.cancel(villager, failed.handle()),
                    "failed request could not be canceled after vanilla consumed WALK_TARGET");
            helper.assertFalse(villager.getBrain().hasMemoryValue(
                            MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE),
                    "cancel left the failed request's timestamp behind");
            BlockPos destination = villager.blockPosition().offset(1, 0, 0);
            MovementRequestSubmission later = MovementRequestManager.submit(villager,
                    spec("regression:later-reachable", "reachable", destination,
                            MovementRequestManager.PRIORITY_WORK));
            helper.assertTrue(later.accepted(), "later reachable request was rejected");
            awaitArrival(helper, villager, later, spec("regression:later-reachable", "reachable",
                    destination, MovementRequestManager.PRIORITY_WORK), destination, 250);
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("vanilla did not expose CANT_REACH after consuming WALK_TARGET");
            return;
        }
        helper.runAfterDelay(1, () -> awaitVanillaFailure(
                helper, villager, failed, remainingTicks - 1));
    }

    private static void awaitNewGenerationRoute(GameTestHelper helper, Villager villager,
                                                MovementRequestSubmission submission,
                                                BlockPos destination, int remainingTicks) {
        Path path = villager.getBrain().getMemory(MemoryModuleType.PATH).orElse(null);
        helper.assertFalse(villager.blockPosition().distSqr(destination) <= 2.25D
                        && path != null && path.getTarget().equals(destination),
                "preempted journey unexpectedly reported the old route as complete");
        if (path != null && path == villager.getNavigation().getPath() && !path.isDone()) {
            helper.assertValueEqual(path.getTarget(), destination,
                    "new generation did not own the live navigation PATH");
            awaitArrival(helper, villager, submission,
                    spec("regression:high-priority", "high", destination,
                            MovementRequestManager.PRIORITY_ESCAPE), destination, 300);
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("new generation did not establish a live route after handoff");
            return;
        }
        helper.runAfterDelay(1, () -> awaitNewGenerationRoute(
                helper, villager, submission, destination, remainingTicks - 1));
    }

    private static void awaitArrival(GameTestHelper helper, Villager villager,
                                     MovementRequestSubmission submission,
                                     MovementRequestSpec spec, BlockPos destination,
                                     int remainingTicks) {
        if (villager.blockPosition().distSqr(destination) <= 2.25D) {
            helper.assertTrue(MovementRequestManager.isActive(villager, submission.handle()),
                    "journey arrived without retaining its request owner");
            helper.assertTrue(MovementRequestManager.complete(villager, submission.handle()),
                    "arrived journey did not complete through its owner");
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("journey did not reach destination: current=" + villager.blockPosition()
                    + " target=" + destination);
            return;
        }
        MovementRequestSubmission maintained = MovementRequestManager.maintain(
                villager, submission.handle(), spec);
        helper.assertTrue(maintained.accepted(),
                "active request could not maintain its vanilla sink input");
        helper.runAfterDelay(1, () -> awaitArrival(
                helper, villager, submission, spec, destination, remainingTicks - 1));
    }

    private static void installFloor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static Villager spawnBrainJourneyVillager(GameTestHelper helper) {
        BlockPos jobSite = helper.absolutePos(new BlockPos(0, 1, 1));
        helper.setBlock(new BlockPos(0, 1, 1), Blocks.COMPOSTER.defaultBlockState());
        Villager villager = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        villager.setVillagerData(villager.getVillagerData()
                .setProfession(VillagerProfession.FARMER));
        villager.refreshBrain(helper.getLevel());
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(helper.getLevel().dimension(), jobSite));
        villager.goalSelector.removeAllGoals(ignored -> true);
        return villager;
    }
}
