package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.entity.ai.HarvestPumpkinGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestHandle;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestOutcome;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestSpec;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestSubmission;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestTerminationReason;
import com.orangevillager61.emeraldcapitalism.entity.ai.VillagerMovementRequestBinding;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Direct regression coverage for brain movement ownership and vanilla failure memory. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class MovementRequestManagerRegressionGameTests {

    private MovementRequestManagerRegressionGameTests() {
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_phase1_stale_owner", timeoutTicks = 500)
    public static void staleOwnerCannotStopARealBrainJourneyAfterPriorityPreemption(
            GameTestHelper helper) {
        installFloor(helper, 20);
        Villager villager = spawnBrainJourneyVillager(helper);
        BlockPos firstDestination = helper.absolutePos(new BlockPos(14, 1, 1));
        BlockPos higherDestination = helper.absolutePos(new BlockPos(14, 1, 3));
        awaitBrainJourneyReady(helper, villager, firstDestination, higherDestination, 80);
    }

    @GameTest(template = "empty_3x3x3")
    public static void movementBindingResubmitsAfterItsHandleIsRetired(GameTestHelper helper) {
        Villager villager = spawnBrainJourneyVillager(helper);
        BlockPos destination = helper.absolutePos(new BlockPos(1, 1, 1));
        MovementRequestSpec spec = spec("regression:binding-recovery", "destination", destination,
                MovementRequestManager.PRIORITY_WORK);
        VillagerMovementRequestBinding binding = new VillagerMovementRequestBinding();

        helper.assertTrue(binding.submit(villager, spec),
                "initial binding request was rejected");
        MovementRequestHandle firstHandle = binding.handle();
        helper.assertTrue(firstHandle != null,
                "initial binding request did not publish a handle");
        if (firstHandle == null) {
            return;
        }

        helper.assertTrue(MovementRequestManager.cancel(villager, firstHandle,
                        MovementRequestTerminationReason.PREEMPTED),
                "test could not retire the initial binding request");
        helper.assertTrue(binding.submit(villager, spec),
                "binding did not resubmit after its handle became stale");
        helper.assertTrue(binding.handle() != null
                        && !binding.handle().equals(firstHandle)
                        && MovementRequestManager.isActive(villager, binding.handle()),
                "binding did not retain the recovered movement request");
        binding.cancel(villager, MovementRequestTerminationReason.ABANDONED);
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_movement_unmanaged_handoff", timeoutTicks = 500)
    public static void firstEscapeRequestTakesOverUnmanagedLiveRouteWithNearbyDestination(
            GameTestHelper helper) {
        installFloor(helper, 20);
        helper.runAfterDelay(2, () -> beginUnmanagedEscapeHandoff(helper));
    }

    private static void beginUnmanagedEscapeHandoff(GameTestHelper helper) {
        Villager villager = spawnBrainJourneyVillager(helper);
        villager.setOnGround(true);
        // This is a live vanilla route long enough to exercise the handoff,
        // while the replacement remains within the documented two-block
        // handoff boundary.
        BlockPos unmanagedDestination = helper.absolutePos(new BlockPos(14, 1, 1));
        BlockPos escapeDestination = unmanagedDestination.offset(0, 0, 1);
        helper.assertTrue(unmanagedDestination.distManhattan(escapeDestination) <= 2,
                "handoff fixture targets are not within the short-route boundary");
        WalkTarget unmanagedTarget = new WalkTarget(new BlockPosTracker(unmanagedDestination),
                0.6F, 0);
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, unmanagedTarget);
        Path expectedVanillaPath = villager.getNavigation().createPath(unmanagedDestination, 0);
        helper.assertTrue(expectedVanillaPath != null && expectedVanillaPath.canReach(),
                "flat test floor did not provide a reachable unmanaged route: path="
                        + expectedVanillaPath + ", pos=" + villager.blockPosition()
                        + ", target=" + unmanagedDestination);
        TestableMoveToTargetSink oldSink = new TestableMoveToTargetSink();
        helper.assertTrue(oldSink.checkAndStart(helper.getLevel(), villager),
                "vanilla MoveToTargetSink did not start the unmanaged journey");
        Path unmanagedPath = villager.getBrain().getMemory(MemoryModuleType.PATH).orElse(null);
        helper.assertTrue(unmanagedPath != null
                        && unmanagedPath == villager.getNavigation().getPath()
                        && !unmanagedPath.isDone(),
                "fixture did not establish a live unmanaged PATH/navigation route");

        long capturedSinkEpoch = MovementRequestManager.captureVanillaSinkEpoch(villager);
        MovementRequestSubmission escape = MovementRequestManager.submit(villager,
                spec("regression:first-escape", "escape", escapeDestination,
                        MovementRequestManager.PRIORITY_ESCAPE));
        helper.assertTrue(escape.accepted(), "first escape request was rejected");
        helper.assertTrue(MovementRequestManager.isStaleVanillaSink(
                        villager, capturedSinkEpoch, unmanagedTarget),
                "sink captured before first ownership was not invalidated by publication");
        WalkTarget escapeTarget = villager.getBrain()
                .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        helper.assertTrue(escapeTarget != null && escapeTarget != unmanagedTarget,
                "first manager request did not install its own WALK_TARGET object");
        helper.assertValueEqual(escapeTarget.getTarget().currentBlockPosition(), escapeDestination,
                "first escape request did not immediately own WALK_TARGET");
        helper.assertTrue(villager.getNavigation().isDone(),
                "unmanaged vanilla navigation remained active after ownership handoff");
        helper.runAfterDelay(MovementRequestManager.MAX_HANDOFF_TICKS + 1,
                () -> awaitUnmanagedEscapeRoute(helper, villager, escape,
                        escapeDestination, oldSink, 300));
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_boat_preemption_journey", timeoutTicks = 700)
    public static void boatEscapePreemptsAndThenResumesARealPumpkinWorkJourney(
            GameTestHelper helper) {
        installFloor(helper, 20);
        BlockPos pumpkin = new BlockPos(14, 1, 10);
        installAttachedPumpkin(helper, pumpkin);
        BlockPos farmerJobSite = new BlockPos(10, 1, 9);
        helper.setBlock(farmerJobSite, Blocks.COMPOSTER.defaultBlockState());
        Villager farmer = helper.spawn(EntityType.VILLAGER, 10, 1, 10);
        farmer.setOnGround(true);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        farmer.setVillagerXp(1);
        farmer.refreshBrain(helper.getLevel());
        farmer.goalSelector.removeAllGoals(ignored -> true);
        farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                net.minecraft.core.GlobalPos.of(helper.getLevel().dimension(),
                        helper.absolutePos(farmerJobSite)));
        farmer.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        farmer.getBrain().eraseMemory(MemoryModuleType.PATH);
        farmer.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        farmer.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        HarvestPumpkinGoal pumpkinGoal = new HarvestPumpkinGoal(farmer);
        farmer.goalSelector.addGoal(0, pumpkinGoal);
        long previousDayTime = helper.getLevel().getDayTime();
        boolean previousMobGriefing = helper.getLevel().getGameRules()
                .getBoolean(GameRules.RULE_MOBGRIEFING);

        boolean previousBoatAvoidance = Config.enableBoatAvoidance;
        Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before =
                PerformanceTimingCounters.snapshot();
        PerformanceTimingCounters.EventCountingScope eventScope =
                PerformanceTimingCounters.beginEventCountingScope();
        BoatJourneyProgress progress = new BoatJourneyProgress(
                farmer, pumpkinGoal, helper.absolutePos(pumpkin),
                previousBoatAvoidance, eventScope, previousMobGriefing,
                previousDayTime, before);
        try {
            Config.enableBoatAvoidance = false;
            helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING)
                    .set(true, helper.getLevel().getServer());
            helper.getLevel().setDayTime(1_000L);
            helper.runAfterDelay(1, () -> awaitPumpkinWorkRoute(helper, progress, 220));
            helper.runAfterDelay(680, () -> {
                if (!progress.finished) {
                    progress.finish();
                    helper.fail("boat preemption journey did not resume and complete pumpkin work");
                }
            });
        } catch (RuntimeException | Error failure) {
            progress.finish();
            throw failure;
        }
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
        long staleFailureTime = helper.getLevel().getGameTime() - 20L;
        villager.getBrain().setMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
                staleFailureTime);

        BlockPos destination = helper.absolutePos(new BlockPos(8, 1, 1));
        MovementRequestSubmission submission = MovementRequestManager.submit(villager,
                spec("regression:fresh-after-stale", "fresh", destination,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(submission.accepted(), "fresh request was rejected after stale state");
        helper.assertFalse(villager.getBrain().hasMemoryValue(MemoryModuleType.PATH),
                "orphaned PATH memory was not repaired before the fresh request");
        helper.assertFalse(villager.getBrain().hasMemoryValue(
                        MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE),
                "preexisting failure timestamp gated the fresh manager-owned route");
        helper.assertTrue(villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
                "fresh request did not install WALK_TARGET");
        awaitArrival(helper, villager, submission, spec("regression:fresh-after-stale", "fresh",
                destination, MovementRequestManager.PRIORITY_WORK), destination, 350);
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_movement_partial_paths", timeoutTicks = 160)
    public static void partialPathContinuesUntilUsefulProgressOrTerminalFailure(
            GameTestHelper helper) {
        installFloor(helper, 20);
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        BlockPos destination = helper.absolutePos(new BlockPos(16, 1, 1));
        MovementRequestSubmission submission = MovementRequestManager.submit(villager,
                spec("regression:partial-path", "temporarily-unreachable", destination,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(submission.accepted(), "partial-path request was rejected");
        WalkTarget ownedTarget = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                .orElseThrow();
        BlockPos intermediate = villager.blockPosition().offset(3, 0, 0);
        Path partial = new Path(List.of(
                new Node(villager.blockPosition().getX(), villager.blockPosition().getY(),
                        villager.blockPosition().getZ()),
                new Node(intermediate.getX(), intermediate.getY(), intermediate.getZ())),
                destination, false);
        villager.getBrain().setMemory(MemoryModuleType.PATH, partial);
        helper.assertTrue(villager.getNavigation().moveTo(partial, 0.6D),
                "partial-path fixture could not start its live route");
        MovementRequestManager.observeVanillaPathPlan(villager, ownedTarget,
                helper.getLevel().getGameTime(), true, true, false, partial);
        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                        .orElse(null) == ownedTarget,
                "partial path observation erased a useful WALK_TARGET");
        helper.assertFalse(villager.getBrain().hasMemoryValue(
                        MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE),
                "partial path observation armed a terminal failure gate");

        BlockPos before = villager.blockPosition();
        helper.runAfterDelay(8, () -> {
            boolean progressed = !villager.blockPosition().equals(before);
            boolean stillUseful = villager.getNavigation().getPath() != null
                    && !villager.getNavigation().isDone();
            helper.assertTrue(progressed || stillUseful,
                    "partial route neither progressed nor remained live");
            helper.assertTrue(MovementRequestManager.isActive(villager, submission.handle()),
                    "partial route was terminally canceled before progress or a legitimate failure");
            helper.assertTrue(MovementRequestManager.cancel(villager, submission.handle(),
                            MovementRequestTerminationReason.ABANDONED),
                    "partial-path cleanup was not exactly once");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_movement_partial_paths", timeoutTicks = 160)
    public static void fallbackPathSurvivesVanillaCantReachMemory(GameTestHelper helper) {
        installFloor(helper, 20);
        Villager villager = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        BlockPos destination = helper.absolutePos(new BlockPos(14, 1, 1));
        MovementRequestSubmission submission = MovementRequestManager.submit(villager,
                spec("regression:fallback-path", "fallback", destination,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(submission.accepted(), "fallback-path request was rejected");
        WalkTarget target = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElseThrow();
        Path fallback = new Path(List.of(
                new Node(villager.blockPosition().getX(), villager.blockPosition().getY(),
                        villager.blockPosition().getZ()),
                new Node(villager.blockPosition().getX() + 2, villager.blockPosition().getY(),
                        villager.blockPosition().getZ())),
                destination, false);
        villager.getBrain().setMemory(MemoryModuleType.PATH, fallback);
        villager.getBrain().setMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
                helper.getLevel().getGameTime() - 1L);
        villager.getNavigation().moveTo(fallback, 0.6D);
        MovementRequestManager.observeVanillaPathPlan(villager, target,
                helper.getLevel().getGameTime(), true, false, false, fallback);
        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                        .orElse(null) == target,
                "fallback observation erased WALK_TARGET while a live path existed");
        helper.assertFalse(villager.getBrain().hasMemoryValue(
                        MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE),
                "fallback observation retained a stale failure gate");
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(MovementRequestManager.isActive(villager, submission.handle()),
                    "fallback route was canceled by retry throttling");
            helper.assertTrue(MovementRequestManager.cancel(villager, submission.handle(),
                            MovementRequestTerminationReason.ABANDONED),
                    "fallback-path cleanup was not exactly once");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_3x3x3", timeoutTicks = 500)
    public static void cancelAfterVanillaConsumedWalkTargetClearsFailureForLaterJourney(
            GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                for (int y = 1; y <= 3; y++) {
                    if (x != 1 || z != 1) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
                    }
                }
                helper.setBlock(new BlockPos(x, 4, z), Blocks.STONE.defaultBlockState());
            }
        }
        helper.setBlock(new BlockPos(1, 3, 1), Blocks.STONE.defaultBlockState());
        BlockPos unreachable = villager.blockPosition().above(12);
        MovementRequestSubmission failed = MovementRequestManager.submit(villager,
                spec("regression:forced-failure", "unreachable", unreachable,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(failed.accepted(), "forced-failure request was rejected");
        helper.runAfterDelay(2, () -> {
            // The real partial/fallback tests cover selector and sink pathing;
            // this test isolates cancellation from the exact post-sink state.
            WalkTarget ownedTarget = villager.getBrain().getMemory(
                    MemoryModuleType.WALK_TARGET).orElse(null);
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            long gameTime = helper.getLevel().getGameTime();
            villager.getBrain().setMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, gameTime);
            MovementRequestManager.observeVanillaPathPlan(villager, ownedTarget, gameTime,
                    false, true, false, null);
            awaitVanillaFailure(helper, villager, failed, 1);
        });
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_movement_retry_windows", timeoutTicks = 500)
    public static void maintainedUnreachableRequestUsesBoundedRetryCooldown(
            GameTestHelper helper) {
        installFloor(helper, 20);
        // Keep the target genuinely pathless: the villager is enclosed under
        // a solid roof, so the sink cannot turn an unreachable request into a
        // useful partial route and the retry contract is deterministic.
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                for (int y = 1; y <= 3; y++) {
                    if (x != 1 || z != 1) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                helper.setBlock(new BlockPos(x, 4, z), Blocks.STONE.defaultBlockState());
            }
        }
        // Leave the villager's two-block body volume clear, but close the
        // vertical escape cell so DefaultRandomPos cannot turn the failed
        // destination into a useful fallback route.
        helper.setBlock(new BlockPos(1, 3, 1), Blocks.STONE.defaultBlockState());
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        // Keep the retry contract focused on a genuinely pathless request.
        // A wall can still yield a useful partial route, which must remain
        // owned instead of entering the retry/failure state machine.
        BlockPos unreachable = villager.blockPosition().above(12);
        MovementRequestSpec request = spec("regression:tick-retry", "unreachable",
                unreachable, MovementRequestManager.PRIORITY_WORK);
        Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before =
                PerformanceTimingCounters.snapshot();
        PerformanceTimingCounters.EventCountingScope eventScope =
                PerformanceTimingCounters.beginEventCountingScope();
        RetryProgress progress = new RetryProgress(villager, request, eventScope, before);
        try {
            MovementRequestSubmission submission = MovementRequestManager.submit(villager, request);
            helper.assertTrue(submission.accepted(), "bounded-failure request was rejected");
            progress.handle = submission.handle();
            helper.runAfterDelay(1, () -> tickRetryWindow(helper, progress, 470));
        } catch (RuntimeException | Error failure) {
            progress.restore();
            throw failure;
        }
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
        helper.assertTrue(villager.getNavigation().moveTo(foreignPath, 0.4D),
                "foreign navigation fixture could not start");
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
        helper.assertTrue(villager.getNavigation().getPath() == foreignPath,
                "stale cancellation stopped a later foreign navigation route");
        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.LOOK_TARGET)
                        .orElse(null) == foreignLookTarget,
                "stale cancellation erased a later foreign LOOK_TARGET");
        helper.assertValueEqual(villager.getBrain().getMemory(
                        MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null), laterFailure,
                "stale cancellation erased later foreign failure state");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_movement_foreign_identity")
    public static void changedRequestDoesNotAdoptEqualForeignWalkTargetAndCancelRestoresIt(
            GameTestHelper helper) {
        installFloor(helper, 20);
        Villager villager = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        BlockPos origin = villager.blockPosition();
        BlockPos destination = origin.offset(6, 0, 0);
        WalkTarget foreignWalkTarget = new WalkTarget(new BlockPosTracker(destination), 0.6F, 0);
        Path foreignPath = new Path(
                List.of(new Node(origin.getX(), origin.getY(), origin.getZ()),
                        new Node(destination.getX(), destination.getY(), destination.getZ())),
                destination, true);
        BlockPosTracker foreignLookTarget = new BlockPosTracker(destination);
        long foreignFailure = helper.getLevel().getGameTime() - 7L;
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, foreignWalkTarget);
        villager.getBrain().setMemory(MemoryModuleType.PATH, foreignPath);
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, foreignLookTarget);
        TestableMoveToTargetSink staleSink = new TestableMoveToTargetSink();
        staleSink.startNow(helper.getLevel(), villager);
        villager.getBrain().setMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
                foreignFailure);

        MovementRequestSubmission request = MovementRequestManager.submit(villager,
                spec("regression:equal-foreign", "same-destination", destination,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(request.accepted(), "request with equal foreign target was rejected");
        WalkTarget managerTarget = villager.getBrain()
                .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        helper.assertTrue(managerTarget != null && managerTarget != foreignWalkTarget,
                "manager adopted a structurally equal foreign WALK_TARGET");
        helper.assertFalse(villager.getBrain().hasMemoryValue(
                        MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE),
                "preexisting foreign failure timestamp remained active during manager ownership");
        helper.assertTrue(villager.getNavigation().isDone(),
                "manager did not take ownership of the preexisting navigation route");
        helper.assertTrue(MovementRequestManager.cancel(villager, request.handle(),
                        MovementRequestTerminationReason.ABANDONED),
                "manager request could not be canceled");
        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                        .orElse(null) == foreignWalkTarget,
                "cancel did not restore the preexisting foreign WALK_TARGET identity");
        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.LOOK_TARGET)
                        .orElse(null) == foreignLookTarget,
                "cancel did not restore the preexisting foreign LOOK_TARGET identity");
        helper.assertValueEqual(villager.getBrain().getMemory(
                        MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null), foreignFailure,
                "cancel did not restore the preexisting foreign failure timestamp");
        helper.runAfterDelay(2, () -> {
            // The restored timestamp is deliberately retained for the
            // cancellation assertion above. A fresh vanilla sink may only
            // replan after that stale failure gate is no longer present.
            villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            helper.assertTrue(villager.getNavigation().moveTo(foreignPath, 0.6D),
                    "restored semantic route could not safely replan");
            Path restoredPath = villager.getNavigation().getPath();
            helper.assertTrue(restoredPath != null && !restoredPath.isDone(),
                    "restored route did not make a live path after delayed handoff");
            // The restored semantic owner may publish the same target again
            // after its sink starts. This is the later state the delayed old
            // sink must not erase.
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, foreignWalkTarget);
            staleSink.stopNow(helper.getLevel(), villager);
            helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                            .orElse(null) == foreignWalkTarget,
                    "old sink stop erased the restored equal-valued foreign target");
            helper.assertTrue(villager.getNavigation().getPath() == restoredPath
                            && !restoredPath.isDone(),
                    "old sink stop erased the replanned foreign navigation route");
            helper.runAfterDelay(3, () -> {
                helper.assertTrue(villager.blockPosition().distSqr(destination) <= 2.25D
                                || villager.getNavigation().getPath() != null,
                        "restored route neither progressed nor retained a live path");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty_3x3x3", batch = "ecap_movement_terminal_counters")
    public static void terminalReasonCountersAreExplicit(GameTestHelper helper) {
        Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before =
                PerformanceTimingCounters.snapshot();
        PerformanceTimingCounters.EventCountingScope eventScope =
                PerformanceTimingCounters.beginEventCountingScope();
        try {
            Villager unmanagedVanilla = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 3, 1, 1);
            WalkTarget unmanagedTarget = new WalkTarget(
                    new BlockPosTracker(unmanagedVanilla.blockPosition().offset(1, 0, 0)),
                    0.6F, 0);
            MovementRequestManager.observeVanillaPathPlan(unmanagedVanilla, unmanagedTarget,
                    helper.getLevel().getGameTime(), false, false, false);
            helper.assertValueEqual(delta(PerformanceTimingCounters.snapshot(), before,
                            PerformanceTimingCounters.Operation.MOVEMENT_PATH_PLAN_ATTEMPT), 0L,
                    "unmanaged vanilla path plan incremented manager counters");

            Villager arrival = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
            MovementRequestSubmission arrivalRequest = MovementRequestManager.submit(arrival,
                    spec("regression:terminal-arrival", "arrival", arrival.blockPosition(),
                            MovementRequestManager.PRIORITY_WORK));
            helper.assertTrue(arrivalRequest.accepted(), "arrival fixture was rejected");
            helper.assertTrue(MovementRequestManager.complete(arrival, arrivalRequest.handle()),
                    "arrival fixture could not complete");
            helper.assertFalse(MovementRequestManager.complete(arrival, arrivalRequest.handle()),
                    "arrival request accepted a second terminal transition");

            Villager failure = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 2);
            MovementRequestSubmission failureRequest = MovementRequestManager.submit(failure,
                    spec("regression:terminal-failure", "failure", failure.blockPosition().above(8),
                            MovementRequestManager.PRIORITY_WORK));
            helper.assertTrue(failureRequest.accepted(), "failure fixture was rejected");
            helper.assertTrue(MovementRequestManager.cancel(failure, failureRequest.handle(),
                            MovementRequestTerminationReason.FAILED),
                    "failure fixture could not cancel");
            helper.assertFalse(MovementRequestManager.cancel(failure, failureRequest.handle(),
                            MovementRequestTerminationReason.ABANDONED),
                    "failure request accepted a second terminal transition");

            Villager abandonment = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
            MovementRequestSubmission abandonmentRequest = MovementRequestManager.submit(abandonment,
                    spec("regression:terminal-abandonment", "abandonment",
                            abandonment.blockPosition().offset(1, 0, 0),
                            MovementRequestManager.PRIORITY_WORK));
            helper.assertTrue(abandonmentRequest.accepted(), "abandonment fixture was rejected");
            helper.assertTrue(MovementRequestManager.cancel(abandonment, abandonmentRequest.handle(),
                            MovementRequestTerminationReason.ABANDONED),
                    "abandonment fixture could not cancel");
            helper.assertFalse(MovementRequestManager.cancel(abandonment, abandonmentRequest.handle(),
                            MovementRequestTerminationReason.FAILED),
                    "abandonment request accepted a second terminal transition");

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
            helper.assertFalse(MovementRequestManager.complete(preemption, high.handle()),
                    "preempting request accepted a second terminal transition");

            Villager unloaded = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
            MovementRequestSubmission unloadRequest = MovementRequestManager.submit(unloaded,
                    spec("regression:terminal-unload", "unload", unloaded.blockPosition().above(1),
                            MovementRequestManager.PRIORITY_WORK));
            helper.assertTrue(unloadRequest.accepted(), "unload fixture was rejected");
            MovementRequestManager.release(unloaded);
            MovementRequestManager.release(unloaded);

            Villager shutdown = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 2);
            MovementRequestSubmission shutdownRequest = MovementRequestManager.submit(shutdown,
                    spec("regression:terminal-shutdown", "shutdown", shutdown.blockPosition().above(1),
                            MovementRequestManager.PRIORITY_WORK));
            helper.assertTrue(shutdownRequest.accepted(), "shutdown fixture was rejected");
            helper.assertTrue(MovementRequestManager.cancel(shutdown, shutdownRequest.handle(),
                            MovementRequestTerminationReason.SERVER_SHUTDOWN),
                    "shutdown fixture could not record its terminal release");
            helper.assertFalse(MovementRequestManager.cancel(shutdown, shutdownRequest.handle(),
                            MovementRequestTerminationReason.ABANDONED),
                    "shutdown request accepted a second terminal transition");

            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> after =
                    PerformanceTimingCounters.snapshot();
            helper.assertValueEqual(delta(after, before,
                            PerformanceTimingCounters.Operation.MOVEMENT_ARRIVAL), 2L,
                    "arrival terminal count delta was not exact");
            helper.assertValueEqual(delta(after, before,
                            PerformanceTimingCounters.Operation.MOVEMENT_FAILURE), 1L,
                    "failure terminal count delta was not exact");
            helper.assertValueEqual(delta(after, before,
                            PerformanceTimingCounters.Operation.MOVEMENT_ABANDONED), 1L,
                    "abandonment terminal count delta was not exact");
            helper.assertValueEqual(delta(after, before,
                            PerformanceTimingCounters.Operation.MOVEMENT_PREEMPTED), 1L,
                    "preemption terminal count delta was not exact");
            helper.assertValueEqual(delta(after, before,
                            PerformanceTimingCounters.Operation.MOVEMENT_UNLOADED), 1L,
                    "unload terminal count delta was not exact");
            helper.assertValueEqual(delta(after, before,
                            PerformanceTimingCounters.Operation.MOVEMENT_SHUTDOWN), 1L,
                    "shutdown terminal count delta was not exact");
            helper.succeed();
        } finally {
            eventScope.close();
        }
    }

    @GameTest(template = "empty_3x3x3", batch = "ecap_movement_terminal_counters")
    public static void reloadedEntitySessionCanReuseUuidWithGenerationOne(GameTestHelper helper) {
        UUID uuid = UUID.randomUUID();
        Villager oldSession = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        oldSession.setUUID(uuid);
        MovementRequestSubmission oldRequest = MovementRequestManager.submit(oldSession,
                spec("regression:reload-session", "same-owner", oldSession.blockPosition()
                        .offset(1, 0, 0), MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(oldRequest.accepted(), "old session request was rejected");
        MovementRequestHandle oldHandle = oldRequest.handle();
        MovementRequestManager.release(oldSession);

        Villager newSession = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        newSession.setUUID(uuid);
        MovementRequestSubmission newRequest = MovementRequestManager.submit(newSession,
                spec("regression:reload-session", "same-owner", newSession.blockPosition()
                        .offset(1, 0, 0), MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(newRequest.accepted(), "reloaded session request was rejected");
        helper.assertValueEqual(newRequest.handle().generation(), 1L,
                "reloaded entity session did not restart its local generation at one");
        helper.assertFalse(MovementRequestManager.cancel(oldSession, oldHandle,
                        MovementRequestTerminationReason.ABANDONED),
                "a delayed callback from the old entity session terminated anything");
        helper.assertTrue(MovementRequestManager.isActive(newSession, newRequest.handle()),
                "the delayed old-session callback disturbed the reloaded request");
        MovementRequestManager.release(newSession);
        helper.succeed();
    }

    private static void tickRetryWindow(GameTestHelper helper, RetryProgress progress,
                                        int remainingTicks) {
        try {
            if (progress.finished) {
                return;
            }
            long gameTime = helper.getLevel().getGameTime();
            if (progress.failureTimestamp == Long.MIN_VALUE
                    && progress.failureObservations <= progress.retryRestorations
                    && progress.villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                    && !progress.villager.getBrain().hasMemoryValue(
                    MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
                    ) {
                // Observe the same explicit no-path result that the vanilla
                // sink publishes after its createPath call. The partial and
                // fallback tests above exercise the real sink; this test keeps
                // the retry-clock boundary deterministic and does not add a
                // hand-built goal to the selector.
                WalkTarget retryTarget = progress.villager.getBrain()
                        .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
                helper.assertTrue(retryTarget != null, "retry observation lost its owned target");
                progress.failureObservations++;
                progress.villager.getBrain().setMemory(
                        MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, gameTime);
                MovementRequestManager.observeVanillaPathPlan(
                        progress.villager, retryTarget, gameTime,
                        false, true, false, null);
            }
            WalkTarget ownedBefore = MovementRequestManager.ownedWalkTarget(
                    progress.villager, progress.handle);
            MovementRequestSubmission maintained = MovementRequestManager.maintain(
                    progress.villager, progress.handle, progress.failedSpec);
            helper.assertTrue(maintained.accepted(), "active unreachable request lost its owner");

            WalkTarget currentWalkTarget = progress.villager.getBrain()
                    .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
            Long currentFailure = progress.villager.getBrain()
                    .getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null);

            if (currentWalkTarget == null) {
                progress.walkTargetWasConsumed = true;
            }

            if (progress.failureTimestamp != Long.MIN_VALUE
                    && progress.walkTargetWasConsumed
                    && gameTime >= progress.failureTimestamp
                    + MovementRequestManager.FAILURE_RETRY_COOLDOWN_TICKS) {
                helper.assertTrue(currentWalkTarget != null,
                        "eligible retry did not restore WALK_TARGET: gameTime=" + gameTime
                                + ", failureTimestamp=" + progress.failureTimestamp
                                + ", retryNotBefore=" + MovementRequestManager.failureRetryNotBefore(
                                progress.villager, progress.handle)
                                + ", currentFailure=" + currentFailure
                                + ", ownedTarget=" + MovementRequestManager.ownedWalkTarget(
                                progress.villager, progress.handle));
                helper.assertTrue(currentWalkTarget == MovementRequestManager.ownedWalkTarget(
                                progress.villager, progress.handle),
                        "eligible retry restored a foreign WALK_TARGET instead of its owned target");
                helper.assertTrue(ownedBefore != currentWalkTarget,
                        "WALK_TARGET was not restored with a manager-created object at eligibility");
                progress.retryRestorations++;
                progress.failureTimestamp = Long.MIN_VALUE;
                progress.walkTargetWasConsumed = false;
            } else if (progress.failureTimestamp != Long.MIN_VALUE
                    && gameTime < progress.failureTimestamp
                    + MovementRequestManager.FAILURE_RETRY_COOLDOWN_TICKS) {
                helper.assertTrue(currentFailure != null
                                && currentFailure == progress.failureTimestamp,
                        "failure timestamp stopped being observable during its cooldown: gameTime="
                                + gameTime + ", expected=" + progress.failureTimestamp
                                + ", actual=" + currentFailure);
                if (progress.walkTargetWasConsumed && currentWalkTarget != null) {
                    helper.assertTrue(currentWalkTarget != MovementRequestManager.ownedWalkTarget(
                                    progress.villager, progress.handle),
                            "manager restored its WALK_TARGET before retry eligibility: gameTime="
                                    + gameTime + ", failureTimestamp=" + progress.failureTimestamp
                                    + ", retryNotBefore="
                                    + MovementRequestManager.failureRetryNotBefore(
                                    progress.villager, progress.handle)
                                    + ", currentFailure=" + currentFailure);
                }
            }

            if (currentFailure != null
                    && currentFailure != progress.failureTimestamp) {
                if (progress.failureTimestamp != Long.MIN_VALUE) {
                    helper.assertTrue(gameTime >= progress.failureTimestamp
                                    + MovementRequestManager.FAILURE_RETRY_COOLDOWN_TICKS,
                            "failure timestamp changed before the retry became eligible");
                }
                if (progress.firstFailureTimestamp == Long.MIN_VALUE) {
                    progress.firstFailureTimestamp = currentFailure;
                }
                helper.assertValueEqual(MovementRequestManager.failureRetryNotBefore(
                                progress.villager, progress.handle),
                        currentFailure + MovementRequestManager.FAILURE_RETRY_COOLDOWN_TICKS,
                        "manager did not arm a retry gate from the visible failure timestamp");
                progress.failureEpisodes++;
                progress.failureTimestamp = currentFailure;
                progress.walkTargetWasConsumed = currentWalkTarget == null;
            }

            if (progress.retryRestorations >= 3) {
                helper.assertTrue(progress.failureEpisodes >= 3,
                        "retry test did not observe three separate failure windows");
                helper.assertTrue(gameTime - progress.firstFailureTimestamp >= 3L
                                * MovementRequestManager.FAILURE_RETRY_COOLDOWN_TICKS,
                        "retry test did not run across three full cooldown windows");
                helper.assertTrue(MovementRequestManager.pathPlanAttempts(
                                progress.villager, progress.handle) <= 4,
                        "real sink path-plan attempts exceeded the initial plan plus three retries");
                Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> duringFailure =
                        PerformanceTimingCounters.snapshot();
                helper.assertValueEqual(delta(duringFailure, progress.before,
                                PerformanceTimingCounters.Operation.MOVEMENT_DESTINATION_REACHABLE), 0L,
                        "an unreachable path was counted as reaching the requested destination");

                helper.assertTrue(MovementRequestManager.cancel(progress.villager,
                                progress.handle, MovementRequestTerminationReason.FAILED),
                        "unreachable request did not terminate as failed");
                for (int x = 0; x < 3; x++) {
                    for (int z = 0; z < 3; z++) {
                        for (int y = 1; y <= 4; y++) {
                            helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                        }
                    }
                }
                BlockPos reachable = progress.villager.blockPosition();
                MovementRequestSpec replacementSpec = spec("regression:retry-replacement",
                        "reachable", reachable, MovementRequestManager.PRIORITY_WORK);
                MovementRequestSubmission replacement = MovementRequestManager.submit(
                        progress.villager, replacementSpec);
                helper.assertTrue(replacement.accepted(),
                        "later reachable replacement was rejected");
                progress.replacementHandle = replacement.handle();
                progress.replacementSpec = replacementSpec;
                WalkTarget replacementTarget = MovementRequestManager.ownedWalkTarget(
                        progress.villager, replacement.handle());
                helper.assertTrue(replacementTarget != null,
                        "later reachable replacement did not install its target");
                long replacementTime = helper.getLevel().getGameTime();
                MovementRequestManager.observeVanillaPathPlan(progress.villager,
                        replacementTarget, replacementTime, true, true, true, null);
                helper.assertTrue(MovementRequestManager.complete(progress.villager,
                                replacement.handle()),
                        "later reachable replacement did not complete as ARRIVED");
                Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> after =
                        PerformanceTimingCounters.snapshot();
                helper.assertTrue(delta(after, progress.before,
                                PerformanceTimingCounters.Operation.MOVEMENT_DESTINATION_REACHABLE) > 0L,
                        "later reachable replacement was not observed as destination-reachable");
                progress.finished = true;
                progress.restore();
                helper.succeed();
                return;
            }
            if (remainingTicks <= 0) {
                progress.restore();
                progress.finished = true;
                helper.fail("request did not complete three tick-driven retry cooldown windows: "
                        + "episodes=" + progress.failureEpisodes
                        + ", restorations=" + progress.retryRestorations
                        + ", firstFailure=" + progress.firstFailureTimestamp
                        + ", failure=" + progress.failureTimestamp
                        + ", gameTime=" + gameTime
                        + ", active=" + MovementRequestManager.isActive(
                        progress.villager, progress.handle)
                        + ", failureObservations=" + progress.failureObservations
                        + ", walkTarget=" + progress.villager.getBrain()
                        .getMemory(MemoryModuleType.WALK_TARGET).orElse(null)
                        + ", cantReach=" + progress.villager.getBrain()
                        .getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null)
                        + ", path=" + progress.villager.getBrain()
                        .getMemory(MemoryModuleType.PATH).orElse(null)
                        + ", navigationPath=" + progress.villager.getNavigation().getPath());
                return;
            }
            helper.runAfterDelay(1,
                    () -> tickRetryWindow(helper, progress, remainingTicks - 1));
        } catch (RuntimeException | Error failure) {
            progress.restore();
            progress.finished = true;
            throw failure;
        }
    }

    private static void awaitRetryReplacement(GameTestHelper helper, RetryProgress progress,
                                              int remainingTicks) {
        try {
            BlockPos destination = progress.replacementSpec.destination();
            if (progress.villager.blockPosition().distSqr(destination) <= 2.25D) {
                helper.assertTrue(MovementRequestManager.isActive(
                                progress.villager, progress.replacementHandle),
                        "reachable replacement arrived without retaining its owner");
                helper.assertTrue(MovementRequestManager.complete(
                                progress.villager, progress.replacementHandle),
                        "reachable replacement did not complete as ARRIVED");
                Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> after =
                        PerformanceTimingCounters.snapshot();
                helper.assertTrue(delta(after, progress.before,
                                PerformanceTimingCounters.Operation.MOVEMENT_DESTINATION_REACHABLE) > 0L,
                        "later reachable replacement was not observed as destination-reachable");
                progress.finished = true;
                helper.succeed();
                return;
            }
            if (remainingTicks <= 0) {
                progress.restore();
                progress.finished = true;
                helper.fail("later reachable replacement did not arrive");
                return;
            }
            MovementRequestSubmission maintained = MovementRequestManager.maintain(
                    progress.villager, progress.replacementHandle, progress.replacementSpec);
            helper.assertTrue(maintained.accepted(),
                    "reachable replacement lost its active request");
            helper.runAfterDelay(1,
                    () -> awaitRetryReplacement(helper, progress, remainingTicks - 1));
        } catch (RuntimeException | Error failure) {
            progress.restore();
            progress.finished = true;
            throw failure;
        } finally {
            if (progress.finished) {
                progress.restore();
            }
        }
    }

    private static long delta(
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> after,
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before,
            PerformanceTimingCounters.Operation operation) {
        return after.get(operation).calls() - before.get(operation).calls();
    }

    private static MovementRequestSpec spec(String owner, String identity, BlockPos destination,
                                            int priority) {
        return new MovementRequestSpec(owner, identity, destination, 0.6F, 0,
                "arrival-v1", "ground-v1", priority, null);
    }

    private static void awaitPumpkinWorkRoute(GameTestHelper helper,
                                              BoatJourneyProgress progress,
                                              int remainingTicks) {
        try {
            MovementRequestHandle active = MovementRequestManager.activeHandle(progress.farmer);
            Path path = progress.farmer.getBrain().getMemory(MemoryModuleType.PATH).orElse(null);
            if (active != null && active.owner().equals("emeraldcapitalism:pumpkin_harvest")
                    && path != null && path == progress.farmer.getNavigation().getPath()
                    && !path.isDone()) {
                progress.initialWorkGeneration = active.generation();
                progress.initialPosition = progress.farmer.position();
                // Place the threat from the observed live-route position. A
                // fixed point could be behind a farmer that had already
                // progressed, allowing collision/push to invalidate the
                // distance assertion before boat avoidance owned the route.
                BlockPos localOrigin = helper.absolutePos(BlockPos.ZERO);
                BlockPos localBoatPosition = progress.farmer.blockPosition()
                        .subtract(localOrigin).offset(3, 0, 0);
                progress.boat = spawnTestBoat(helper, localBoatPosition);
                Config.enableBoatAvoidance = true;
                progress.initialBoatDistance = progress.farmer.distanceToSqr(progress.boat);
                helper.runAfterDelay(1, () -> awaitBoatEscape(helper, progress, 100));
                return;
            }
            if (remainingTicks <= 0) {
                MovementRequestHandle currentActive =
                        MovementRequestManager.activeHandle(progress.farmer);
                WalkTarget walkTarget = progress.farmer.getBrain()
                        .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
                Path currentPath = progress.farmer.getBrain()
                        .getMemory(MemoryModuleType.PATH).orElse(null);
                boolean goalEligible = progress.pumpkinGoal.canUse();
                boolean mobGriefing = helper.getLevel().getGameRules()
                        .getBoolean(GameRules.RULE_MOBGRIEFING);
                progress.finish();
                helper.fail("production pumpkin work did not establish a live MoveToTargetSink route: pos="
                        + progress.farmer.blockPosition() + ", pumpkin="
                        + helper.getLevel().getBlockState(progress.pumpkin) + ", active=" + currentActive
                        + ", goalTarget=" + progress.pumpkinGoal.getTargetPos()
                        + ", goalEligible=" + goalEligible
                        + ", mobGriefing=" + mobGriefing
                        + ", walkTarget=" + walkTarget + ", path=" + currentPath);
                return;
            }
            helper.runAfterDelay(1,
                    () -> awaitPumpkinWorkRoute(helper, progress, remainingTicks - 1));
        } catch (RuntimeException | Error failure) {
            progress.finish();
            throw failure;
        }
    }

    private static void awaitBoatEscape(GameTestHelper helper,
                                        BoatJourneyProgress progress,
                                        int remainingTicks) {
        try {
            MovementRequestHandle active = MovementRequestManager.activeHandle(progress.farmer);
            if (active != null && active.owner().equals("emeraldcapitalism:boat_avoidance")) {
                progress.escapePublished = true;
                WalkTarget escapeTarget = progress.farmer.getBrain()
                        .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
                helper.assertTrue(escapeTarget != null,
                        "boat preemption did not publish its owned WALK_TARGET");
                helper.assertTrue(escapeTarget.getTarget().currentBlockPosition()
                                .distSqr(progress.boat.blockPosition()) > 12.0D,
                        "boat escape target did not create meaningful distance from the boat");
            }
            if (progress.escapePublished
                    && progress.farmer.distanceToSqr(progress.boat)
                    >= progress.initialBoatDistance + 1.0D
                    && progress.farmer.position().distanceToSqr(progress.initialPosition) > 0.25D) {
                progress.boat.discard();
                helper.runAfterDelay(1, () -> awaitResumedPumpkinWork(helper, progress, 520));
                return;
            }
            if (remainingTicks <= 0) {
                progress.finish();
                helper.fail("boat escape did not take ownership and move the villager promptly: "
                        + "observed=" + progress.escapePublished
                        + ", pos=" + progress.farmer.position()
                        + ", boat=" + progress.boat.position()
                        + ", initialDistance=" + progress.initialBoatDistance
                        + ", currentDistance=" + progress.farmer.distanceToSqr(progress.boat)
                        + ", active=" + MovementRequestManager.activeHandle(progress.farmer));
                return;
            }
            helper.runAfterDelay(1, () -> awaitBoatEscape(helper, progress, remainingTicks - 1));
        } catch (RuntimeException | Error failure) {
            progress.finish();
            throw failure;
        }
    }

    private static void awaitResumedPumpkinWork(GameTestHelper helper,
                                                BoatJourneyProgress progress,
                                                int remainingTicks) {
        try {
            MovementRequestHandle latest = MovementRequestManager.lastPublishedHandle(progress.farmer);
            if (latest != null && latest.owner().equals("emeraldcapitalism:pumpkin_harvest")
                    && latest.generation() != progress.initialWorkGeneration) {
                progress.workResumed = true;
            }
            BlockPos pumpkin = progress.pumpkin;
            if (helper.getLevel().getBlockState(pumpkin).isAir()
                    && countItem(progress.farmer, Items.PUMPKIN) > 0) {
                helper.assertTrue(progress.workResumed,
                        "pumpkin world effect occurred without a later work request resuming: initial="
                                + progress.initialWorkGeneration + ", latest="
                                + MovementRequestManager.lastPublishedHandle(progress.farmer)
                                + ", active=" + MovementRequestManager.activeHandle(progress.farmer)
                                + ", goalTarget=" + progress.pumpkinGoal.getTargetPos()
                                + ", position=" + progress.farmer.blockPosition());
                helper.assertValueEqual(MovementRequestManager.terminalCount(progress.farmer,
                                MovementRequestTerminationReason.PREEMPTED), 1L,
                        "boat escape did not record exactly one work-request preemption for this villager");
                helper.assertTrue(MovementRequestManager.terminalCount(progress.farmer,
                                MovementRequestTerminationReason.ARRIVED) >= 1L,
                        "resumed production work did not record a completed movement leg");
                helper.succeed();
                progress.finish();
                return;
            }

            if (remainingTicks <= 0) {
                progress.finish();
                helper.fail("pumpkin work did not resume after the boat threat left: position="
                        + progress.farmer.blockPosition() + ", goalTarget="
                        + progress.pumpkinGoal.getTargetPos() + ", latest="
                        + MovementRequestManager.lastPublishedHandle(progress.farmer)
                        + ", active=" + MovementRequestManager.activeHandle(progress.farmer)
                        + ", walkTarget=" + progress.farmer.getBrain()
                        .getMemory(MemoryModuleType.WALK_TARGET).orElse(null));
                return;
            }
            helper.runAfterDelay(1,
                    () -> awaitResumedPumpkinWork(helper, progress, remainingTicks - 1));
        } catch (RuntimeException | Error failure) {
            progress.finish();
            throw failure;
        }
    }

    private static Boat spawnTestBoat(GameTestHelper helper, BlockPos localPosition) {
//? if >=1.21.4 {
        /*return helper.spawn(EntityType.OAK_BOAT,
                localPosition.getX(), localPosition.getY(), localPosition.getZ());
*///?} else {
        return helper.spawn(EntityType.BOAT,
                localPosition.getX(), localPosition.getY(), localPosition.getZ());
 //?}
    }

    private static void installAttachedPumpkin(GameTestHelper helper, BlockPos pumpkin) {
        BlockPos stem = pumpkin.relative(Direction.SOUTH);
        helper.setBlock(pumpkin, Blocks.PUMPKIN.defaultBlockState());
        helper.setBlock(stem, Blocks.ATTACHED_PUMPKIN_STEM.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
    }

    private static void awaitUnmanagedEscapeRoute(GameTestHelper helper, Villager villager,
                                                  MovementRequestSubmission escape,
                                                  BlockPos destination,
                                                  TestableMoveToTargetSink staleSink,
                                                  int remainingTicks) {
        Path path = villager.getBrain().getMemory(MemoryModuleType.PATH).orElse(null);
        if (path != null && path == villager.getNavigation().getPath() && !path.isDone()) {
            helper.assertValueEqual(path.getTarget(), destination,
                    "first escape request did not establish its own live route");
            WalkTarget ownedTarget = villager.getBrain()
                    .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
            staleSink.stopNow(helper.getLevel(), villager);
            helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                            .orElse(null) == ownedTarget,
                    "stale unmanaged sink stop erased the manager WALK_TARGET");
            helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.PATH)
                            .orElse(null) == path
                            && villager.getNavigation().getPath() == path
                            && !path.isDone(),
                    "stale unmanaged sink stop erased the manager PATH/navigation");
            awaitArrival(helper, villager, escape,
                    spec("regression:first-escape", "escape", destination,
                            MovementRequestManager.PRIORITY_ESCAPE), destination, 300);
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("first escape request did not establish a live route after handoff");
            return;
        }
        helper.runAfterDelay(1, () -> awaitUnmanagedEscapeRoute(helper, villager, escape,
                destination, staleSink, remainingTicks - 1));
    }

    private static final class TestableMoveToTargetSink extends MoveToTargetSink {
        private void startNow(ServerLevel level, Villager villager) {
            start(level, villager, level.getGameTime());
        }

        private boolean checkAndStart(ServerLevel level, Villager villager) {
            long gameTime = level.getGameTime();
            if (!checkExtraStartConditions(level, villager)) {
                return false;
            }
            start(level, villager, gameTime);
            return true;
        }

        private void stopNow(ServerLevel level, Villager villager) {
            stop(level, villager, level.getGameTime());
        }

    }

    private static void waitForLivePathBeforePreemption(GameTestHelper helper, Villager villager,
                                                         MovementRequestSubmission first,
                                                         BlockPos firstDestination,
                                                         BlockPos higherDestination,
                                                         int remainingTicks) {
        MovementRequestSubmission maintained = MovementRequestManager.maintain(villager,
                first.handle(), spec("regression:low-priority", "low", firstDestination,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(maintained.accepted(),
                "initial brain journey could not maintain its manager-owned request");
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

    private static void awaitBrainJourneyReady(GameTestHelper helper, Villager villager,
                                                BlockPos firstDestination,
                                                BlockPos higherDestination,
                                                int remainingTicks) {
        Path currentPath = villager.getNavigation().getPath();
        if (villager.isAlive() && villager.isAddedToLevel() && villager.tickCount > 0
                && villager.onGround() && (currentPath == null || currentPath.isDone())) {
            clearManagerJourneyBrain(villager);
            MovementRequestSubmission first = MovementRequestManager.submit(villager,
                    spec("regression:low-priority", "low", firstDestination,
                            MovementRequestManager.PRIORITY_WORK));
            helper.assertTrue(first.accepted(), "initial brain journey was rejected");
            waitForLivePathBeforePreemption(helper, villager, first, firstDestination,
                    higherDestination, 180);
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("brain journey fixture never reached entity/path readiness: tickCount="
                    + villager.tickCount + ", alive=" + villager.isAlive()
                    + ", added=" + villager.isAddedToLevel() + ", onGround="
                    + villager.onGround() + ", path=" + currentPath);
            return;
        }
        helper.runAfterDelay(1, () -> awaitBrainJourneyReady(helper, villager,
                firstDestination, higherDestination, remainingTicks - 1));
    }

    private static void clearManagerJourneyBrain(Villager villager) {
        villager.getNavigation().stop();
        villager.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.PATH);
        villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        villager.getBrain().eraseMemory(MemoryModuleType.INTERACTION_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.BREED_TARGET);
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
        Villager villager = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        villager.setVillagerData(villager.getVillagerData()
                .setProfession(VillagerProfession.FARMER));
        villager.setVillagerXp(1);
        villager.refreshBrain(helper.getLevel());
        // Keep this fixture's brain focused on the manager-owned movement
        // request. A job site can publish a competing WALK_TARGET between
        // ticks on newer game versions, making the preemption assertion depend
        // on unrelated villager scheduling rather than MoveToTargetSink.
        villager.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.PATH);
        villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        villager.getBrain().eraseMemory(MemoryModuleType.INTERACTION_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.BREED_TARGET);
        villager.goalSelector.removeAllGoals(ignored -> true);
        return villager;
    }

    private static int countItem(Villager villager, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static final class RetryProgress {
        private final Villager villager;
        private final MovementRequestSpec failedSpec;
        private final PerformanceTimingCounters.EventCountingScope eventScope;
        private final Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before;
        private MovementRequestHandle handle;
        private MovementRequestHandle replacementHandle;
        private MovementRequestSpec replacementSpec;
        private long firstFailureTimestamp = Long.MIN_VALUE;
        private long failureTimestamp = Long.MIN_VALUE;
        private int failureEpisodes;
        private int retryRestorations;
        private boolean walkTargetWasConsumed;
        private boolean finished;
        private int failureObservations;

        private RetryProgress(Villager villager, MovementRequestSpec failedSpec,
                             PerformanceTimingCounters.EventCountingScope eventScope,
                             Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before) {
            this.villager = villager;
            this.failedSpec = failedSpec;
            this.eventScope = eventScope;
            this.before = before;
        }

        private void restore() {
            eventScope.close();
        }
    }

    private static final class BoatJourneyProgress {
        private final Villager farmer;
        private final HarvestPumpkinGoal pumpkinGoal;
        private final BlockPos pumpkin;
        private final boolean previousBoatAvoidance;
        private final PerformanceTimingCounters.EventCountingScope eventScope;
        private final boolean previousMobGriefing;
        private final long previousDayTime;
        private final Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before;
        private Boat boat;
        private net.minecraft.world.phys.Vec3 initialPosition;
        private double initialBoatDistance;
        private long initialWorkGeneration = Long.MIN_VALUE;
        private boolean escapePublished;
        private boolean workResumed;
        private boolean finished;

        private BoatJourneyProgress(Villager farmer, HarvestPumpkinGoal pumpkinGoal,
                                   BlockPos pumpkin,
                                   boolean previousBoatAvoidance,
                                   PerformanceTimingCounters.EventCountingScope eventScope,
                                   boolean previousMobGriefing, long previousDayTime,
                                   Map<PerformanceTimingCounters.Operation,
                                           PerformanceTimingCounters.Snapshot> before) {
            this.farmer = farmer;
            this.pumpkinGoal = pumpkinGoal;
            this.pumpkin = pumpkin;
            this.previousBoatAvoidance = previousBoatAvoidance;
            this.eventScope = eventScope;
            this.previousMobGriefing = previousMobGriefing;
            this.previousDayTime = previousDayTime;
            this.before = before;
        }

        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            Config.enableBoatAvoidance = previousBoatAvoidance;
            eventScope.close();
            if (farmer.level() instanceof ServerLevel level) {
                level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING)
                        .set(previousMobGriefing, level.getServer());
                level.setDayTime(previousDayTime);
            }
        }
    }
}
