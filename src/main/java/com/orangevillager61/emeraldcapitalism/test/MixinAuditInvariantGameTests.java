package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.entity.ai.WanderingTraderAvoidBoatGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.WanderingTraderFenceGateGoal;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.VillagerMakeLove;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Focused invariants used by the mixin subtraction audit. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class MixinAuditInvariantGameTests {

    private MixinAuditInvariantGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void villagerInventoryHasEighteenSlots(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        helper.assertValueEqual(villager.getInventory().getContainerSize(), 18,
                "villager inventory must retain the expanded 18-slot contract");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void wanderingTraderKeepsVanillaInventorySize(GameTestHelper helper) {
        WanderingTrader trader = helper.spawnWithNoFreeWill(EntityType.WANDERING_TRADER, 1, 1, 1);
        helper.assertTrue(trader != null, "could not create wandering trader inventory fixture");
        helper.assertValueEqual(trader.getInventory().getContainerSize(), 8,
                "villager inventory expansion must not affect wandering traders");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void villagerBreedingUsesCustomHungerThreshold(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        stats.setHungerLevel(11);
        helper.assertFalse(villager.canBreed(), "hunger below 12 must prevent breeding");
        stats.setHungerLevel(12);
        helper.assertTrue(villager.canBreed(), "hunger 12 must allow an awake adult to breed");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void villagerPickupReservesFinalSlotsForFood(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        for (int slot = 0; slot < 15; slot++) {
            villager.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        helper.assertFalse(com.orangevillager61.emeraldcapitalism.util.VillagerPickupCompat.wants(
                        villager, helper.getLevel(), new ItemStack(Items.EMERALD)),
                "non-food pickup must not consume one of the three reserved food slots");
        helper.assertTrue(com.orangevillager61.emeraldcapitalism.util.VillagerPickupCompat.wants(
                        villager, helper.getLevel(), new ItemStack(Items.BREAD)),
                "food pickup must be allowed to use the reserved slots");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void villagerPickupCreditsInsertedEmeralds(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        ItemEntity emeralds = new ItemEntity(helper.getLevel(), villager.getX(), villager.getY(),
                villager.getZ(), new ItemStack(Items.EMERALD, 3));
        try {
//? if >=1.21.4 {
            Method pickup = Villager.class.getDeclaredMethod("pickUpItem", ServerLevel.class,
                    ItemEntity.class);
            pickup.setAccessible(true);
            pickup.invoke(villager, helper.getLevel(), emeralds);
//?} else {
/*            Method pickup = Villager.class.getDeclaredMethod("pickUpItem", ItemEntity.class);
            pickup.setAccessible(true);
            pickup.invoke(villager, emeralds);
 *///?}
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new AssertionError("Could not invoke the villager pickup boundary", exception);
        } catch (InvocationTargetException exception) {
            throw new AssertionError("Villager pickup boundary threw", exception.getCause());
        }
        VillagerStatsAttachment stats = villager.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        helper.assertValueEqual(stats.getEmeraldBalance(), 3,
                "pickup accounting must credit exactly the inserted emerald value");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void villagerBirthUsesCustomCooldownAndBookkeeping(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager first = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        Villager second = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        VillagerStatsAttachment firstStats = first.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        VillagerStatsAttachment secondStats = second.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        firstStats.setHungerLevel(20);
        secondStats.setHungerLevel(20);

        Optional<Villager> child = invokeBreed(level, first, second);
        helper.assertTrue(child.isPresent(), "direct vanilla birth fixture did not create a child");
        helper.assertValueEqual(first.getAge(), 12000, "first parent did not receive the custom cooldown");
        helper.assertValueEqual(second.getAge(), 12000, "second parent did not receive the custom cooldown");
        helper.assertValueEqual(firstStats.getHungerLevel(), 10, "first parent was not charged custom hunger");
        helper.assertValueEqual(secondStats.getHungerLevel(), 10, "second parent was not charged custom hunger");
        VillagerStatsAttachment childStats = child.orElseThrow()
                .getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
        helper.assertTrue(childStats.hasParents(), "newborn did not receive family bookkeeping");
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    private static Optional<Villager> invokeBreed(ServerLevel level, Villager first, Villager second) {
        try {
            Method breed = VillagerMakeLove.class.getDeclaredMethod(
                    "breed", ServerLevel.class, Villager.class, Villager.class);
            breed.setAccessible(true);
            return (Optional<Villager>) breed.invoke(new VillagerMakeLove(), level, first, second);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new AssertionError("Could not invoke the VillagerMakeLove birth boundary", exception);
        } catch (InvocationTargetException exception) {
            throw new AssertionError("VillagerMakeLove birth boundary threw", exception.getCause());
        }
    }

    @GameTest(template = "empty_3x3x3")
    public static void villagerPathIncludesVerticalLadderNodes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ladderBase = installLadder(helper);
        Villager villager = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        villager.moveTo(ladderBase.getX() + 0.5D, ladderBase.getY(), ladderBase.getZ() + 0.5D,
                0.0F, 0.0F);
        Zombie zombie = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(EntityType.ZOMBIE, level);
        helper.assertTrue(zombie != null, "could not create unrelated ladder-pathing fixture");

        boolean previousLadderTraversal = Config.enableLadderTraversal;
        try {
            Config.enableLadderTraversal = true;
            helper.assertTrue(hasUpwardLadderNode(level, villager, ladderBase),
                    "walk-node evaluation did not expose the next vertical ladder node");
            helper.assertFalse(hasUpwardLadderNode(level, zombie, ladderBase),
                    "unrelated mobs must not receive custom ladder nodes");

            Config.enableLadderTraversal = false;
            helper.assertFalse(hasUpwardLadderNode(level, villager, ladderBase),
                    "disabled ladder traversal must not expose custom vertical nodes");
        } finally {
            Config.enableLadderTraversal = previousLadderTraversal;
        }
        helper.succeed();
    }

    private static boolean hasUpwardLadderNode(ServerLevel level, Mob mob, BlockPos ladderBase) {
        WalkNodeEvaluator evaluator = new WalkNodeEvaluator();
        PathNavigationRegion region = new PathNavigationRegion(
                level, ladderBase.offset(-8, -8, -8), ladderBase.offset(8, 8, 8));
        Node[] neighbors = new Node[32];
        int count;
        evaluator.prepare(region, mob);
        try {
            count = evaluator.getNeighbors(neighbors,
                    new Node(ladderBase.getX(), ladderBase.getY(), ladderBase.getZ()));
        } finally {
            evaluator.done();
        }
        return java.util.Arrays.stream(neighbors, 0, count)
                .anyMatch(node -> node.x == ladderBase.getX() && node.z == ladderBase.getZ()
                        && node.y == ladderBase.getY() + 1);
    }

    @GameTest(template = "empty_3x3x3")
    public static void villagerPathTreatsFenceGateAsDoor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos gatePos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlock(gatePos, Blocks.OAK_FENCE_GATE.defaultBlockState(), 3);
        Villager villager = helper.spawn(EntityType.VILLAGER, 2, 1, 1);
        IronGolem ironGolem = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(
                EntityType.IRON_GOLEM, level);
        EmeraldGolem emeraldGolem = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(
                ECAPEntityTypes.EMERALD_GOLEM.get(), level);
        helper.assertTrue(ironGolem != null && emeraldGolem != null,
                "could not create openable-pathing fixtures");

        boolean previousFenceGateInteraction = Config.enableFenceGateInteraction;
        try {
            Config.enableFenceGateInteraction = true;
            helper.assertValueEqual(pathTypeFor(level, villager, gatePos),
                    PathType.DOOR_WOOD_CLOSED,
                    "closed fence gate must use the closed wooden-door path type");
            PathType ironGolemGateType = pathTypeFor(level, ironGolem, gatePos);
            helper.assertTrue(ironGolemGateType != PathType.DOOR_WOOD_CLOSED
                            && ironGolemGateType != PathType.DOOR_OPEN,
                    "ordinary iron golems must not inherit emerald-golem gate handling");

            Config.enableFenceGateInteraction = false;
            PathType disabledVillagerGateType = pathTypeFor(level, villager, gatePos);
            helper.assertTrue(disabledVillagerGateType != PathType.DOOR_WOOD_CLOSED
                            && disabledVillagerGateType != PathType.DOOR_OPEN,
                    "disabled fence-gate interaction must not leave villager gate pathing enabled");

            Config.enableFenceGateInteraction = true;
            level.setBlock(gatePos, level.getBlockState(gatePos)
                    .setValue(FenceGateBlock.OPEN, true), 3);
            helper.assertValueEqual(pathTypeFor(level, villager, gatePos),
                    PathType.DOOR_OPEN, "open fence gate must use the open-door path type");

            level.setBlock(gatePos, Blocks.OAK_TRAPDOOR.defaultBlockState(), 3);
            helper.assertValueEqual(pathTypeFor(level, emeraldGolem, gatePos),
                    PathType.DOOR_WOOD_CLOSED,
                    "emerald golems must retain wooden-trapdoor pathing");
            PathType ironGolemTrapdoorType = pathTypeFor(level, ironGolem, gatePos);
            helper.assertTrue(ironGolemTrapdoorType != PathType.DOOR_WOOD_CLOSED
                            && ironGolemTrapdoorType != PathType.DOOR_OPEN,
                    "ordinary iron golems must not inherit emerald-golem trapdoor handling");
        } finally {
            Config.enableFenceGateInteraction = previousFenceGateInteraction;
        }
        helper.succeed();
    }

    private static PathType pathTypeFor(ServerLevel level, Mob mob, BlockPos pos) {
        WalkNodeEvaluator evaluator = new WalkNodeEvaluator();
        PathNavigationRegion region = new PathNavigationRegion(
                level, pos.offset(-2, -2, -2), pos.offset(2, 2, 2));
        evaluator.prepare(region, mob);
        try {
            return evaluator.getPathType(new PathfindingContext(level, mob),
                    pos.getX(), pos.getY(), pos.getZ());
        } finally {
            evaluator.done();
        }
    }

    @GameTest(template = "empty_3x8x3", timeoutTicks = 400)
    public static void villagerClimbsInstalledLadderPath(GameTestHelper helper) {
        BlockPos ladderBase = installLadder(helper);
        Villager villager = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        villager.moveTo(ladderBase.getX() + 0.5D, ladderBase.getY(), ladderBase.getZ() + 0.5D,
                0.0F, 0.0F);

        List<Node> nodes = new ArrayList<>();
        for (int y = 1; y < 5; y++) {
            nodes.add(new Node(ladderBase.getX(), ladderBase.getY() + y, ladderBase.getZ()));
        }
        helper.assertTrue(villager.getNavigation().moveTo(new Path(nodes, ladderBase.above(4), true), 1.0D),
                "villager ladder path could not be installed");
        awaitVillagerLadderExit(helper, villager, ladderBase, 300);
    }

    @GameTest(template = "empty_3x8x3", timeoutTicks = 800)
    public static void villagerKeepsLadderPathWhileWaitingForAnotherClimber(GameTestHelper helper) {
        BlockPos ladderBase = installLadder(helper);
        Villager climber = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 2, 1);
        climber.moveTo(ladderBase.getX() + 0.5D, ladderBase.getY() + 1.0D,
                ladderBase.getZ() + 0.5D, 0.0F, 0.0F);
        Villager waiter = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        waiter.moveTo(ladderBase.getX() + 0.5D, ladderBase.getY(),
                ladderBase.getZ() + 0.5D, 0.0F, 0.0F);

        helper.assertTrue(climber.getNavigation().moveTo(ladderPath(ladderBase, 1), 1.0D),
                "climber ladder path could not be installed");
        helper.assertTrue(waiter.getNavigation().moveTo(ladderPath(ladderBase, 0), 1.0D),
                "waiting villager ladder path could not be installed");
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(waiter.getNavigation().getPath() != null,
                    "waiting villager lost its ladder path while the column was occupied");
            awaitVillagersClearLadder(helper, climber, waiter, ladderBase, 600, 0);
        });
    }

    private static void awaitVillagersClearLadder(GameTestHelper helper, Villager first,
                                                  Villager second, BlockPos ladderBase,
                                                  int remainingTicks, int stableTicks) {
        boolean firstClear = hasClearedLadderTop(first, ladderBase);
        boolean secondClear = hasClearedLadderTop(second, ladderBase);
        if (remainingTicks <= 0) {
            helper.fail("multiple villagers did not clear the ladder: first=" + first.position()
                    + ", firstClimbable=" + first.onClimbable()
                    + ", second=" + second.position()
                    + ", secondClimbable=" + second.onClimbable()
                    + ", firstPath=" + first.getNavigation().getPath()
                    + ", secondPath=" + second.getNavigation().getPath());
            return;
        }
        if (firstClear && secondClear) {
            // Do not accept a transient step off the top: keep observing long
            // enough to catch the old up/down oscillation or immediate
            // re-selection of the completed ladder path.
            if (stableTicks >= 40) {
                helper.succeed();
                return;
            }
            helper.runAfterDelay(1, () -> awaitVillagersClearLadder(
                    helper, first, second, ladderBase, remainingTicks - 1, stableTicks + 1));
            return;
        }
        helper.runAfterDelay(1, () -> awaitVillagersClearLadder(
                helper, first, second, ladderBase, remainingTicks - 1, 0));
    }

    private static Path ladderPath(BlockPos ladderBase, int firstOffset) {
        List<Node> nodes = new ArrayList<>();
        for (int offset = firstOffset; offset < 5; offset++) {
            nodes.add(new Node(ladderBase.getX(), ladderBase.getY() + offset, ladderBase.getZ()));
        }
        return new Path(nodes, ladderBase.above(4), true);
    }

    private static BlockPos installLadder(GameTestHelper helper) {
        BlockPos ladderBase = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlock(ladderBase.below(), Blocks.STONE.defaultBlockState(), 3);
        for (int y = 0; y < 5; y++) {
            helper.getLevel().setBlock(ladderBase.above(y), Blocks.LADDER.defaultBlockState()
                    .setValue(LadderBlock.FACING, Direction.SOUTH), 3);
        }
        BlockPos exitPos = ladderBase.above(4).relative(Direction.SOUTH);
        // Give both villagers room to collide on the landing without being
        // pushed sideways into unsupported air. The assertion still requires
        // each entity to reach the row one block beyond the ladder top.
        for (int dx = -1; dx <= 1; dx++) {
            helper.getLevel().setBlock(exitPos.below().offset(dx, 0, 0),
                    Blocks.STONE.defaultBlockState(), 3);
        }
        // The 3x3x3 template has protective barriers above its nominal volume.
        // Clear both the exit cell and headroom so the dismount guard sees a
        // real walkable block in every supported Minecraft version.
        helper.getLevel().setBlock(exitPos, Blocks.AIR.defaultBlockState(), 3);
        helper.getLevel().setBlock(exitPos.above(), Blocks.AIR.defaultBlockState(), 3);
        helper.getLevel().setBlock(ladderBase.above(5), Blocks.AIR.defaultBlockState(), 3);
        helper.getLevel().setBlock(ladderBase.above(6), Blocks.AIR.defaultBlockState(), 3);
        return ladderBase;
    }

    @GameTest(template = "empty_3x3x3")
    public static void wanderingTraderReceivesCustomMovementGoals(GameTestHelper helper) {
        WanderingTrader trader = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(
                EntityType.WANDERING_TRADER, helper.getLevel());
        helper.assertTrue(trader != null, "could not create wandering trader");
        helper.getLevel().addFreshEntity(trader);
        helper.assertTrue(trader.goalSelector.getAvailableGoals().stream()
                        .anyMatch(goal -> goal.getGoal() instanceof WanderingTraderAvoidBoatGoal),
                "wandering trader is missing its boat-avoidance goal");
        helper.assertTrue(trader.goalSelector.getAvailableGoals().stream()
                        .anyMatch(goal -> goal.getGoal() instanceof WanderingTraderFenceGateGoal),
                "wandering trader is missing its fence-gate goal");
        helper.succeed();
    }

    @GameTest(template = "empty_3x8x3", timeoutTicks = 800)
    public static void wanderingTraderClimbsInstalledLadderPath(GameTestHelper helper) {
        BlockPos ladderBase = installLadder(helper);
        WanderingTrader trader = helper.spawnWithNoFreeWill(EntityType.WANDERING_TRADER, 1, 1, 1);
        helper.assertTrue(trader != null, "could not create wandering trader ladder fixture");
        trader.moveTo(ladderBase.getX() + 0.5D, ladderBase.getY(), ladderBase.getZ() + 0.5D,
                0.0F, 0.0F);
        helper.getLevel().addFreshEntity(trader);
        List<Node> nodes = new ArrayList<>();
        for (int y = 1; y < 5; y++) {
            nodes.add(new Node(ladderBase.getX(), ladderBase.getY() + y, ladderBase.getZ()));
        }
        helper.assertTrue(trader.getNavigation().moveTo(new Path(nodes, ladderBase.above(4), true), 1.0D),
                "wandering trader ladder path could not be installed");
        awaitTraderLadderExit(helper, trader, ladderBase, 300);
    }

    private static void awaitVillagerLadderExit(GameTestHelper helper, Villager villager,
                                                BlockPos ladderBase, int remainingTicks) {
        if (hasClearedLadderTop(villager, ladderBase)) {
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("villager did not complete its ladder exit: pos=" + villager.position()
                    + ", base=" + ladderBase + ", enabled=" + Config.enableLadderTraversal);
            return;
        }
        helper.runAfterDelay(1, () -> awaitVillagerLadderExit(
                helper, villager, ladderBase, remainingTicks - 1));
    }

    private static void awaitTraderLadderExit(GameTestHelper helper,
                                               WanderingTrader trader,
                                               BlockPos ladderBase, int remainingTicks) {
        if (hasClearedLadderTop(trader, ladderBase)) {
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("wandering trader did not complete its ladder exit: pos=" + trader.position()
                    + ", base=" + ladderBase + ", alive=" + trader.isAlive());
            return;
        }
        helper.runAfterDelay(1, () -> awaitTraderLadderExit(
                helper, trader, ladderBase, remainingTicks - 1));
    }

    private static boolean hasClearedLadderTop(LivingEntity entity, BlockPos ladderBase) {
        // The ladder's top rung is four blocks above its base. The entity must
        // be at that top-rung height and have physically entered at least one
        // block beyond the south-facing ladder column before the test accepts
        // the exit. Checking blockPosition() requires the entity's center to
        // be in the adjacent exit block, including at negative coordinates.
        return entity.getY() > ladderBase.getY() + 3.0D
                && entity.blockPosition().getZ() >= ladderBase.getZ() + 1
                && !entity.onClimbable();
    }

    @GameTest(template = "empty_3x3x3")
    public static void easyDifficultyConvertsKilledVillager(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Difficulty previousDifficulty = level.getDifficulty();
        boolean previousAlwaysConvert = Config.alwaysConvertVillagersToZombieVillagers;
        try {
            level.getServer().setDifficulty(Difficulty.EASY, true);
            Config.alwaysConvertVillagersToZombieVillagers = true;
            Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 1, 1);
            Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
            zombie.killedEntity(level, villager);
            List<ZombieVillager> converted = level.getEntitiesOfClass(
                    ZombieVillager.class, zombie.getBoundingBox().inflate(6.0D));
            helper.assertValueEqual(converted.size(), 1,
                    "Easy difficulty must use the configured guaranteed conversion path");
            helper.succeed();
        } finally {
            Config.alwaysConvertVillagersToZombieVillagers = previousAlwaysConvert;
            level.getServer().setDifficulty(previousDifficulty, true);
        }
    }

    @GameTest(template = "empty_3x3x3")
    public static void villagerConversionHonorsConfigAndPeacefulBoundary(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Difficulty previousDifficulty = level.getDifficulty();
        boolean previousAlwaysConvert = Config.alwaysConvertVillagersToZombieVillagers;
        try {
            level.getServer().setDifficulty(Difficulty.EASY, true);
            Config.alwaysConvertVillagersToZombieVillagers = false;
            Zombie easyZombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 1, 1);
            Villager easyVillager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
            easyZombie.killedEntity(level, easyVillager);
            helper.assertTrue(level.getEntitiesOfClass(
                            ZombieVillager.class, easyZombie.getBoundingBox().inflate(6.0D)).isEmpty(),
                    "disabled guaranteed conversion must preserve Easy's zero-percent conversion");

            level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
            Config.alwaysConvertVillagersToZombieVillagers = true;
            Zombie peacefulZombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 1, 2);
            Villager peacefulVillager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 2);
            peacefulZombie.killedEntity(level, peacefulVillager);
            helper.assertTrue(level.getEntitiesOfClass(
                            ZombieVillager.class, peacefulZombie.getBoundingBox().inflate(6.0D)).isEmpty(),
                    "guaranteed conversion must not override Peaceful difficulty");
            helper.succeed();
        } finally {
            Config.alwaysConvertVillagersToZombieVillagers = previousAlwaysConvert;
            level.getServer().setDifficulty(previousDifficulty, true);
        }
    }

}
