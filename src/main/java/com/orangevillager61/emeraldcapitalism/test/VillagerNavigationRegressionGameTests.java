package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.entity.ai.HarvestPumpkinGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestHandle;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestTerminationReason;
import com.orangevillager61.emeraldcapitalism.entity.ai.VillagerLadderClaims;
import com.orangevillager61.emeraldcapitalism.entity.ai.VillagerNavigationTargets;
import com.orangevillager61.emeraldcapitalism.entity.ai.VillagerNavigationWatchdog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/** Regression coverage for bounded navigation, ladder exits, pumpkin retries, and watchdog progress. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class VillagerNavigationRegressionGameTests {

    private VillagerNavigationRegressionGameTests() {
    }

    @GameTest(template = "empty_3x8x3", batch = "ecap_navigation_regressions", timeoutTicks = 600)
    public static void villagerClimbsToLandingAndReachesOffsetWorkstation(GameTestHelper helper) {
        BlockPos ladderBase = installLadder(helper);
        BlockPos workPosition = helper.absolutePos(new BlockPos(1, 5, 2));
        helper.setBlock(new BlockPos(1, 4, 2), Blocks.COMPOSTER.defaultBlockState());
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 0);
        villager.moveTo(ladderBase.getX() + 0.5D, ladderBase.getY(), ladderBase.getZ() + 0.5D,
                0.0F, 0.0F);

        List<Node> nodes = verticalRoute(ladderBase, true);
        nodes.add(new Node(ladderBase.getX(), ladderBase.getY() + 4, ladderBase.getZ() + 1));
        nodes.add(new Node(workPosition.getX(), workPosition.getY(), workPosition.getZ()));
        helper.assertTrue(villager.getNavigation().moveTo(new Path(nodes, workPosition, true), 1.0D),
                "ascending route with an offset landing could not be installed");
        awaitLandingAndWork(helper, villager, ladderBase, workPosition,
                new LadderExitObservation(), 500, false, 0);
    }

    @GameTest(template = "empty_3x8x3", batch = "ecap_navigation_regressions", timeoutTicks = 500)
    public static void villagerDescendsLadderAndLeavesOnSupportedFooting(GameTestHelper helper) {
        BlockPos ladderBase = installLadder(helper);
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 5, 0);
        villager.moveTo(ladderBase.getX() + 0.5D, ladderBase.getY() + 4.0D,
                ladderBase.getZ() + 0.5D, 0.0F, 0.0F);

        List<Node> nodes = verticalRoute(ladderBase, false);
        nodes.add(new Node(ladderBase.getX(), ladderBase.getY(), ladderBase.getZ() + 1));
        BlockPos bottomExit = ladderBase.relative(Direction.SOUTH);
        helper.assertTrue(villager.getNavigation().moveTo(new Path(nodes, bottomExit, true), 1.0D),
                "descending route could not be installed");
        awaitDescent(helper, villager, ladderBase, 400);
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_navigation_regressions", timeoutTicks = 300)
    public static void outerNavigationCandidateStillAllowsPumpkinWork(GameTestHelper helper) {
        BlockPos pumpkin = new BlockPos(6, 1, 1);
        installFloor(helper, 20);
        installAttachedPumpkin(helper, pumpkin);

        // Leave only a valid west approach open among the inner candidates.
        // The outer ring remains available to prove the bounded search does
        // not spend its whole budget on the nearest cells.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = pumpkin.getX() + dx;
                int z = pumpkin.getZ() + dz;
                if ((dx == 0 && dz == 0) || dx == -1) {
                    continue;
                }
                if (x == pumpkin.getX() && z == pumpkin.getZ() + 1) {
                    helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
                } else {
                    helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
                    helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
                }
            }
        }
        BlockPos pumpkinStem = pumpkin.relative(Direction.SOUTH);
        helper.setBlock(pumpkinStem, Blocks.ATTACHED_PUMPKIN_STEM.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        helper.setBlock(pumpkinStem.above(), Blocks.STONE.defaultBlockState());

        Villager farmer = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        farmer.setOnGround(true);
        helper.runAfterDelay(1, () -> verifyOuterNavigationCandidate(helper, farmer, pumpkin));
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_navigation_regressions", timeoutTicks = 700)
    public static void normalVillagerAiHarvestsPumpkinAtAbsolutePosition(GameTestHelper helper) {
        // Keep the target well inside the disposable floor so ordinary villager
        // wandering cannot leave the loaded fixture before the custom work goal
        // gets its first route admission.
        BlockPos localPumpkin = new BlockPos(6, 1, 1);
        installFloor(helper, 20);
        installAttachedPumpkin(helper, localPumpkin);

        // This is deliberately a normal villager with normal goal/brain
        // registration. No goal is installed manually and the actor is never
        // moved to the selected endpoint after the journey starts.
        Villager farmer = helper.spawn(EntityType.VILLAGER, 2, 1, 1);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        farmer.setOnGround(true);
        // Normal villager work is schedule-gated; 1000 is before the work
        // window and makes this fixture measure idle scheduling instead of
        // navigation. Keep the actor in the daytime work window.
        helper.getLevel().setDayTime(5_000L);
        farmer.refreshBrain(helper.getLevel());
        helper.assertTrue(farmer.goalSelector.getAvailableGoals().stream()
                        .anyMatch(wrapper -> wrapper.getGoal() instanceof HarvestPumpkinGoal),
                "normal villager registration did not install the pumpkin goal");
        BlockPos absolutePumpkin = helper.absolutePos(localPumpkin);
        awaitAutonomousPumpkinHarvest(helper, farmer, absolutePumpkin, 680);
    }

    @GameTest(template = "empty_3x8x3", batch = "ecap_navigation_real_ladder", timeoutTicks = 1_000)
    public static void normalVillagerAiAscendsLadderAndCompletesUpperWork(GameTestHelper helper) {
        BlockPos pumpkin = installTwoLevelLadderPumpkin(helper, true);
        Villager farmer = spawnNormalFarmer(helper, new BlockPos(1, 1, 1));
        awaitRealLadderWork(helper, farmer, pumpkin, new RealLadderProgress(), 980, true);
    }

    @GameTest(template = "empty_3x8x3", batch = "ecap_navigation_real_ladder", timeoutTicks = 1_000)
    public static void normalVillagerAiDescendsLadderAndCompletesLowerWork(GameTestHelper helper) {
        BlockPos pumpkin = installTwoLevelLadderPumpkin(helper, false);
        Villager farmer = spawnNormalFarmer(helper, new BlockPos(1, 5, 1));
        awaitRealLadderWork(helper, farmer, pumpkin, new RealLadderProgress(), 980, false);
    }

    @GameTest(template = "empty_3x8x3", batch = "ecap_navigation_real_ladder", timeoutTicks = 1_000)
    public static void twoNormalVillagersContendForOneLadderAndOneWorkTarget(GameTestHelper helper) {
        BlockPos pumpkin = installTwoLevelLadderPumpkin(helper, true);
        Villager first = spawnNormalFarmer(helper, new BlockPos(1, 1, 1));
        Villager second = spawnNormalFarmer(helper, new BlockPos(0, 1, 1));
        BlockPos absolutePumpkin = helper.absolutePos(pumpkin);
        ContentionObservation observation = new ContentionObservation();
        helper.runAfterDelay(1, () -> awaitContendedLadderWork(
                helper, first, second, absolutePumpkin, observation, 980));
    }

    @GameTest(template = "empty_3x8x3", batch = "ecap_navigation_real_ladder", timeoutTicks = 1_200)
    public static void normalVillagerBlockedTopExitCancelsAndReplansWithoutCollision(
            GameTestHelper helper) {
        BlockPos pumpkin = installTwoLevelLadderPumpkin(helper, true);
        Villager farmer = spawnNormalFarmer(helper, new BlockPos(1, 1, 1));
        RealLadderProgress progress = new RealLadderProgress();
        awaitBlockedTopExitRecovery(helper, farmer, pumpkin, progress, 360);
    }

    @GameTest(template = "empty_3x8x3", batch = "ecap_navigation_real_ladder", timeoutTicks = 700)
    public static void stalePlannedLadderExitUsesValidatedFallback(GameTestHelper helper) {
        BlockPos ladderBase = installLadder(helper);
        Villager farmer = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 0);
        farmer.moveTo(ladderBase.getX() + 0.5D, ladderBase.getY(), ladderBase.getZ() + 0.5D,
                0.0F, 0.0F);
        BlockPos staleExit = new BlockPos(ladderBase.getX() + 1,
                ladderBase.getY() + 1, ladderBase.getZ());
        List<Node> nodes = verticalRoute(ladderBase, true);
        nodes.add(new Node(staleExit.getX(), staleExit.getY(), staleExit.getZ()));
        helper.assertTrue(farmer.getNavigation().moveTo(
                        new Path(nodes, staleExit, true), 1.0D),
                "stale-exit ladder route could not be installed");
        helper.runAfterDelay(1, () -> awaitStalePlannedExit(
                helper, farmer, ladderBase, staleExit, 550));
    }

    @GameTest(template = "empty_3x8x3", batch = "ecap_navigation_real_ladder", timeoutTicks = 1_200)
    public static void ladderDestructionReleasesLoadedEntityClaim(GameTestHelper helper) {
        BlockPos pumpkin = installTwoLevelLadderPumpkin(helper, true);
        Villager original = spawnNormalFarmer(helper, new BlockPos(1, 1, 1));
        awaitLadderDestructionBeforeCompletion(helper, original, pumpkin,
                new RealLadderProgress(), 360);
    }

    @GameTest(template = "empty_3x8x3", batch = "ecap_navigation_real_ladder", timeoutTicks = 1_200)
    public static void normalVillagerUnloadReloadReleasesLadderClaim(GameTestHelper helper) {
        BlockPos pumpkin = installTwoLevelLadderPumpkin(helper, true);
        Villager original = spawnNormalFarmer(helper, new BlockPos(1, 1, 1));
        awaitEntityUnloadBeforeCompletion(helper, original, pumpkin,
                new RealLadderProgress(), 360);
    }

    private static BlockPos installTwoLevelLadderPumpkin(GameTestHelper helper, boolean upper) {
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        for (int y = 0; y < 5; y++) {
            helper.setBlock(new BlockPos(1, 1 + y, 0), Blocks.LADDER.defaultBlockState()
                    .setValue(LadderBlock.FACING, Direction.SOUTH));
        }
        // The upper platform is supported beside the ladder, leaving the
        // ladder column itself open for a planner-generated vertical route.
        for (int x = 0; x < 3; x++) {
            for (int z = 1; z < 3; z++) {
                helper.setBlock(new BlockPos(x, 4, z), Blocks.STONE.defaultBlockState());
                helper.setBlock(new BlockPos(x, 5, z), Blocks.AIR.defaultBlockState());
                helper.setBlock(new BlockPos(x, 6, z), Blocks.AIR.defaultBlockState());
            }
        }
        BlockPos pumpkin = upper ? new BlockPos(1, 5, 2) : new BlockPos(2, 1, 2);
        BlockPos stem = upper ? pumpkin.relative(Direction.WEST) : pumpkin.relative(Direction.WEST);
        helper.setBlock(pumpkin, Blocks.PUMPKIN.defaultBlockState());
        helper.setBlock(stem, Blocks.ATTACHED_PUMPKIN_STEM.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING,
                        upper ? Direction.EAST : Direction.EAST));
        return pumpkin;
    }

    private static Villager spawnNormalFarmer(GameTestHelper helper, BlockPos localPosition) {
        Villager farmer = helper.spawn(EntityType.VILLAGER, localPosition.getX(),
                localPosition.getY(), localPosition.getZ());
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        farmer.setOnGround(true);
        // Keep every real farmer fixture inside the vanilla daytime work
        // window so ladder traversal is the synchronization under test.
        helper.getLevel().setDayTime(5_000L);
        farmer.refreshBrain(helper.getLevel());
        helper.assertTrue(farmer.goalSelector.getAvailableGoals().stream()
                        .anyMatch(wrapper -> wrapper.getGoal() instanceof HarvestPumpkinGoal),
                "normal villager registration did not install the pumpkin goal");
        return farmer;
    }

    private static void awaitRealLadderWork(GameTestHelper helper, Villager farmer,
                                             BlockPos localPumpkin, RealLadderProgress progress,
                                             int remainingTicks, boolean ascending) {
        BlockPos pumpkin = helper.absolutePos(localPumpkin);
        progress.observe(farmer, pumpkin, helper.absolutePos(new BlockPos(1, 1, 0)));
        if (helper.getLevel().getBlockState(pumpkin).isAir()
                && farmer.getInventory().countItem(net.minecraft.world.item.Items.PUMPKIN) > 0) {
            helper.assertTrue(progress.sawClimbable,
                    "real farmer completed the work without entering the ladder route");
            helper.assertTrue(ascending ? progress.maximumY >= pumpkin.getY() : progress.minimumY <= pumpkin.getY(),
                    "real farmer did not complete the expected ladder direction: "
                            + farmer.position() + " progress=" + progress);
            helper.assertTrue(progress.sawBeyondLadder,
                    "real farmer harvested the target without being observed beyond the ladder");
            helper.assertTrue(progress.sawSupportedCollisionFreeFooting,
                    "real farmer harvested the target without supported collision-free footing beyond the ladder");
            // The target is deliberately beyond the ladder and the normal
            // HarvestPumpkinGoal has just completed its real interaction. That
            // completion is the durable route-exit assertion; the sampled
            // position is retained only as diagnostic progress because a
            // villager may take its next ordinary step in the same server tick.
            awaitClaimsReleasedAfterSuccess(helper, 80);
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("normal villager AI did not complete ladder work: pos=" + farmer.position()
                    + " pumpkin=" + helper.getLevel().getBlockState(pumpkin)
                    + " progress=" + progress);
            return;
        }
        helper.runAfterDelay(1, () -> awaitRealLadderWork(
                helper, farmer, localPumpkin, progress, remainingTicks - 1, ascending));
    }

    private static void awaitClaimsReleasedAfterSuccess(GameTestHelper helper, int remainingTicks) {
        if (VillagerLadderClaims.claimCount() == 0) {
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("successful real ladder work retained a ladder claim: "
                    + VillagerLadderClaims.claimCount());
            return;
        }
        helper.runAfterDelay(1, () -> awaitClaimsReleasedAfterSuccess(helper, remainingTicks - 1));
    }

    private static void awaitBlockedTopExitRecovery(GameTestHelper helper, Villager farmer,
                                                     BlockPos localPumpkin,
                                                     RealLadderProgress progress,
                                                     int remainingTicks) {
        BlockPos pumpkin = helper.absolutePos(localPumpkin);
        progress.observe(farmer, pumpkin, helper.absolutePos(new BlockPos(1, 1, 0)));
        BlockPos selectedExit = VillagerLadderClaims.activeExit(farmer);
        if (progress.sawClimbable && selectedExit != null
                && VillagerLadderClaims.claimCount() > 0
                && farmer.getNavigation().isDone()
                && !helper.getLevel().getBlockState(pumpkin).isAir()) {
            helper.assertTrue(VillagerLadderClaims.claimedColumn(farmer) != null,
                    "blocked-exit test did not observe an active ladder claim");
            progress.sawActiveDismount = true;
            progress.selectedExit = selectedExit;
            helper.getLevel().setBlock(selectedExit, Blocks.STONE.defaultBlockState(), 3);
            helper.runAfterDelay(35, () -> awaitBlockedExitClaimRelease(helper, farmer,
                    localPumpkin, progress, 80));
            return;
        }
        if (helper.getLevel().getBlockState(pumpkin).isAir()) {
            helper.fail("real farmer completed the ladder job before the blocked-exit interruption");
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("real farmer never entered the ladder before the blocked-exit interruption: "
                    + farmer.position() + " progress=" + progress);
            return;
        }
        helper.runAfterDelay(1, () -> awaitBlockedTopExitRecovery(
                helper, farmer, localPumpkin, progress, remainingTicks - 1));
    }

    private static void awaitStalePlannedExit(GameTestHelper helper, Villager farmer,
                                               BlockPos ladderBase, BlockPos staleExit,
                                               int remainingTicks) {
        BlockPos activeExit = VillagerLadderClaims.activeExit(farmer);
        if (activeExit != null) {
            helper.assertTrue(!activeExit.equals(staleExit),
                    "stale planned route exit was used for ladder dismount: " + activeExit);
            helper.assertTrue(activeExit.equals(ladderBase.relative(Direction.SOUTH).above(4)),
                    "validated fallback did not use the connected supported endpoint: "
                            + activeExit);
            helper.assertTrue(helper.getLevel().noCollision(farmer, farmer.getBoundingBox()),
                    "validated ladder fallback left the villager colliding");
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("stale planned ladder exit never reached validated dismount: pos="
                    + farmer.position() + " staleExit=" + staleExit);
            return;
        }
        helper.runAfterDelay(1, () -> awaitStalePlannedExit(
                helper, farmer, ladderBase, staleExit, remainingTicks - 1));
    }

    private static void awaitBlockedExitClaimRelease(GameTestHelper helper, Villager farmer,
                                                       BlockPos localPumpkin, RealLadderProgress progress,
                                                       int remainingTicks) {
        progress.observe(farmer, helper.absolutePos(localPumpkin),
                helper.absolutePos(new BlockPos(1, 1, 0)));
        if (VillagerLadderClaims.claimCount() == 0) {
            helper.assertTrue(helper.getLevel().noCollision(farmer, farmer.getBoundingBox()),
                    "blocked top exit left the real villager colliding");
            if (progress.selectedExit != null) {
                helper.getLevel().setBlock(progress.selectedExit, Blocks.AIR.defaultBlockState(), 3);
            }
            awaitRealLadderWork(helper, farmer, localPumpkin, progress, 700, true);
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("blocked top exit did not release its ladder claim before replan: "
                    + VillagerLadderClaims.claimCount());
            return;
        }
        helper.runAfterDelay(1, () -> awaitBlockedExitClaimRelease(
                helper, farmer, localPumpkin, progress, remainingTicks - 1));
    }

    private static void awaitLadderDestructionBeforeCompletion(GameTestHelper helper,
                                                                 Villager farmer,
                                                                 BlockPos localPumpkin,
                                                                 RealLadderProgress progress,
                                                                 int remainingTicks) {
        BlockPos pumpkin = helper.absolutePos(localPumpkin);
        progress.observe(farmer, pumpkin, helper.absolutePos(new BlockPos(1, 1, 0)));
        if (progress.sawClimbable && !helper.getLevel().getBlockState(pumpkin).isAir()) {
            // This is a real-world interruption: remove the ladder while the
            // normal farmer is pursuing its planner-selected work target.
            for (int y = 1; y <= 5; y++) {
                helper.setBlock(new BlockPos(1, y, 0), Blocks.AIR.defaultBlockState());
            }
            awaitLadderDestructionCleanup(helper, farmer, 80);
            return;
        }
        if (helper.getLevel().getBlockState(pumpkin).isAir()) {
            helper.fail("real farmer completed before the ladder-destruction interruption");
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("real farmer never entered the ladder before ladder destruction: "
                    + farmer.position() + " progress=" + progress);
            return;
        }
        helper.runAfterDelay(1, () -> awaitLadderDestructionBeforeCompletion(
                helper, farmer, localPumpkin, progress, remainingTicks - 1));
    }

    private static void awaitLadderDestructionCleanup(GameTestHelper helper, Villager villager,
                                                       int remainingTicks) {
        helper.assertTrue(villager.isAlive(),
                "ladder destruction unexpectedly unloaded the original villager");
        helper.assertTrue(helper.getLevel().noCollision(villager, villager.getBoundingBox()),
                "ladder destruction left the original villager colliding");
        if (VillagerLadderClaims.claimCount() == 0) {
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("ladder destruction did not release the loaded villager claim: "
                    + VillagerLadderClaims.claimCount());
            return;
        }
        helper.runAfterDelay(1, () -> awaitLadderDestructionCleanup(
                helper, villager, remainingTicks - 1));
    }

    private static void awaitEntityUnloadBeforeCompletion(GameTestHelper helper, Villager villager,
                                                            BlockPos localPumpkin,
                                                            RealLadderProgress progress,
                                                            int remainingTicks) {
        BlockPos pumpkin = helper.absolutePos(localPumpkin);
        progress.observe(villager, pumpkin, helper.absolutePos(new BlockPos(1, 1, 0)));
        if (progress.sawClimbable && VillagerLadderClaims.claimCount() > 0
                && !helper.getLevel().getBlockState(pumpkin).isAir()) {
            villager.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            awaitReloadAfterUnload(helper, villager, localPumpkin, 80);
            return;
        }
        if (helper.getLevel().getBlockState(pumpkin).isAir()) {
            helper.fail("real farmer completed before the entity-unload interruption");
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("real farmer never entered the ladder before entity unload: "
                    + villager.position() + " progress=" + progress);
            return;
        }
        helper.runAfterDelay(1, () -> awaitEntityUnloadBeforeCompletion(
                helper, villager, localPumpkin, progress, remainingTicks - 1));
    }

    private static void awaitReloadAfterUnload(GameTestHelper helper, Villager unloaded,
                                                BlockPos localPumpkin, int remainingTicks) {
        if (VillagerLadderClaims.claimCount() == 0) {
                // Reload the entity/session and restore the same route fixture.
                for (int y = 0; y < 5; y++) {
                    helper.setBlock(new BlockPos(1, 1 + y, 0), Blocks.LADDER.defaultBlockState()
                            .setValue(LadderBlock.FACING, Direction.SOUTH));
                }
                Villager reloaded = spawnNormalFarmer(helper, new BlockPos(1, 1, 1));
                awaitRealLadderWork(helper, reloaded, localPumpkin,
                        new RealLadderProgress(), 1_100, true);
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("entity unload did not release its ladder claim: " + unloaded.getUUID());
            return;
        }
        helper.runAfterDelay(1, () -> awaitReloadAfterUnload(
                helper, unloaded, localPumpkin, remainingTicks - 1));
    }

    private static void awaitContendedLadderWork(GameTestHelper helper, Villager first,
                                                  Villager second, BlockPos pumpkin,
                                                  ContentionObservation observation,
                                                  int remainingTicks) {
        observation.observe(helper, first, second);
        observation.releaseClaimHolderAfterWaitingRoute();
        if (helper.getLevel().getBlockState(pumpkin).isAir()) {
            int harvested = first.getInventory().countItem(net.minecraft.world.item.Items.PUMPKIN)
                    + second.getInventory().countItem(net.minecraft.world.item.Items.PUMPKIN);
            helper.assertValueEqual(harvested, 1,
                    "two normal villagers harvested the contended work target more than once");
            helper.assertTrue(observation.sawWaitingRoute,
                    "waiting villager did not retain its route while the ladder was claimed: "
                            + observation);
            helper.assertTrue(observation.sawWaiterProceedAfterRelease,
                    "waiting villager did not proceed onto the ladder after claim release: "
                            + observation);
            awaitContendedClaimsReleased(helper, 120);
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("two normal villagers did not complete contended ladder work: first="
                    + first.position() + " second=" + second.position()
                    + " pumpkin=" + helper.getLevel().getBlockState(pumpkin));
            return;
        }
        helper.runAfterDelay(1, () -> awaitContendedLadderWork(
                helper, first, second, pumpkin, observation, remainingTicks - 1));
    }

    private static void awaitContendedClaimsReleased(GameTestHelper helper, int remainingTicks) {
        if (VillagerLadderClaims.claimCount() == 0) {
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("contended ladder work retained claims after the target completed: "
                    + VillagerLadderClaims.claimCount());
            return;
        }
        helper.runAfterDelay(1, () -> awaitContendedClaimsReleased(helper, remainingTicks - 1));
    }

    private static final class RealLadderProgress {
        private int maximumY = Integer.MIN_VALUE;
        private int minimumY = Integer.MAX_VALUE;
        private boolean sawClimbable;
        private boolean sawBeyondLadder;
        private boolean sawSupportedCollisionFreeFooting;
        private boolean sawActiveDismount;
        private BlockPos selectedExit;

        private void observe(Villager villager, BlockPos target, BlockPos ladderColumn) {
            maximumY = Math.max(maximumY, villager.blockPosition().getY());
            minimumY = Math.min(minimumY, villager.blockPosition().getY());
            sawClimbable |= villager.onClimbable();
            BlockPos feet = villager.blockPosition();
            boolean beyond = feet.getY() == target.getY()
                    && (feet.getX() != ladderColumn.getX() || feet.getZ() != ladderColumn.getZ())
                    && villager.distanceToSqr(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D)
                    <= 6.25D;
            sawBeyondLadder |= beyond;
            if (beyond) {
                BlockState support = villager.level().getBlockState(feet.below());
                boolean supported = support.isFaceSturdy(villager.level(), feet.below(), Direction.UP)
                        || support.is(BlockTags.CLIMBABLE);
                boolean clear = villager.level().getBlockState(feet).getCollisionShape(
                                villager.level(), feet).isEmpty()
                        && villager.level().getBlockState(feet.above()).getCollisionShape(
                                villager.level(), feet.above()).isEmpty();
                sawSupportedCollisionFreeFooting |= supported && clear
                        && villager.level().noCollision(villager, villager.getBoundingBox());
            }
        }

        @Override
        public String toString() {
            return "RealLadderProgress{maximumY=" + maximumY + ", minimumY=" + minimumY
                    + ", sawClimbable=" + sawClimbable + ", sawBeyondLadder="
                    + sawBeyondLadder + ", sawSupportedCollisionFreeFooting="
                    + sawSupportedCollisionFreeFooting + ", sawActiveDismount="
                    + sawActiveDismount + ", selectedExit=" + selectedExit + '}';
        }
    }

    private static final class ContentionObservation {
        private boolean sawFirstClaim;
        private boolean sawSecondClaim;
        private boolean sawWaitingRoute;
        private boolean sawReleasedClaim;
        private boolean sawWaiterProceedAfterRelease;
        private Villager waitingVillager;
        private Villager claimHolder;
        private boolean releasedClaimHolder;

        private void observe(GameTestHelper helper, Villager first, Villager second) {
            VillagerLadderClaims.LadderColumn firstClaim =
                    VillagerLadderClaims.claimedColumn(first);
            VillagerLadderClaims.LadderColumn secondClaim =
                    VillagerLadderClaims.claimedColumn(second);
            sawFirstClaim |= firstClaim != null;
            sawSecondClaim |= secondClaim != null;
            if (firstClaim != null && firstClaim.equals(secondClaim)) {
                helper.fail("two normal villagers simultaneously occupied one claimed ladder segment");
                return;
            }
            boolean firstWaiting = firstClaim != null && secondClaim == null
                    && retainsRoute(second);
            boolean secondWaiting = secondClaim != null && firstClaim == null
                    && retainsRoute(first);
            if (firstWaiting) {
                waitingVillager = second;
                claimHolder = first;
            } else if (secondWaiting) {
                waitingVillager = first;
                claimHolder = second;
            }
            sawWaitingRoute |= waitingVillager != null;
            if (waitingVillager != null && VillagerLadderClaims.claimedColumn(waitingVillager) != null) {
                sawWaiterProceedAfterRelease = true;
            }
        }

        private void releaseClaimHolderAfterWaitingRoute() {
            if (releasedClaimHolder || !sawWaitingRoute || claimHolder == null) {
                return;
            }
            VillagerLadderClaims.releaseAll(claimHolder);
            MovementRequestHandle handle = MovementRequestManager.activeHandle(claimHolder);
            if (handle != null) {
                MovementRequestManager.cancel(claimHolder, handle,
                        MovementRequestTerminationReason.ABANDONED);
            }
            claimHolder.discard();
            releasedClaimHolder = true;
            sawReleasedClaim = true;
        }

        private static boolean retainsRoute(Villager villager) {
            return MovementRequestManager.activeHandle(villager) != null
                    || villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                    || (villager.getNavigation().getPath() != null
                    && !villager.getNavigation().isDone());
        }

        @Override
        public String toString() {
            return "ContentionObservation{firstClaim=" + sawFirstClaim
                    + ", secondClaim=" + sawSecondClaim + ", waitingRoute="
                    + sawWaitingRoute + ", released=" + sawReleasedClaim
                    + ", waiterProceeded=" + sawWaiterProceedAfterRelease + '}';
        }
    }

    private static void verifyOuterNavigationCandidate(GameTestHelper helper, Villager farmer,
                                                        BlockPos pumpkin) {
        BlockPos absolutePumpkin = helper.absolutePos(pumpkin);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        farmer.setOnGround(true);
        HarvestPumpkinGoal goal = new HarvestPumpkinGoal(farmer);
        helper.assertTrue(goal.canUse(), "farmer did not select the attached pumpkin");
        BlockPos selected = VillagerNavigationTargets.findReachableTarget(
                farmer, absolutePumpkin, 2);
        helper.assertTrue(selected != null && selected.getX() == absolutePumpkin.getX() - 1,
                "bounded search did not reserve an outer standing candidate: " + selected
                        + " at=" + farmer.blockPosition());

        goal.start();
        farmer.moveTo(selected.getX() + 0.5D, selected.getY(), selected.getZ() + 0.5D,
                0.0F, 0.0F);
        goal.tick();
        helper.assertTrue(helper.getLevel().getBlockState(absolutePumpkin).isAir(),
                "the selected navigation endpoint did not allow the farmer to execute the job");
        goal.stop();
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_navigation_regressions", timeoutTicks = 260)
    public static void inaccessiblePumpkinCooldownAllowsFartherTargetAndExpires(GameTestHelper helper) {
        BlockPos nearby = new BlockPos(6, 1, 1);
        BlockPos farther = new BlockPos(12, 1, 1);
        installFloor(helper, 20);
        installAttachedPumpkin(helper, nearby);
        installAttachedPumpkin(helper, farther);
        BlockPos absoluteNearby = helper.absolutePos(nearby);
        helper.assertTrue(helper.getLevel().getBlockState(absoluteNearby).is(Blocks.PUMPKIN)
                        && helper.getLevel().getBlockState(absoluteNearby.relative(Direction.SOUTH))
                        .is(Blocks.ATTACHED_PUMPKIN_STEM)
                        && helper.getLevel().getBlockState(absoluteNearby.relative(Direction.SOUTH))
                        .getValue(HorizontalDirectionalBlock.FACING) == Direction.NORTH,
                "nearby pumpkin fixture lost its attached stem: "
                        + helper.getLevel().getBlockState(absoluteNearby.relative(Direction.SOUTH)));

        // Remove every standing cell in the nearby target's search cube while
        // leaving the farther plant on the open side of the structure.
        for (int x = 4; x <= 8; x++) {
            for (int z = -1; z <= 3; z++) {
                if (x == nearby.getX() && z == nearby.getZ()) {
                    continue;
                }
                if (x == nearby.getX() && z == nearby.getZ() + 1) {
                    helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
                    continue;
                }
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
            }
        }
        helper.setBlock(nearby.relative(Direction.SOUTH), Blocks.ATTACHED_PUMPKIN_STEM.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        helper.setBlock(nearby.relative(Direction.SOUTH).above(), Blocks.STONE.defaultBlockState());

        Villager farmer = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        HarvestPumpkinGoal goal = new HarvestPumpkinGoal(farmer);
        helper.assertTrue(goal.canUse(), "nearby inaccessible pumpkin was not selected for the first attempt");
        helper.assertValueEqual(goal.getTargetPos(), helper.absolutePos(nearby),
                "the first pumpkin target was not the nearby plant");
        goal.start();
        helper.assertTrue(!goal.canContinueToUse(),
                "a pumpkin without a navigation target remained active");
        goal.stop();

        helper.assertTrue(goal.canUse(),
                "a farther reachable pumpkin was not attempted during the failed target cooldown");
        helper.assertValueEqual(goal.getTargetPos(), helper.absolutePos(farther),
                "failed nearby pumpkin prevented selection of the farther plant");
        goal.stop();

        for (int x = 4; x <= 8; x++) {
            for (int z = -1; z <= 3; z++) {
                if (x == nearby.getX() && z == nearby.getZ()) {
                    continue;
                }
                if (x == nearby.getX() && z == nearby.getZ() + 1) {
                    helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR.defaultBlockState());
                    continue;
                }
                helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR.defaultBlockState());
                helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR.defaultBlockState());
            }
        }
        helper.setBlock(nearby.relative(Direction.SOUTH), Blocks.ATTACHED_PUMPKIN_STEM.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(goal.canUse(),
                    "the previously obstructed pumpkin did not become eligible after cooldown expiry");
            helper.assertValueEqual(goal.getTargetPos(), helper.absolutePos(nearby),
                    "cooldown expiry did not restore the nearer pumpkin candidate");
            goal.stop();
            helper.succeed();
        });
    }

    @GameTest(template = "empty_3x8x3", batch = "ecap_navigation_regressions", timeoutTicks = 450)
    public static void watchdogDetectsStationaryAndOscillatingButAllowsDetourAndLadderProgress(
            GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        VillagerNavigationWatchdog watchdog = new VillagerNavigationWatchdog();
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 1));
        for (int tick = 0; tick < 100; tick++) {
            helper.assertFalse(watchdog.isStuck(villager, target),
                    "stationary villager timed out before the full watchdog window");
        }
        helper.assertTrue(watchdog.isStuck(villager, target),
                "stationary villager did not time out");

        watchdog.reset();
        for (int tick = 0; tick < 120; tick++) {
            double offset = (tick & 1) == 0 ? 0.5D : 0.0D;
            villager.moveTo(1.5D + offset, villager.getY(), 1.5D, 0.0F, 0.0F);
            if (watchdog.isStuck(villager, target)) {
                break;
            }
            if (tick == 119) {
                helper.fail("oscillating villager never timed out");
                return;
            }
        }

        watchdog.reset();
        for (int tick = 0; tick < 30; tick++) {
            villager.moveTo(1.5D, 1.0D + tick * 0.3D, 1.5D, 0.0F, 0.0F);
            helper.assertFalse(watchdog.isStuck(villager, target),
                    "legitimate ladder-like progress was treated as a straight-line stall");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x8x3", batch = "ecap_navigation_regressions", timeoutTicks = 600)
    public static void obstructedLadderExitReleasesClaimAndRecovers(GameTestHelper helper) {
        BlockPos ladderBase = installLadder(helper);
        Villager first = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 0);
        first.moveTo(ladderBase.getX() + 0.5D, ladderBase.getY() + 4.0D,
                ladderBase.getZ() + 0.5D,
                0.0F, 0.0F);
        BlockPos exit = ladderBase.relative(Direction.SOUTH).above(4);
        List<Node> nodes = new ArrayList<>();
        nodes.add(new Node(ladderBase.getX(), ladderBase.getY() + 3, ladderBase.getZ()));
        nodes.add(new Node(ladderBase.getX(), ladderBase.getY() + 4, ladderBase.getZ()));
        nodes.add(new Node(exit.getX(), exit.getY(), exit.getZ()));
        helper.assertTrue(first.getNavigation().moveTo(
                        new Path(nodes, exit, true), 1.0D),
                "obstructed-exit ladder route could not be installed");

        helper.runAfterDelay(1, () -> {
            helper.setBlock(new BlockPos(1, 5, 1), Blocks.STONE.defaultBlockState());
            helper.runAfterDelay(50, () -> {
                helper.assertTrue(helper.getLevel().noCollision(first, first.getBoundingBox()),
                        "villager retained a colliding bounding box after exit obstruction");
                helper.setBlock(new BlockPos(1, 5, 1), Blocks.AIR.defaultBlockState());
                Villager second = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 0);
                second.moveTo(ladderBase.getX() + 0.5D, ladderBase.getY(), ladderBase.getZ() + 0.5D,
                        0.0F, 0.0F);
                List<Node> retryNodes = verticalRoute(ladderBase, true);
                retryNodes.add(new Node(exit.getX(), exit.getY(), exit.getZ()));
                helper.assertTrue(second.getNavigation().moveTo(
                                new Path(retryNodes, exit, true), 1.0D),
                        "retry route could not be installed after the first exit recovered");
                awaitLadderClear(helper, second, ladderBase,
                        new LadderExitObservation(), 450, 0);
            });
        });
    }

    private static BlockPos installLadder(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 0));
        helper.setBlock(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE.defaultBlockState());
        for (int y = 0; y < 5; y++) {
            helper.setBlock(new BlockPos(1, 1 + y, 0), Blocks.LADDER.defaultBlockState()
                    .setValue(LadderBlock.FACING, Direction.SOUTH));
        }
        helper.setBlock(new BlockPos(1, 4, 1), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(1, 4, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(1, 5, 1), Blocks.AIR.defaultBlockState());
        helper.setBlock(new BlockPos(1, 5, 2), Blocks.AIR.defaultBlockState());
        helper.setBlock(new BlockPos(1, 6, 0), Blocks.AIR.defaultBlockState());
        helper.setBlock(new BlockPos(1, 6, 1), Blocks.AIR.defaultBlockState());
        helper.setBlock(new BlockPos(1, 6, 2), Blocks.AIR.defaultBlockState());
        helper.setBlock(new BlockPos(1, 7, 0), Blocks.AIR.defaultBlockState());
        helper.setBlock(new BlockPos(1, 7, 1), Blocks.AIR.defaultBlockState());
        helper.setBlock(new BlockPos(1, 7, 2), Blocks.AIR.defaultBlockState());
        return base;
    }

    private static List<Node> verticalRoute(BlockPos base, boolean ascending) {
        List<Node> nodes = new ArrayList<>();
        if (ascending) {
            for (int y = 0; y < 5; y++) {
                nodes.add(new Node(base.getX(), base.getY() + y, base.getZ()));
            }
        } else {
            for (int y = 3; y >= 0; y--) {
                nodes.add(new Node(base.getX(), base.getY() + y, base.getZ()));
            }
        }
        return nodes;
    }

    private static void installAttachedPumpkin(GameTestHelper helper, BlockPos pumpkin) {
        helper.setBlock(pumpkin, Blocks.PUMPKIN.defaultBlockState());
        helper.setBlock(pumpkin.relative(Direction.SOUTH), Blocks.ATTACHED_PUMPKIN_STEM.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
    }

    private static void installFloor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static void awaitLandingAndWork(GameTestHelper helper, Villager villager,
                                             BlockPos ladderBase, BlockPos workPosition,
                                             LadderExitObservation observation,
                                             int remainingTicks, boolean startedWork,
                                             int stableTicks) {
        if (observeLadderTopOscillation(helper, villager, ladderBase, observation)) {
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("villager did not leave the ladder and reach the offset workstation: "
                    + villager.position());
            return;
        }
        if (hasClearedLadder(villager, ladderBase) && !startedWork) {
            if (stableTicks < 20) {
                helper.runAfterDelay(1, () -> awaitLandingAndWork(
                        helper, villager, ladderBase, workPosition, observation,
                        remainingTicks - 1, false, stableTicks + 1));
                return;
            }
            // The landing bridge deliberately avoids teleporting the entity;
            // the first post-exit tick may still be airborne, so retry the
            // ordinary navigator until it can adopt the continuation route.
            boolean continuationStarted = villager.getNavigation().moveTo(
                    workPosition.getX() + 0.5D, workPosition.getY(),
                    workPosition.getZ() + 0.5D, 1.0D);
            helper.runAfterDelay(1, () -> awaitLandingAndWork(
                    helper, villager, ladderBase, workPosition, observation,
                    remainingTicks - 1, continuationStarted, 0));
            return;
        }
        if (startedWork && villager.blockPosition().distSqr(workPosition) <= 2.25D) {
            helper.succeed();
            return;
        }
        boolean nextStartedWork = startedWork;
        helper.runAfterDelay(1, () -> awaitLandingAndWork(
                helper, villager, ladderBase, workPosition, observation,
                remainingTicks - 1, nextStartedWork, 0));
    }

    private static void awaitAutonomousPumpkinHarvest(GameTestHelper helper, Villager farmer,
                                                       BlockPos absolutePumpkin,
                                                       int remainingTicks) {
        if (helper.getLevel().getBlockState(absolutePumpkin).isAir()
                && farmer.getInventory().countItem(net.minecraft.world.item.Items.PUMPKIN) > 0) {
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("normal villager AI did not harvest the absolute pumpkin position; villager="
                    + farmer.position() + " target=" + absolutePumpkin
                    + " block=" + helper.getLevel().getBlockState(absolutePumpkin));
            return;
        }
        helper.runAfterDelay(1, () -> awaitAutonomousPumpkinHarvest(
                helper, farmer, absolutePumpkin, remainingTicks - 1));
    }

    private static void awaitDescent(GameTestHelper helper, Villager villager,
                                      BlockPos ladderBase, int remainingTicks) {
        if (remainingTicks <= 0) {
            helper.fail("villager did not complete the ladder descent: " + villager.position());
            return;
        }
        if (hasClearedLadder(villager, ladderBase)
                && villager.blockPosition().getY() <= ladderBase.getY() + 1) {
            helper.succeed();
            return;
        }
        helper.runAfterDelay(1, () -> awaitDescent(helper, villager, ladderBase, remainingTicks - 1));
    }

    private static void awaitLadderClear(GameTestHelper helper, Villager villager,
                                          BlockPos ladderBase,
                                          LadderExitObservation observation,
                                          int remainingTicks, int stableTicks) {
        if (observeLadderTopOscillation(helper, villager, ladderBase, observation)) {
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("retry villager did not clear the ladder after claim recovery");
            return;
        }
        if (hasClearedLadder(villager, ladderBase)) {
            if (stableTicks >= 40) {
                helper.succeed();
                return;
            }
            helper.runAfterDelay(1, () -> awaitLadderClear(
                    helper, villager, ladderBase, observation, remainingTicks - 1, stableTicks + 1));
            return;
        }
        helper.runAfterDelay(1, () -> awaitLadderClear(
                helper, villager, ladderBase, observation, remainingTicks - 1, 0));
    }

    private static boolean hasClearedLadder(Villager villager, BlockPos ladderBase) {
        return !villager.onClimbable()
                && villager.blockPosition().getZ() > ladderBase.getZ();
    }

    private static boolean observeLadderTopOscillation(GameTestHelper helper, Villager villager,
                                                       BlockPos ladderBase,
                                                       LadderExitObservation observation) {
        double currentY = villager.getY();
        if (!observation.initialized) {
            observation.initialized = true;
            observation.lastY = currentY;
            return false;
        }

        double deltaY = currentY - observation.lastY;
        observation.lastY = currentY;
        boolean atLadderTop = villager.onClimbable()
                && currentY > ladderBase.getY() + 3.0D;
        if (!atLadderTop) {
            observation.lastDirection = 0;
            return false;
        }

        int direction = deltaY > 0.035D ? 1 : deltaY < -0.035D ? -1 : 0;
        if (direction != 0) {
            if (observation.lastDirection != 0 && direction != observation.lastDirection) {
                observation.reversals++;
            }
            observation.lastDirection = direction;
        }
        if (observation.reversals > 2) {
            helper.fail("villager oscillated at the ladder top instead of continuing: pos="
                    + villager.position() + ", reversals=" + observation.reversals
                    + ", path=" + villager.getNavigation().getPath());
            return true;
        }
        return false;
    }

    private static final class LadderExitObservation {
        private boolean initialized;
        private double lastY;
        private int lastDirection;
        private int reversals;
    }
}
