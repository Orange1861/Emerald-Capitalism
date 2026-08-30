package com.orangevillager61.emeraldcapitalism.test;

import com.mojang.authlib.GameProfile;
import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldSkrimisher;
import com.orangevillager61.emeraldcapitalism.entity.ai.HostileVillagePlayerTargetGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.HostileVillageMayorTargetGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.EmeraldGolemInteractWithOpenablesGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.EmeraldGolemRetreatGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.IronGolemInteractWithEmeraldDoorsGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.VaultGolemGoals;
import com.orangevillager61.emeraldcapitalism.event.EmeraldGolemEvents;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEntityTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class EmeraldGolemGameTests {

    private EmeraldGolemGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void emeraldGolemHasWanderGoal(GameTestHelper helper) {
        EmeraldGolem golem = ECAPEntityTypes.EMERALD_GOLEM.get().create(helper.getLevel());
        if (golem == null) {
            helper.fail("Could not create the emerald golem");
            return;
        }

        boolean hasWanderGoal = golem.goalSelector.getAvailableGoals().stream()
                .anyMatch(goal -> goal.getGoal() instanceof RandomStrollGoal);
        helper.assertTrue(hasWanderGoal, "emerald golem must register a random stroll goal");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void emeraldGolemDropsEmeraldsInsteadOfIron(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmeraldGolem golem = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (golem == null) {
            helper.fail("Could not create the emerald golem for the drop test");
            return;
        }

        golem.moveTo(1.5D, 1.0D, 1.5D, 0.0F, 0.0F);
        if (!level.addFreshEntity(golem)) {
            helper.fail("Could not add the emerald golem for the drop test");
            return;
        }
        golem.hurt(level.damageSources().generic(), 100.0F);

        List<ItemEntity> drops = level.getEntitiesOfClass(
                ItemEntity.class, golem.getBoundingBox().inflate(2.0D));
        helper.assertTrue(drops.stream().anyMatch(drop -> drop.getItem().is(net.minecraft.world.item.Items.EMERALD)),
                "emerald golem must drop emeralds");
        helper.assertFalse(drops.stream().anyMatch(drop -> drop.getItem().is(net.minecraft.world.item.Items.IRON_INGOT)),
                "emerald golem must not drop iron ingots");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void ambushEmeraldGolemDropsVillageMap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmeraldGolem golem = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (golem == null) {
            helper.fail("Could not create the ambush emerald golem for the drop test");
            return;
        }

        boolean previousMapDrop = Config.emeraldGolemAmbushDropsVillageMap;
        Config.emeraldGolemAmbushDropsVillageMap = true;
        try {
            golem.markAmbush();
            golem.moveTo(1.5D, 1.0D, 1.5D, 0.0F, 0.0F);
            if (!level.addFreshEntity(golem)) {
                helper.fail("Could not add the ambush emerald golem for the drop test");
                return;
            }
            golem.hurt(level.damageSources().generic(), 100.0F);

            List<ItemEntity> drops = level.getEntitiesOfClass(
                    ItemEntity.class, golem.getBoundingBox().inflate(2.0D));
            helper.assertTrue(drops.stream().anyMatch(drop -> drop.getItem()
                            .is(com.orangevillager61.emeraldcapitalism.registry.ECAPItems.VILLAGE_MAP.get())),
                    "an ambush emerald golem must drop a Village Map");
        } finally {
            Config.emeraldGolemAmbushDropsVillageMap = previousMapDrop;
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void ambushEmeraldGolemRetargetsRespawnedPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID playerId = UUID.randomUUID();
        ServerPlayer original = new ServerPlayer(
                level.getServer(), level,
                new GameProfile(playerId, "ecap-ambush-original"),
                ClientInformation.createDefault());
        ServerPlayer respawned = new ServerPlayer(
                level.getServer(), level,
                new GameProfile(playerId, "ecap-ambush-respawned"),
                ClientInformation.createDefault());
        EmeraldGolem golem = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (golem == null) {
            helper.fail("Could not create the ambush emerald golem for the respawn test");
            return;
        }

        golem.moveTo(helper.absolutePos(new BlockPos(1, 1, 1)), 0.0F, 0.0F);
        golem.armAmbush(original, 200);
        golem.setTarget(original);
        golem.setPersistentAngerTarget(playerId);
        golem.startPersistentAngerTimer();
        if (!level.addFreshEntity(golem)) {
            helper.fail("Could not add the ambush emerald golem for the respawn test");
            return;
        }

        EmeraldGolemEvents.onPlayerRespawn(new PlayerEvent.PlayerRespawnEvent(respawned, true));

        helper.assertTrue(golem.getTarget() == respawned,
                "ambush emerald golem must target the respawned player entity");
        helper.assertTrue(playerId.equals(golem.getPersistentAngerTarget()),
                "ambush emerald golem must preserve persistent anger toward the respawned player");
        helper.assertTrue(golem.getRemainingPersistentAngerTime() > 0,
                "ambush emerald golem must restart its persistent anger timer on respawn");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void ambushEmeraldGolemKeepsTargetAfterSaveReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID playerId = UUID.randomUUID();
        ServerPlayer target = new ServerPlayer(
                level.getServer(), level,
                new GameProfile(playerId, "ecap-ambush-persisted"),
                ClientInformation.createDefault());
        EmeraldGolem original = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (original == null) {
            helper.fail("Could not create the ambush emerald golem for the save test");
            return;
        }

        original.moveTo(helper.absolutePos(new BlockPos(1, 1, 1)), 0.0F, 0.0F);
        original.armAmbush(target, 120);
        CompoundTag saved = original.saveWithoutId(new CompoundTag());

        EmeraldGolem restored = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (restored == null) {
            helper.fail("Could not recreate the ambush emerald golem for the save test");
            return;
        }
        restored.load(saved);

        helper.assertTrue(restored.isAmbushFor(playerId),
                "a reloaded delayed ambush must retain its player association");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void vaultGolemsDoNotHaveWanderGoals(GameTestHelper helper) {
        EmeraldGolem emeraldGolem = ECAPEntityTypes.EMERALD_GOLEM.get().create(helper.getLevel());
        IronGolem ironGolem = EntityType.IRON_GOLEM.create(helper.getLevel());
        if (emeraldGolem == null || ironGolem == null) {
            helper.fail("Could not create golems for the Vault Golem wander test");
            return;
        }

        Component vaultName = Component.literal("Vault Golem");
        emeraldGolem.setCustomName(vaultName);
        ironGolem.setCustomName(vaultName);
        VaultGolemGoals.markAsVaultGuard(emeraldGolem);
        VaultGolemGoals.markAsVaultGuard(ironGolem);

        helper.assertFalse(hasWanderGoal(emeraldGolem),
                "a Vault Golem emerald golem must not have a random-stroll goal");
        helper.assertFalse(hasWanderGoal(ironGolem),
                "a Vault Golem iron golem must not have a random-stroll goal");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void vaultEmeraldGolemDoesNotRetreatButCanClimbLadder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ladderBase = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlock(ladderBase.below(), Blocks.STONE.defaultBlockState(), 3);
        for (int y = 0; y < 5; y++) {
            BlockPos ladderPos = ladderBase.above(y);
            level.setBlock(ladderPos, Blocks.LADDER.defaultBlockState()
                    .setValue(LadderBlock.FACING, Direction.SOUTH), 3);
        }

        EmeraldGolem golem = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (golem == null) {
            helper.fail("Could not create the vault emerald golem");
            return;
        }
        golem.setCustomName(Component.literal("Vault Golem"));
        golem.setBankEmployeePos(helper.absolutePos(new BlockPos(0, 1, 0)));
        VaultGolemGoals.markAsVaultGuard(golem);
        golem.moveTo(ladderBase.getX() + 0.5D, ladderBase.getY(), ladderBase.getZ() + 0.5D,
                0.0F, 0.0F);
        if (!level.addFreshEntity(golem)) {
            helper.fail("Could not add the vault emerald golem");
            return;
        }

        EmeraldGolemRetreatGoal.start(golem, helper.absolutePos(new BlockPos(0, 1, 0)));
        helper.assertTrue(golem.isVaultGuard(), "marked bank-assigned golem must be identified as a vault guard");
        helper.assertFalse(golem.goalSelector.getAvailableGoals().stream()
                        .anyMatch(goal -> goal.getGoal() instanceof EmeraldGolemRetreatGoal),
                "vault guard must not receive the retreat goal");

        List<Node> ladderNodes = new ArrayList<>();
        for (int y = 1; y < 5; y++) {
            ladderNodes.add(new Node(ladderBase.getX(), ladderBase.getY() + y, ladderBase.getZ()));
        }
        Path path = new Path(ladderNodes, ladderBase.above(4), true);
        helper.assertTrue(golem.getNavigation().moveTo(path, 1.0D),
                "vault guard test path could not be started");
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(golem.getY() > ladderBase.getY() + 1.0D,
                    "vault guard could not climb the ladder when its path required it");
            helper.succeed();
        });
    }

    private static boolean hasWanderGoal(IronGolem golem) {
        return golem.goalSelector.getAvailableGoals().stream()
                .anyMatch(goal -> goal.getGoal() instanceof RandomStrollGoal);
    }

    @GameTest(template = "empty_3x3x3")
    public static void emeraldGolemPathfindsVerticallyThroughLadders(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ladderBase = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlock(ladderBase.below(), Blocks.STONE.defaultBlockState(), 3);
        for (int y = 0; y < 5; y++) {
            BlockPos ladderPos = ladderBase.above(y);
            level.setBlock(ladderPos, Blocks.LADDER.defaultBlockState()
                    .setValue(LadderBlock.FACING, Direction.SOUTH), 3);
        }
        BlockPos target = ladderBase.above(4).east();
        level.setBlock(target.below(), Blocks.STONE.defaultBlockState(), 3);

        EmeraldGolem golem = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (golem == null) {
            helper.fail("Could not create the emerald golem for the ladder path test");
            return;
        }
        golem.moveTo(ladderBase.getX() + 0.5D, ladderBase.getY(), ladderBase.getZ() + 0.5D,
                0.0F, 0.0F);
        if (!level.addFreshEntity(golem)) {
            helper.fail("Could not add the emerald golem for the ladder path test");
            return;
        }
        golem.goalSelector.removeAllGoals(goal -> goal instanceof RandomStrollGoal);

        List<Node> ladderNodes = new ArrayList<>();
        for (int y = 1; y < 5; y++) {
            ladderNodes.add(new Node(ladderBase.getX(), ladderBase.getY() + y, ladderBase.getZ()));
        }
        Path path = new Path(ladderNodes, target, true);
        helper.assertTrue(golem.getNavigation().moveTo(path, 1.0D),
                "emerald golem could not start its ladder path");
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(golem.getY() > ladderBase.getY() + 1.0D,
                    "emerald golem did not climb the ladder");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_3x3x3")
    public static void ironGolemsReceiveHostileVillagePlayerTargetGoal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmeraldGolem emeraldGolem = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        IronGolem ironGolem = EntityType.IRON_GOLEM.create(level);
        if (emeraldGolem == null || ironGolem == null) {
            helper.fail("Could not create golems for hostile-player target registration test");
            return;
        }
        level.addFreshEntity(emeraldGolem);
        level.addFreshEntity(ironGolem);

        long emeraldHostileGoals = emeraldGolem.targetSelector.getAvailableGoals().stream()
                .filter(goal -> goal.getGoal() instanceof HostileVillagePlayerTargetGoal)
                .count();
        long ironHostileGoals = ironGolem.targetSelector.getAvailableGoals().stream()
                .filter(goal -> goal.getGoal() instanceof HostileVillagePlayerTargetGoal)
                .count();
        helper.assertValueEqual((int) emeraldHostileGoals, 1,
                "emerald golems must target hostile village players exactly once");
        helper.assertValueEqual((int) ironHostileGoals, 1,
                "iron golems must target hostile village players exactly once");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void onlyEmeraldGolemsTargetTheMayorDuringAnElection(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(1, 1, 1));
        VillageRecord village = VillageRegistryData.get(level).getOrCreateVillage(
                UUID.randomUUID(), center, new net.minecraft.world.phys.AABB(center).inflate(8.0D));
        var mayor = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.VILLAGER, 1, 1, 1);
        mayor.setVillagerData(mayor.getVillagerData().setProfession(ECAPVillagerProfessions.MAYOR.get()));
        UUID candidateId = UUID.randomUUID();
        helper.assertTrue(village.becomeGovernorCandidate(
                        candidateId, Config.governorCandidateOpinionThreshold + 1),
                "could not establish a governor candidate for the mayor-target test");

        EmeraldGolem emeraldGolem = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        EmeraldSkrimisher skrimisher = ECAPEntityTypes.EMERALD_SKRIMISHER.get().create(level);
        IronGolem ironGolem = EntityType.IRON_GOLEM.create(level);
        if (emeraldGolem == null || skrimisher == null || ironGolem == null) {
            helper.fail("Could not create golems for mayor hostility test");
            return;
        }

        emeraldGolem.moveTo(center.offset(0, 0, 2), 0.0F, 0.0F);
        skrimisher.moveTo(center.offset(2, 0, 0), 0.0F, 0.0F);
        ironGolem.moveTo(center.offset(-2, 0, 0), 0.0F, 0.0F);
        level.addFreshEntity(emeraldGolem);
        level.addFreshEntity(skrimisher);
        level.addFreshEntity(ironGolem);

        HostileVillageMayorTargetGoal emeraldGoal = findMayorGoal(emeraldGolem);
        HostileVillageMayorTargetGoal skrimisherGoal = findMayorGoal(skrimisher);
        helper.assertTrue(emeraldGoal != null,
                "emerald golems must register a mayor target goal");
        helper.assertTrue(skrimisherGoal != null,
                "skrimshers must inherit the mayor target goal");
        helper.assertValueEqual(countMayorGoals(ironGolem), 0,
                "ordinary iron golems must not register the mayor target goal");

        if (emeraldGoal == null || skrimisherGoal == null) {
            return;
        }
        boolean foundMayor = false;
        for (int attempt = 0; attempt < 40 && !foundMayor; attempt++) {
            foundMayor = emeraldGoal.canUse();
        }
        helper.assertTrue(foundMayor,
                "an emerald golem did not find the mayor while a candidate was registered");
        emeraldGoal.start();
        helper.assertTrue(emeraldGolem.getTarget() == mayor,
                "emerald golem did not target the village mayor during an election");

        village.clearGovernorCandidate();
        emeraldGoal.stop();
        helper.assertFalse(emeraldGoal.canUse(),
                "emerald golem remained hostile to the mayor after the candidate was removed");
        helper.succeed();
    }

    private static HostileVillageMayorTargetGoal findMayorGoal(IronGolem golem) {
        return golem.targetSelector.getAvailableGoals().stream()
                .map(goal -> goal.getGoal())
                .filter(HostileVillageMayorTargetGoal.class::isInstance)
                .map(HostileVillageMayorTargetGoal.class::cast)
                .findFirst()
                .orElse(null);
    }

    private static int countMayorGoals(IronGolem golem) {
        return (int) golem.targetSelector.getAvailableGoals().stream()
                .filter(goal -> goal.getGoal() instanceof HostileVillageMayorTargetGoal)
                .count();
    }

    @GameTest(template = "empty_3x3x3")
    public static void golemsReceiveSubtypeAppropriateOpenablesGoal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmeraldGolem emeraldGolem = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        IronGolem ironGolem = EntityType.IRON_GOLEM.create(level);
        if (emeraldGolem == null || ironGolem == null) {
            helper.fail("Could not create golems for openables-goal registration test");
            return;
        }
        level.addFreshEntity(emeraldGolem);
        level.addFreshEntity(ironGolem);

        long emeraldOpenablesGoals = emeraldGolem.goalSelector.getAvailableGoals().stream()
                .filter(goal -> goal.getGoal() instanceof EmeraldGolemInteractWithOpenablesGoal)
                .count();
        long emeraldDoorOnlyGoals = emeraldGolem.goalSelector.getAvailableGoals().stream()
                .filter(goal -> goal.getGoal() instanceof IronGolemInteractWithEmeraldDoorsGoal)
                .count();
        long ironOpenablesGoals = ironGolem.goalSelector.getAvailableGoals().stream()
                .filter(goal -> goal.getGoal() instanceof EmeraldGolemInteractWithOpenablesGoal)
                .count();
        long ironDoorOnlyGoals = ironGolem.goalSelector.getAvailableGoals().stream()
                .filter(goal -> goal.getGoal() instanceof IronGolemInteractWithEmeraldDoorsGoal)
                .count();

        helper.assertTrue(emeraldOpenablesGoals == 1,
                "emerald golem must register exactly one broad openables goal");
        helper.assertTrue(emeraldDoorOnlyGoals == 0,
                "emerald golem must not also register the competing iron-golem door goal");
        helper.assertTrue(ironOpenablesGoals == 0,
                "ordinary iron golem must not register the emerald-golem openables goal");
        helper.assertTrue(ironDoorOnlyGoals == 1,
                "ordinary iron golem must retain exactly one emerald-door goal");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void ironGolemReachesTargetsAboveItsHead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        IronGolem golem = EntityType.IRON_GOLEM.create(level);
        net.minecraft.world.entity.monster.Zombie target = EntityType.ZOMBIE.create(level);
        if (golem == null || target == null) {
            helper.fail("Could not create iron golem reach test entities");
            return;
        }

        BlockPos spawnPos = helper.absolutePos(new BlockPos(1, 1, 1));
        golem.moveTo(spawnPos, 0.0F, 0.0F);
        int configuredReach = Config.ironGolemVerticalReachAboveHead;
        helper.assertTrue(configuredReach >= 1 && configuredReach <= 16,
                "iron golem vertical reach config must stay within 1 to 16 blocks");
        double withinReachY = golem.getBoundingBox().maxY + configuredReach - 0.01D;
        target.moveTo(golem.getX(), withinReachY, golem.getZ(), 0.0F, 0.0F);
        helper.assertTrue(golem.isWithinMeleeAttackRange(target),
                "iron golem could not reach a target within its configured height range");

        target.moveTo(golem.getX(), golem.getBoundingBox().maxY + configuredReach + 0.01D,
                golem.getZ(), 0.0F, 0.0F);
        helper.assertFalse(golem.isWithinMeleeAttackRange(target),
                "iron golem reached beyond its configured height range");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void emeraldGolemAssignmentSurvivesEntitySaveReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmeraldGolem original = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (original == null) {
            helper.fail("Could not create the original emerald golem");
            return;
        }

        BlockPos spawnPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos bankPos = helper.absolutePos(new BlockPos(-2, 1, -2));
        original.moveTo(spawnPos, 0.0F, 0.0F);
        original.setPlayerCreated(true);
        original.setHealth(37.5F);
        original.setBankEmployeePos(bankPos);
        if (!level.addFreshEntity(original)) {
            helper.fail("Could not add the original emerald golem to the test level");
            return;
        }

        boolean playerCreatedBeforeSave = original.isPlayerCreated();
        float healthBeforeSave = original.getHealth();
        CompoundTag saved = original.saveWithoutId(new CompoundTag());
        long assignmentFieldCount = saved.getAllKeys().stream()
                .filter("bank_employee_pos"::equals)
                .count();

        original.discard();
        EmeraldGolem restored = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (restored == null) {
            helper.fail("Could not recreate the emerald golem for reload");
            return;
        }
        restored.load(saved);

        helper.assertTrue(assignmentFieldCount == 1,
                "bank employee assignment must be encoded exactly once");
        helper.assertTrue(bankPos.equals(restored.getBankEmployeePos()),
                "bank employee position did not survive entity save/reload");
        helper.assertTrue(restored.isPlayerCreated() == playerCreatedBeforeSave,
                "vanilla player-created state changed during mod state reload");
        helper.assertTrue(restored.getHealth() == healthBeforeSave,
                "vanilla golem health changed during mod state reload");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void malformedOrMissingBankEmployeeStateDefaultsToUnassigned(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmeraldGolem source = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (source == null) {
            helper.fail("Could not create the source emerald golem");
            return;
        }
        source.moveTo(helper.absolutePos(new BlockPos(1, 1, 1)), 0.0F, 0.0F);
        CompoundTag saved = source.saveWithoutId(new CompoundTag());

        EmeraldGolem missing = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (missing == null) {
            helper.fail("Could not create the missing-state emerald golem");
            return;
        }
        CompoundTag missingTag = saved.copy();
        missingTag.remove("bank_employee_pos");
        missing.load(missingTag);
        helper.assertTrue(missing.getBankEmployeePos() == null,
                "missing bank employee state must default to unassigned");

        EmeraldGolem malformed = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (malformed == null) {
            helper.fail("Could not create the malformed-state emerald golem");
            return;
        }
        CompoundTag malformedTag = saved.copy();
        malformedTag.putString("bank_employee_pos", "not-an-int-stream");
        try {
            malformed.load(malformedTag);
        } catch (RuntimeException ex) {
            helper.fail("Malformed bank employee state crashed entity reload: " + ex.getMessage());
            return;
        }
        helper.assertTrue(malformed.getBankEmployeePos() == null,
                "malformed bank employee state must default to unassigned");
        helper.succeed();
    }
}
