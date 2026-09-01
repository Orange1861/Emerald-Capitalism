package com.orangevillager61.emeraldcapitalism.test;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.behavior.AvoidBoatBehavior;
import com.orangevillager61.emeraldcapitalism.behavior.AvoidZombiePlagueBehavior;
import com.orangevillager61.emeraldcapitalism.behavior.BegForFoodBehavior;
import com.orangevillager61.emeraldcapitalism.behavior.BankAwareAssignProfessionFromJobSite;
import com.orangevillager61.emeraldcapitalism.behavior.BankAwarePotentialJobSiteBehavior;
import com.orangevillager61.emeraldcapitalism.behavior.FleeHostileVillagePlayerBehavior;
import com.orangevillager61.emeraldcapitalism.behavior.InteractWithFenceGateBehavior;
import com.orangevillager61.emeraldcapitalism.behavior.UseZombieSmellBehavior;
import com.orangevillager61.emeraldcapitalism.behavior.VillagerGoalPackageIntegration;
import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.entity.ai.VillagerNavigationWatchdog;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.util.VillagerFoodSelection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.VillagerGoalPackages;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class VillagerGoalBehaviorGameTests {

    private static final int BEGGING_PRIORITY = 5;
    private static final int BOAT_AVOIDANCE_PRIORITY = 5;
    private static final int ZOMBIE_PLAGUE_AVOIDANCE_PRIORITY = 4;
    private static final int ZOMBIE_SMELL_PRIORITY = 1;
    private static final int HOSTILE_VILLAGE_PLAYER_FLEE_PRIORITY = 2;
    private static final int FENCE_GATE_PRIORITY = 6;

    private VillagerGoalBehaviorGameTests() {
    }

    /**
     * This assertion is intentionally made through the vanilla factories. Removing
     * or silently missing any target injection makes this test fail.
     */
    @GameTest(template = "empty_3x3x3")
    public static void packageContentsAreExactAndConstructionDoesNotDuplicate(GameTestHelper helper) {
        for (VillagerProfession profession : List.of(
                VillagerProfession.NONE,
                VillagerProfession.FARMER,
                VillagerProfession.LIBRARIAN)) {
            for (int construction = 0; construction < 3; construction++) {
                ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> core =
                        VillagerGoalPackages.getCorePackage(profession, 0.5F);
                ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> idle =
                        VillagerGoalPackages.getIdlePackage(profession, 0.5F);
                ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> meet =
                        VillagerGoalPackages.getMeetPackage(profession, 0.5F);

                assertBehavior(helper, core, InteractWithFenceGateBehavior.class, FENCE_GATE_PRIORITY, 1);
                assertBehavior(helper, core, AvoidBoatBehavior.class, BOAT_AVOIDANCE_PRIORITY, 1);
                assertBehavior(helper, core, AvoidZombiePlagueBehavior.class,
                        ZOMBIE_PLAGUE_AVOIDANCE_PRIORITY, 1);
                assertBehavior(helper, core, UseZombieSmellBehavior.class,
                        ZOMBIE_SMELL_PRIORITY, 1);
                assertBehavior(helper, core, FleeHostileVillagePlayerBehavior.class,
                        HOSTILE_VILLAGE_PLAYER_FLEE_PRIORITY, 1);
                assertBehavior(helper, idle, BegForFoodBehavior.class, BEGGING_PRIORITY, 1);
                assertBehavior(helper, meet, BegForFoodBehavior.class, BEGGING_PRIORITY, 1);

                ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> coreAgain =
                        VillagerGoalPackageIntegration.addCoreBehaviors(core);
                ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> idleAgain =
                        VillagerGoalPackageIntegration.addIdleBehaviors(idle);
                ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>> meetAgain =
                        VillagerGoalPackageIntegration.addMeetBehaviors(meet);
                assertBehavior(helper, coreAgain, InteractWithFenceGateBehavior.class, FENCE_GATE_PRIORITY, 1);
                assertBehavior(helper, coreAgain, AvoidBoatBehavior.class, BOAT_AVOIDANCE_PRIORITY, 1);
                assertBehavior(helper, coreAgain, AvoidZombiePlagueBehavior.class,
                        ZOMBIE_PLAGUE_AVOIDANCE_PRIORITY, 1);
                assertBehavior(helper, coreAgain, UseZombieSmellBehavior.class,
                        ZOMBIE_SMELL_PRIORITY, 1);
                assertBehavior(helper, coreAgain, FleeHostileVillagePlayerBehavior.class,
                        HOSTILE_VILLAGE_PLAYER_FLEE_PRIORITY, 1);
                assertBehavior(helper, idleAgain, BegForFoodBehavior.class, BEGGING_PRIORITY, 1);
                assertBehavior(helper, meetAgain, BegForFoodBehavior.class, BEGGING_PRIORITY, 1);

                helper.assertValueEqual(core.size(), 25,
                        "core package no longer preserves the 1.21.1 vanilla entries plus five custom behaviors");
                helper.assertValueEqual(idle.size(), 9,
                        "idle package no longer preserves the 1.21.1 vanilla entries plus begging");
                helper.assertValueEqual(meet.size(), 10,
                        "meet package no longer preserves the 1.21.1 vanilla entries plus begging");
                helper.assertValueEqual(coreAgain.size(), core.size(),
                        "reapplying core integration duplicated a custom behavior");
                helper.assertValueEqual(idleAgain.size(), idle.size(),
                        "reapplying idle integration duplicated begging");
                helper.assertValueEqual(meetAgain.size(), meet.size(),
                        "reapplying meet integration duplicated begging");
            }
        }

        assertRepresentativeWorkPackage(helper, VillagerProfession.FARMER);
        assertRepresentativeWorkPackage(helper, VillagerProfession.LIBRARIAN);
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void bankerJobSiteUsesWorkSideBeforeAssignment(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(new BlockPos(10, 1, 10));
        helper.setBlock(new BlockPos(10, 1, 10), ECAPBlocks.BANK.get().defaultBlockState()
                .setValue(BankBlock.FACING, Direction.NORTH));

        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 10, 1, 9);
        villager.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE,
                GlobalPos.of(level.dimension(), bankPos));

        List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> core =
                VillagerGoalPackages.getCorePackage(VillagerProfession.NONE, 0.5F);
        BehaviorControl<Villager> goToPotentialJobSite = findBehavior(
                core, BankAwarePotentialJobSiteBehavior.class);
        BehaviorControl<Villager> assignProfession = findBehavior(
                core, BankAwareAssignProfessionFromJobSite.class);
        long gameTime = level.getGameTime();

        helper.assertTrue(goToPotentialJobSite.tryStart(level, villager, gameTime),
                "bank potential-job-site behavior did not start");
        BlockPos workPos = BankBlock.getBankerWorkPos(level.getBlockState(bankPos), bankPos);
        WalkTarget initialWalkTarget = villager.getBrain()
                .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        helper.assertTrue(initialWalkTarget != null,
                "bank potential-job-site behavior did not install a walk target on start");
        helper.assertValueEqual(initialWalkTarget.getTarget().currentBlockPosition(), workPos,
                "bank behavior targeted the solid bank block before its first tick");
        goToPotentialJobSite.tickOrStop(level, villager, gameTime + 1L);

        WalkTarget walkTarget = villager.getBrain()
                .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        helper.assertTrue(walkTarget != null,
                "bank potential-job-site behavior did not install a walk target");
        helper.assertValueEqual(walkTarget.getTarget().currentBlockPosition(), workPos,
                "bank candidate was routed to the deposit side instead of the banker work side");
        helper.assertFalse(assignProfession.tryStart(level, villager, gameTime + 2L),
                "bank candidate was assigned while standing on the deposit side");

        villager.moveTo(workPos.getX() + 0.5D, workPos.getY(), workPos.getZ() + 0.5D,
                0.0F, 0.0F);
        helper.assertTrue(assignProfession.tryStart(level, villager, gameTime + 3L),
                "bank candidate could not be assigned from the banker work side");
        helper.assertValueEqual(villager.getVillagerData().getProfession(), ECAPVillagerProfessions.BANKER.get(),
                "bank candidate did not receive the banker profession");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void bankFrontAndBackAccessSidesAreExclusive(GameTestHelper helper) {
        BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
        var state = ECAPBlocks.BANK.get().defaultBlockState()
                .setValue(BankBlock.FACING, Direction.NORTH);
        Vec3 front = Vec3.atBottomCenterOf(BankBlock.getDepositApproachPos(state, bankPos));
        Vec3 back = Vec3.atBottomCenterOf(BankBlock.getBankerWorkPos(state, bankPos));
        Vec3 side = Vec3.atBottomCenterOf(bankPos.relative(Direction.EAST));

        helper.assertTrue(BankBlock.isAtDepositApproach(state, bankPos, front),
                "front position was not accepted for villager bank access");
        helper.assertFalse(BankBlock.isAtDepositApproach(state, bankPos, back),
                "back position was accepted for ordinary villager bank access");
        helper.assertFalse(BankBlock.isAtDepositApproach(state, bankPos, side),
                "side position was accepted for ordinary villager bank access");
        helper.assertTrue(BankBlock.isAtBankerWorkPos(state, bankPos, back),
                "back position was not accepted for banker work");
        helper.assertFalse(BankBlock.isAtBankerWorkPos(state, bankPos, front),
                "front position was accepted for banker work");
        helper.assertFalse(BankBlock.isAtBankerWorkPos(state, bankPos, side),
                "side position was accepted for banker work");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void starvingVillagerBegsAndReceivesFood(GameTestHelper helper) {
        Villager beggar = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager donor = helper.spawn(EntityType.VILLAGER, 2, 1, 1);
        VillagerStatsAttachment beggarStats =
                beggar.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        VillagerStatsAttachment donorStats =
                donor.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        long gameTime = Math.max(helper.getLevel().getGameTime(), 600L);
        ((ServerLevelData) helper.getLevel().getLevelData()).setGameTime(gameTime);

        beggarStats.setHungerLevel(0);
        beggarStats.setLastBegTime(gameTime - 600L);
        donorStats.setHungerLevel(20);
        donor.getInventory().addItem(new ItemStack(Items.BREAD, 10));
        beggar.getBrain().setMemory(
                MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,
                new NearestVisibleLivingEntities(beggar, List.<LivingEntity>of(donor)));

        BehaviorControl<Villager> behavior = findBehavior(
                VillagerGoalPackages.getIdlePackage(VillagerProfession.NONE, 0.5F),
                BegForFoodBehavior.class);
        helper.assertTrue(behavior.tryStart(helper.getLevel(), beggar, gameTime),
                "starving villager did not start the begging behavior");
        helper.assertValueEqual(beggarStats.getBegDonorUUID(), donor.getUUID(),
                "begging did not select the eligible donor");

        for (int tick = 1; tick <= 30; tick++) {
            behavior.tickOrStop(helper.getLevel(), beggar, gameTime + tick);
        }

        helper.assertValueEqual(countItem(donor, Items.BREAD), 5,
                "donor did not give exactly half of its ten bread, capped at five");
        helper.assertValueEqual(beggarStats.getLastBegTime(), gameTime + 30L,
                "successful begging did not record its completion time");
        helper.assertTrue(beggarStats.getBegDonorUUID() == null,
                "successful begging left stale donor state");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void villagersPreferNonBreadAndAppleFood(GameTestHelper helper) {
        net.minecraft.world.SimpleContainer inventory = new net.minecraft.world.SimpleContainer(3);
        inventory.setItem(0, new ItemStack(Items.BREAD));
        inventory.setItem(1, new ItemStack(Items.APPLE));
        inventory.setItem(2, new ItemStack(Items.CARROT));

        helper.assertValueEqual(VillagerFoodSelection.findBestFoodSlot(inventory), 2,
                "villager food selection did not defer bread and apples");
        inventory.setItem(2, ItemStack.EMPTY);
        helper.assertValueEqual(VillagerFoodSelection.findBestFoodSlot(inventory), 0,
                "villager food selection did not choose fallback food when necessary");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void fenceGateBehaviorOpensAndClosesThePathGate(GameTestHelper helper) {
        BlockPos villagerPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos gatePos = helper.absolutePos(new BlockPos(2, 1, 1));
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.OAK_FENCE_GATE.defaultBlockState());
        Villager villager = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Path path = new Path(
                List.of(
                        new Node(villagerPos.getX(), villagerPos.getY(), villagerPos.getZ()),
                        new Node(gatePos.getX(), gatePos.getY(), gatePos.getZ())),
                gatePos,
                true);
        helper.assertTrue(villager.getNavigation().moveTo(path, 0.5D),
                "test navigation rejected the focused fence-gate path");
        villager.getBrain().setMemory(MemoryModuleType.PATH, path);

        boolean previous = Config.enableFenceGateInteraction;
        Config.enableFenceGateInteraction = true;
        try {
            BehaviorControl<Villager> behavior = findBehavior(
                    VillagerGoalPackages.getCorePackage(VillagerProfession.NONE, 0.5F),
                    InteractWithFenceGateBehavior.class);
            long gameTime = helper.getLevel().getGameTime();
            helper.assertTrue(behavior.tryStart(helper.getLevel(), villager, gameTime),
                    "fence-gate behavior did not start for a present path");
            helper.assertTrue(helper.getLevel().getBlockState(gatePos).getValue(BlockStateProperties.OPEN),
                    "fence-gate behavior did not open the closed gate on the path");

            villager.moveTo(gatePos.getX() + 6.0D, gatePos.getY(), gatePos.getZ(), 0.0F, 0.0F);
            behavior.tickOrStop(helper.getLevel(), villager, gameTime + 1L);
            helper.assertFalse(helper.getLevel().getBlockState(gatePos).getValue(BlockStateProperties.OPEN),
                    "fence-gate behavior did not close its gate after the villager passed");
        } finally {
            Config.enableFenceGateInteraction = previous;
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void boatAvoidanceChoosesAWalkTargetAwayFromTheBoat(GameTestHelper helper) {
        for (int x = -4; x <= 3; x++) {
            helper.setBlock(new BlockPos(x, 0, 1), Blocks.STONE.defaultBlockState());
        }
        Villager villager = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Boat boat = helper.spawn(EntityType.BOAT, 2, 1, 1);
        boolean previous = Config.enableBoatAvoidance;
        Config.enableBoatAvoidance = true;
        try {
            BehaviorControl<Villager> behavior = findBehavior(
                    VillagerGoalPackages.getCorePackage(VillagerProfession.NONE, 0.5F),
                    AvoidBoatBehavior.class);
            long gameTime = helper.getLevel().getGameTime();
            helper.assertTrue(behavior.tryStart(helper.getLevel(), villager, gameTime),
                    "boat avoidance did not start for a nearby living boat");
            WalkTarget walkTarget = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
            helper.assertTrue(walkTarget != null, "boat avoidance did not install a walk target");
            helper.assertTrue(walkTarget.getTarget().currentPosition().x < villager.position().x,
                    "boat east of the villager did not produce a westward avoidance target");
            helper.assertValueEqual(
                    villager.getBrain().getMemory(MemoryModuleType.LOOK_TARGET)
                            .map(target -> target.currentBlockPosition()).orElse(null),
                    boat.blockPosition(),
                    "boat avoidance did not keep the nearby boat as its look target");
            behavior.doStop(helper.getLevel(), villager, gameTime);
        } finally {
            Config.enableBoatAvoidance = previous;
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void villagersAvoidNearbyPhaseTwoVillagers(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        Villager phaseTwoVillager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        BlockPos floor = villager.blockPosition().below();
        for (int x = -10; x <= 4; x++) {
            helper.getLevel().setBlockAndUpdate(
                    floor.offset(x, 0, 0), Blocks.STONE.defaultBlockState());
        }
        phaseTwoVillager.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                com.orangevillager61.emeraldcapitalism.registry.ECAPEffects.ZOMBIE_VIRUS,
                net.minecraft.world.effect.MobEffectInstance.INFINITE_DURATION,
                0, false, false, true));

        BehaviorControl<Villager> behavior = findBehavior(
                VillagerGoalPackages.getCorePackage(VillagerProfession.NONE, 0.5F),
                AvoidZombiePlagueBehavior.class);
        long gameTime = helper.getLevel().getGameTime();
        helper.assertTrue(behavior.tryStart(helper.getLevel(), villager, gameTime),
                "villager did not start fleeing a nearby phase-two villager");
        WalkTarget walkTarget = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        helper.assertTrue(walkTarget != null,
                "phase-two avoidance did not install a walk target");
        helper.assertTrue(walkTarget.getTarget().currentPosition().x < villager.position().x,
                "phase-two villager east of the villager did not produce a westward escape target");
        behavior.doStop(helper.getLevel(), villager, gameTime);
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void navigationWatchdogStopsAfterSustainedNoProgress(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 1, 1));
        VillagerNavigationWatchdog watchdog = new VillagerNavigationWatchdog();
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 1));

        for (int tick = 0; tick < 100; tick++) {
            helper.assertFalse(watchdog.isStuck(villager, target),
                    "navigation watchdog fired before the full no-progress window");
        }
        helper.assertTrue(watchdog.isStuck(villager, target),
                "navigation watchdog did not fire after sustained no progress");

        villager.moveTo(villager.getX() + 1.0D, villager.getY(), villager.getZ(), 0.0F, 0.0F);
        helper.assertFalse(watchdog.isStuck(villager, target),
                "navigation watchdog did not reset after movement");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void gateRemainsOpenWhileAnotherMobApproaches(GameTestHelper helper) {
        BlockPos ownerPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos gatePos = helper.absolutePos(new BlockPos(2, 1, 1));
        BlockPos otherPos = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.OAK_FENCE_GATE.defaultBlockState());

        Villager owner = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager other = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        Path ownerPath = new Path(
                List.of(
                        new Node(ownerPos.getX(), ownerPos.getY(), ownerPos.getZ()),
                        new Node(gatePos.getX(), gatePos.getY(), gatePos.getZ())),
                gatePos,
                true);
        Path otherPath = new Path(
                List.of(
                        new Node(otherPos.getX(), otherPos.getY(), otherPos.getZ()),
                        new Node(gatePos.getX(), gatePos.getY(), gatePos.getZ())),
                gatePos,
                true);
        helper.assertTrue(owner.getNavigation().moveTo(ownerPath, 0.5D),
                "owner navigation rejected the focused fence-gate path");
        helper.assertTrue(other.getNavigation().moveTo(otherPath, 0.5D),
                "second villager navigation rejected the focused fence-gate path");
        owner.getBrain().setMemory(MemoryModuleType.PATH, ownerPath);

        boolean previous = Config.enableFenceGateInteraction;
        Config.enableFenceGateInteraction = true;
        try {
            BehaviorControl<Villager> behavior = findBehavior(
                    VillagerGoalPackages.getCorePackage(VillagerProfession.NONE, 0.5F),
                    InteractWithFenceGateBehavior.class);
            long gameTime = helper.getLevel().getGameTime();
            helper.assertTrue(behavior.tryStart(helper.getLevel(), owner, gameTime),
                    "fence-gate behavior did not start for the owner");
            helper.assertTrue(helper.getLevel().getBlockState(gatePos).getValue(BlockStateProperties.OPEN),
                    "fence-gate behavior did not open the gate");

            owner.moveTo(owner.getX() + 6.0D, owner.getY(), owner.getZ(), 0.0F, 0.0F);
            behavior.tickOrStop(helper.getLevel(), owner, gameTime + 1L);
            helper.assertTrue(helper.getLevel().getBlockState(gatePos).getValue(BlockStateProperties.OPEN),
                    "fence-gate behavior closed a gate while another villager approached");
        } finally {
            Config.enableFenceGateInteraction = previous;
        }
        helper.succeed();
    }

    private static void assertBehavior(
            GameTestHelper helper,
            List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> packageEntries,
            Class<?> behaviorType,
            int expectedPriority,
            int expectedCount) {
        long matching = packageEntries.stream()
                .filter(entry -> entry.getSecond().getClass() == behaviorType)
                .count();
        helper.assertValueEqual((int) matching, expectedCount,
                behaviorType.getSimpleName() + " count was not exact");
        packageEntries.stream()
                .filter(entry -> entry.getSecond().getClass() == behaviorType)
                .forEach(entry -> helper.assertValueEqual(entry.getFirst(), expectedPriority,
                        behaviorType.getSimpleName() + " priority changed"));
    }

    private static void assertRepresentativeWorkPackage(
            GameTestHelper helper, VillagerProfession profession) {
        List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> work =
                VillagerGoalPackages.getWorkPackage(profession, 0.5F);
        helper.assertValueEqual(work.size(), 7,
                "representative profession lost a top-level vanilla work behavior: " + profession);
        for (String expected : List.of(
                "RunOne", "ShowTradesToPlayer", "GiveGiftToHero")) {
            helper.assertTrue(work.stream()
                            .anyMatch(entry -> entry.getSecond().getClass().getSimpleName().equals(expected)),
                    "representative profession lost vanilla behavior " + expected + ": " + profession);
        }
    }

    @SuppressWarnings("unchecked")
    private static BehaviorControl<Villager> findBehavior(
            List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> packageEntries,
            Class<?> behaviorType) {
        return packageEntries.stream()
                .map(Pair::getSecond)
                .filter(behavior -> behavior.getClass() == behaviorType)
                .map(behavior -> (BehaviorControl<Villager>) behavior)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing behavior " + behaviorType.getSimpleName()));
    }

    private static int countItem(Villager villager, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
