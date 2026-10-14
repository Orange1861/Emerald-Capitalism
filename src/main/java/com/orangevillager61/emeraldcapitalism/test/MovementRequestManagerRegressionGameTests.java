package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestSpec;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestSubmission;
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
        waitForLivePathBeforePreemption(helper, villager, first, higherDestination, 180);
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

    private static MovementRequestSpec spec(String owner, String identity, BlockPos destination,
                                            int priority) {
        return new MovementRequestSpec(owner, identity, destination, 0.6F, 0,
                "arrival-v1", "ground-v1", priority, null);
    }

    private static void waitForLivePathBeforePreemption(GameTestHelper helper, Villager villager,
                                                         MovementRequestSubmission first,
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
            helper.assertFalse(MovementRequestManager.cancel(villager, first.handle()),
                    "stale owner reclaimed the preempted request");
            awaitArrival(helper, villager, higher, spec("regression:high-priority", "high",
                    higherDestination, MovementRequestManager.PRIORITY_ESCAPE), higherDestination, 300);
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("brain journey never exposed a live PATH/navigation route before preemption");
            return;
        }
        helper.runAfterDelay(1, () -> waitForLivePathBeforePreemption(
                helper, villager, first, higherDestination, remainingTicks - 1));
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
