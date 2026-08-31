package com.orangevillager61.emeraldcapitalism.entity.ai;

import com.mojang.authlib.GameProfile;
import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
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
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

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
                        candidate.getUUID(), Config.governorCandidateOpinionThreshold + 1),
                "could not start the governor candidate process for the unreachable-path test");

        MayorFollowGovernorCandidateGoal goal = new MayorFollowGovernorCandidateGoal(mayor, candidate);
        helper.assertTrue(goal.canUse(), "Mayor did not detect the started candidate process");
        goal.start();
        helper.assertFalse(goal.canContinueToUse(),
                "Mayor continued following after pathing to the candidate failed");
        helper.assertTrue(mayor.getNavigation().isDone(),
                "Mayor navigation remained active after pathing failure");
        goal.stop();
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void governorClaimRewardsMayorWithReputation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager mayor = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        mayor.setVillagerData(mayor.getVillagerData().setProfession(ECAPVillagerProfessions.MAYOR.get()));

        ServerPlayer candidate = createCandidate(level, mayor.blockPosition());
        VillageRecord village = createVillage(level, mayor.blockPosition());
        helper.assertTrue(village.becomeGovernorCandidate(
                        candidate.getUUID(), Config.governorCandidateOpinionThreshold + 1),
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
}
