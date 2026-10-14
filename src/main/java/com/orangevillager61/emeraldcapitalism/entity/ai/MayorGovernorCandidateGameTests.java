package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.mojang.authlib.GameProfile;
import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import com.orangevillager61.emeraldcapitalism.world.village.VillageGovernance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.Map;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class MayorGovernorCandidateGameTests {

    private MayorGovernorCandidateGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void mayorStopsFollowingWhenCandidateIsUnreachable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager mayor = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        mayor.setVillagerData(mayor.getVillagerData().setProfession(ECAPVillagerProfessions.MAYOR.get()));

        ServerPlayer candidate = createCandidate(level, mayor.blockPosition().above(20));
        VillageRecord village = createVillage(level, mayor.blockPosition());
        helper.assertTrue(village.becomeGovernorCandidate(
                        candidate.getUUID(), Config.governorCandidateOpinionThreshold + 1,
                        level.getGameTime()),
                "could not start the governor candidate process for the unreachable-path test");

        MayorFollowGovernorCandidateGoal goal = new MayorFollowGovernorCandidateGoal(mayor, candidate);
        helper.assertTrue(goal.canUse(), "Mayor did not detect the started candidate process");
        goal.start();
        helper.assertFalse(goal.canContinueToUse(),
                "Mayor continued following after pathing to the candidate failed");
        helper.assertFalse(goal.canUse(),
                "Mayor immediately retried an unreachable candidate instead of honoring cooldown");
        helper.assertTrue(mayor.getNavigation().isDone(),
                "Mayor navigation remained active after pathing failure");
        goal.stop();
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", timeoutTicks = 500)
    public static void mayorFollowsMovingReachableCandidateWithBoundedReplanning(
            GameTestHelper helper) {
        installFloor(helper, 20);
        ServerLevel level = helper.getLevel();
        Villager mayor = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        mayor.goalSelector.removeAllGoals(ignored -> true);
        mayor.setVillagerData(mayor.getVillagerData().setProfession(ECAPVillagerProfessions.MAYOR.get()));

        BlockPos candidateStart = helper.absolutePos(new BlockPos(12, 1, 1));
        ServerPlayer candidate = createCandidate(level, candidateStart);
        VillageRecord village = createVillage(level, mayor.blockPosition());
        helper.assertTrue(village.becomeGovernorCandidate(
                        candidate.getUUID(), Config.governorCandidateOpinionThreshold + 1,
                        level.getGameTime()),
                "could not start the reachable moving-candidate process");

        MayorFollowGovernorCandidateGoal goal = new MayorFollowGovernorCandidateGoal(mayor, candidate);
        helper.assertTrue(goal.canUse(), "Mayor did not detect the reachable candidate");
        Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before =
                PerformanceTimingCounters.snapshot();
        double startX = mayor.getX();
        goal.start();
        tickMovingCandidate(helper, goal, mayor, candidate, before, startX, 0);
    }

    @GameTest(template = "empty_3x3x3")
    public static void governorClaimRewardsMayorWithReputation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager mayor = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        mayor.setVillagerData(mayor.getVillagerData().setProfession(ECAPVillagerProfessions.MAYOR.get()));

        ServerPlayer candidate = createCandidate(level, mayor.blockPosition());
        VillageRecord village = createVillage(level, mayor.blockPosition());
        helper.assertTrue(village.becomeGovernorCandidate(
                        candidate.getUUID(), Config.governorCandidateOpinionThreshold + 1,
                        level.getGameTime()),
                "could not start the governor claim reputation test");

        helper.assertTrue(VillageGovernance.refresh(level, village),
                "governor claim did not promote the candidate");
        helper.assertTrue(village.isGovernor(candidate.getUUID()),
                "candidate was not appointed Governor");
        helper.assertValueEqual(mayor.getPlayerReputation(candidate), 100,
                "governor claim did not add 100 reputation to the Mayor");
        helper.succeed();
    }

    private static VillageRecord createVillage(ServerLevel level, BlockPos mayorPos) {
        return VillageRegistryData.get(level).getOrCreateVillage(
                UUID.randomUUID(), mayorPos, new AABB(mayorPos).inflate(16.0D));
    }

    private static ServerPlayer createCandidate(ServerLevel level, BlockPos position) {
        ServerPlayer candidate = new ServerPlayer(
                level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "ecap-governor-candidate-test"),
                ClientInformation.createDefault());
        candidate.setPos(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
        return candidate;
    }

    private static void tickMovingCandidate(
            GameTestHelper helper,
            MayorFollowGovernorCandidateGoal goal,
            Villager mayor,
            ServerPlayer candidate,
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before,
            double startingX,
            int elapsedTicks) {
        if (elapsedTicks == 12) {
            candidate.setPos(candidate.getX(), candidate.getY(), candidate.getZ() + 2.0D);
        }
        goal.tick();
        if (mayor.distanceToSqr(candidate) <= 9.0D) {
            helper.assertFalse(goal.canContinueToUse(),
                    "Mayor did not report arrival when it reached the moving candidate");
            helper.assertTrue(Math.abs(mayor.getX() - startingX) > 1.0D,
                    "Mayor did not move during the reachable candidate journey");
            PerformanceTimingCounters.Snapshot replans =
                    PerformanceTimingCounters.snapshot().get(
                            PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_REPLAN);
            PerformanceTimingCounters.Snapshot previous = before.get(
                    PerformanceTimingCounters.Operation.MOVEMENT_REQUEST_REPLAN);
            helper.assertTrue(replans.calls() - previous.calls() <= 4,
                    "moving-candidate follow replanned without a bound: "
                            + (replans.calls() - previous.calls()));
            helper.assertTrue(MovementRequestManager.activeHandle(mayor) == null,
                    "Mayor arrival left its movement request active");
            helper.succeed();
            return;
        }
        if (elapsedTicks >= 400) {
            helper.fail("Mayor did not reach moving candidate: mayor=" + mayor.blockPosition()
                    + " candidate=" + candidate.blockPosition());
            return;
        }
        helper.runAfterDelay(1, () -> tickMovingCandidate(
                helper, goal, mayor, candidate, before, startingX, elapsedTicks + 1));
    }

    private static void installFloor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
    }
}
