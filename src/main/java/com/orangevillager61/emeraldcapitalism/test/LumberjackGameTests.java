package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.entity.ai.LumberjackGoal;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Focused server-side tests for lumberjack tree scanning and harvesting. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class LumberjackGameTests {

    private LumberjackGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackBreaksByHandAndReplantsTree(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        BlockPos base = installSmallTree(helper);
        BlockPos furnacePos = installNearbyFurnace(helper);
        BlockPos blockingLeaf = base.west().above();
        BlockPos secondBlockingLeaf = base.east().above();
        helper.setBlock(blockingLeaf, Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(secondBlockingLeaf, Blocks.OAK_LEAVES.defaultBlockState());

        LumberjackGoal goal = new LumberjackGoal(lumberjack);
        helper.assertTrue(goal.canUse(), "lumberjack did not detect a supported log cluster with leaves");
        goal.start();
        helper.assertTrue(helper.getLevel().getBlockState(blockingLeaf).isAir(),
                "lumberjack left a canopy leaf blocking its head-level approach");
        helper.assertTrue(helper.getLevel().getBlockState(secondBlockingLeaf).isAir(),
                "lumberjack stopped after clearing only one canopy leaf");
        goal.tick();
        helper.assertTrue(lumberjack.getNavigation().isDone(),
                "lumberjack kept navigating after reaching the tree log it was cutting");

        for (int tick = 0; tick < 58; tick++) {
            goal.tick();
        }
        helper.assertTrue(helper.getLevel().getBlockState(base).is(Blocks.OAK_LOG),
                "log was broken before the vanilla bare-hand duration elapsed");

        for (int tick = 0; tick < 61; tick++) {
            goal.tick();
        }
        helper.assertTrue(helper.getLevel().getBlockState(base).isAir(),
                "base log was not broken after the vanilla bare-hand duration");

        for (int tick = 0; tick < 61; tick++) {
            goal.tick();
        }
        goal.tick();

        for (int tick = 0; tick < 220; tick++) {
            goal.tick();
            tickFurnace(helper, furnacePos);
        }

        helper.assertValueEqual(countItem(lumberjack, Items.OAK_LOG), 3,
                "a sub-eight-log charcoal allocation consumed harvested logs");
        helper.assertValueEqual(countItem(lumberjack, Items.CHARCOAL), 0,
                "a sub-eight-log charcoal allocation started production");
        helper.assertValueEqual(countItem(lumberjack, Items.OAK_PLANKS), 0,
                "bootstrap planks were incorrectly returned to the lumberjack inventory");
        helper.assertTrue(((FurnaceBlockEntity) helper.getLevel().getBlockEntity(furnacePos))
                        .getItem(1).isEmpty(),
                "a sub-eight-log allocation inserted furnace fuel");
        helper.assertTrue(helper.getLevel().getBlockState(base).is(Blocks.OAK_SAPLING),
                "lumberjack did not replant a compatible sapling at the tree base");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackAccumulatesCharcoalQuotaAcrossSmallTreeBatches(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        installNearbyFurnace(helper);

        LumberjackGoal goal = new LumberjackGoal(lumberjack);
        installSmallTree(helper);
        harvestTree(helper, goal);
        helper.assertValueEqual(countItem(lumberjack, Items.OAK_LOG), 3,
                "the first small tree lost logs before a charcoal batch was ready");
        helper.assertValueEqual(countItem(lumberjack, Items.CHARCOAL), 0,
                "the first small tree started a sub-eight-log charcoal batch");

        installSmallTree(helper);
        LumberjackGoal nextGoal = new LumberjackGoal(lumberjack);
        helper.assertTrue(nextGoal.canUse(), "lumberjack did not select the next replanted tree");
        nextGoal.start();
        harvestTreeAfterStart(helper, nextGoal);

        helper.assertValueEqual(countItem(lumberjack, Items.OAK_LOG), 6,
                "small-tree batches consumed logs before eight were assigned to charcoal");
        helper.assertValueEqual(countItem(lumberjack, Items.CHARCOAL), 0,
                "repeated small trees started charcoal production below the batch threshold");
        helper.assertValueEqual(lumberjack.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).getCharcoalQuota(), 3.0D,
                "small-tree charcoal assignments did not accumulate without recounting retained logs");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void underFueledCharcoalQuotaReturnsToTreeCollection(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_LOG));
        lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                .setCharcoalQuota(1.0D);
        installNearbyFurnace(helper);
        BlockPos treeBase = installSmallTree(helper);

        LumberjackGoal goal = new LumberjackGoal(lumberjack);
        helper.assertTrue(goal.canUse(),
                "under-fueled lumberjack did not continue with available tree work");
        goal.start();
        for (int tick = 0; tick < 180; tick++) {
            goal.tick();
        }

        helper.assertTrue(helper.getLevel().getBlockState(treeBase).isAir()
                        || helper.getLevel().getBlockState(treeBase.above()).isAir(),
                "under-fueled charcoal work held the lumberjack motionless at the furnace");
        goal.stop();
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackUsesCharcoalAsFurnaceFuel(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_LOG, 8));
        lumberjack.getInventory().addItem(new ItemStack(Items.CHARCOAL));
        BlockPos furnacePos = installNearbyFurnace(helper);
        lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                .setCharcoalQuota(8.0D);

        LumberjackGoal goal = new LumberjackGoal(lumberjack);
        helper.assertTrue(goal.canUse(), "eight assigned logs did not start charcoal production");
        goal.start();
        goal.tick();

        FurnaceBlockEntity furnace = (FurnaceBlockEntity) helper.getLevel().getBlockEntity(furnacePos);
        helper.assertValueEqual(countItem(lumberjack, Items.OAK_LOG), 7,
                "charcoal fuel path consumed a log instead of using charcoal");
        helper.assertValueEqual(countItem(lumberjack, Items.CHARCOAL), 0,
                "charcoal fuel remained in the villager inventory after insertion");
        helper.assertTrue(furnace != null && furnace.getItem(1).is(Items.CHARCOAL),
                "charcoal was not inserted as furnace fuel");
        helper.assertValueEqual(lumberjack.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).getCharcoalQuota(), 7.0D,
                "the charcoal-fueled conversion did not charge exactly one assigned log");
        goal.stop();
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackChargesQuotaWhenFurnaceInputIsInserted(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_LOG, 9));
        BlockPos furnacePos = installNearbyFurnace(helper);
        lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                .setCharcoalQuota(8.0D);

        LumberjackGoal goal = new LumberjackGoal(lumberjack);
        helper.assertTrue(goal.canUse(), "eight assigned logs did not select charcoal production");
        goal.start();
        goal.tick();

        FurnaceBlockEntity furnace = (FurnaceBlockEntity) helper.getLevel().getBlockEntity(furnacePos);
        helper.assertTrue(furnace != null && !furnace.getItem(0).isEmpty(),
                "the lumberjack did not insert a log before the interruption test");
        helper.assertValueEqual(lumberjack.getData(
                        EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).getCharcoalQuota(), 7.0D,
                "the lumberjack did not charge the quota when the furnace input was inserted");

        goal.stop();
        helper.assertValueEqual(lumberjack.getData(
                        EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).getCharcoalQuota(), 7.0D,
                "stopping an in-flight furnace job must not restore or duplicate its quota");

        for (int tick = 0; tick < 220; tick++) {
            tickFurnace(helper, furnacePos);
        }
        LumberjackGoal resumedGoal = new LumberjackGoal(lumberjack);
        helper.assertTrue(resumedGoal.canUse(),
                "the lumberjack did not resume its tracked furnace conversion after interruption");
        resumedGoal.start();
        resumedGoal.tick();
        helper.assertValueEqual(countItem(lumberjack, Items.CHARCOAL), 1,
                "the resumed lumberjack did not collect the completed charcoal");
        helper.assertTrue(((FurnaceBlockEntity) helper.getLevel().getBlockEntity(furnacePos))
                        .getItem(2).isEmpty(),
                "the completed charcoal remained in the furnace after resumption");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackRetriesCharcoalCollectionAfterInventoryFills(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_LOG, 9));
        BlockPos furnacePos = installNearbyFurnace(helper);
        lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                .setCharcoalQuota(8.0D);

        LumberjackGoal goal = new LumberjackGoal(lumberjack);
        helper.assertTrue(goal.canUse(), "eight assigned logs did not select charcoal production");
        goal.start();
        goal.tick();
        goal.stop();

        for (int slot = 0; slot < lumberjack.getInventory().getContainerSize(); slot++) {
            if (lumberjack.getInventory().getItem(slot).isEmpty()) {
                lumberjack.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
            }
        }
        for (int tick = 0; tick < 220; tick++) {
            tickFurnace(helper, furnacePos);
        }

        LumberjackGoal blockedGoal = new LumberjackGoal(lumberjack);
        helper.assertTrue(blockedGoal.canUse(),
                "the lumberjack did not resume a completed conversion with a full inventory");
        blockedGoal.start();
        blockedGoal.tick();
        blockedGoal.stop();
        FurnaceBlockEntity furnace = (FurnaceBlockEntity) helper.getLevel().getBlockEntity(furnacePos);
        helper.assertTrue(furnace != null && furnace.getItem(2).is(Items.CHARCOAL),
                "the full-inventory failure did not preserve the furnace result for retry");

        for (int slot = 0; slot < lumberjack.getInventory().getContainerSize(); slot++) {
            if (lumberjack.getInventory().getItem(slot).is(Items.DIRT)) {
                lumberjack.getInventory().setItem(slot, ItemStack.EMPTY);
                break;
            }
        }
        LumberjackGoal retryGoal = new LumberjackGoal(lumberjack);
        helper.assertTrue(retryGoal.canUse(),
                "the lumberjack did not retry tracked charcoal after inventory space returned");
        retryGoal.start();
        retryGoal.tick();
        helper.assertValueEqual(countItem(lumberjack, Items.CHARCOAL), 1,
                "the lumberjack did not collect charcoal after the inventory retry");
        helper.assertTrue(furnace.getItem(2).isEmpty(),
                "the charcoal result remained in the furnace after the inventory retry");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackPicksUpSaplings(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));

        helper.assertTrue(com.orangevillager61.emeraldcapitalism.util.VillagerPickupCompat.wants(
                        lumberjack, helper.getLevel(), new ItemStack(Items.OAK_SAPLING)),
                "lumberjack did not accept a sapling when its work inventory had space");
        helper.assertTrue(com.orangevillager61.emeraldcapitalism.util.VillagerPickupCompat.wants(
                        lumberjack, helper.getLevel(), new ItemStack(Items.OAK_LOG)),
                "lumberjack did not accept a harvested log when its work inventory had space");
        helper.assertTrue(com.orangevillager61.emeraldcapitalism.util.VillagerPickupCompat.wants(
                        lumberjack, helper.getLevel(), new ItemStack(Items.STICK)),
                "lumberjack did not accept sticks from harvested leaves when its work inventory had space");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackDoesNotSelectLogsInsideItsHomeArea(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        lumberjack.getBrain().setMemory(MemoryModuleType.HOME,
                GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 1, 1))));
        installSmallTree(helper);

        helper.assertFalse(new LumberjackGoal(lumberjack).canUse(),
                "lumberjack selected logs inside its protected home area");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackReachesLogsAboveItsHead(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        BlockPos base = installTallTree(helper);

        LumberjackGoal goal = new LumberjackGoal(lumberjack);
        helper.assertTrue(goal.canUse(), "lumberjack did not detect the tall supported tree");
        goal.start();
        for (int tick = 0; tick < 5 * 60; tick++) {
            goal.tick();
        }
        goal.tick();

        helper.assertTrue(lumberjack.getNavigation().isDone(),
                "lumberjack navigated away instead of reaching the upper log from below");
        helper.assertTrue(helper.getLevel().getBlockState(base.above(5)).is(Blocks.OAK_LOG),
                "lumberjack did not retain the upper log as its current work target");
        goal.stop();
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackReachesLogsTenBlocksAboveHead(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        BlockPos base = installTreeRequiringTenBlockReach(helper);

        LumberjackGoal goal = new LumberjackGoal(lumberjack);
        helper.assertTrue(goal.canUse(), "lumberjack did not detect the ten-block tree");
        goal.start();
        for (int tick = 0; tick < 12 * 61 + 2; tick++) {
            goal.tick();
        }
        goal.tick();

        helper.assertTrue(helper.getLevel().getBlockState(base).is(Blocks.OAK_SAPLING),
                "lumberjack could not harvest a log ten blocks above its head");
        goal.stop();
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackReservesSelectedTree(GameTestHelper helper) {
        Villager first = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        first.setVillagerData(first.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        Villager second = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 1);
        second.setVillagerData(second.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        installSmallTree(helper);

        LumberjackGoal firstGoal = new LumberjackGoal(first);
        helper.assertTrue(firstGoal.canUse(), "the first lumberjack did not select the test tree");
        firstGoal.start();

        LumberjackGoal blockedGoal = new LumberjackGoal(second);
        helper.assertFalse(blockedGoal.canUse(),
                "the second lumberjack selected a tree already reserved by the first");
        helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(1, 1, 2)))
                        .is(Blocks.OAK_LOG),
                "the blocked lumberjack changed the reserved tree");

        firstGoal.stop();
        LumberjackGoal releasedGoal = new LumberjackGoal(second);
        helper.assertTrue(releasedGoal.canUse(),
                "the selected tree reservation was not released when the first goal stopped");
        releasedGoal.stop();
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void isolatedLogsAreNotSelectedAsTrees(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        helper.setBlock(new BlockPos(1, 1, 2), Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(new BlockPos(1, 2, 2), Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(new BlockPos(1, 0, 2), Blocks.DIRT.defaultBlockState());

        helper.assertFalse(new LumberjackGoal(lumberjack).canUse(),
                "lumberjack selected logs without an attached leaf canopy");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void horizontalLogConstructionWithLeavesIsNotSelected(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));

        BlockPos first = new BlockPos(1, 1, 2);
        helper.setBlock(first.below(), Blocks.DIRT.defaultBlockState());
        helper.setBlock(first, Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(first.east(), Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(first.east(2), Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(first.above(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(first.east().above(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(first.east(2).above(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(first.above(2), Blocks.OAK_LEAVES.defaultBlockState());

        helper.assertFalse(new LumberjackGoal(lumberjack).canUse(),
                "lumberjack selected a horizontal log construction as a tree");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void cherryTreesAreNotSelected(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));

        BlockPos base = new BlockPos(1, 1, 2);
        helper.setBlock(base.below(), Blocks.DIRT.defaultBlockState());
        helper.setBlock(base, Blocks.CHERRY_LOG.defaultBlockState());
        helper.setBlock(base.above(), Blocks.CHERRY_LOG.defaultBlockState());
        helper.setBlock(base.above(2), Blocks.CHERRY_LOG.defaultBlockState());
        helper.setBlock(base.above(3), Blocks.CHERRY_LEAVES.defaultBlockState());
        helper.setBlock(base.above(2).west(), Blocks.CHERRY_LEAVES.defaultBlockState());
        helper.setBlock(base.above(2).east(), Blocks.CHERRY_LEAVES.defaultBlockState());
        helper.setBlock(base.above(2).north(), Blocks.CHERRY_LEAVES.defaultBlockState());
        helper.setBlock(base.above(2).south(), Blocks.CHERRY_LEAVES.defaultBlockState());

        helper.assertFalse(new LumberjackGoal(lumberjack).canUse(),
                "lumberjack selected a cherry tree that should be preserved");
        helper.succeed();
    }

    private static BlockPos installSmallTree(GameTestHelper helper) {
        enableMobGriefing(helper);
        BlockPos base = new BlockPos(1, 1, 2);
        helper.setBlock(base.below(), Blocks.DIRT.defaultBlockState());
        helper.setBlock(base, Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(base.above(), Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(base.above(2), Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(base.above(3), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(base.above(2).west(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(base.above(2).east(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(base.above(2).north(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(base.above(2).south(), Blocks.OAK_LEAVES.defaultBlockState());
        return helper.absolutePos(base);
    }

    private static BlockPos installTallTree(GameTestHelper helper) {
        enableMobGriefing(helper);
        BlockPos base = new BlockPos(1, 1, 2);
        helper.setBlock(base.below(), Blocks.DIRT.defaultBlockState());
        for (int height = 0; height <= 5; height++) {
            helper.setBlock(base.above(height), Blocks.OAK_LOG.defaultBlockState());
        }
        helper.setBlock(base.above(5).above(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(base.above(5).west(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(base.above(5).east(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(base.above(5).north(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(base.above(5).south(), Blocks.OAK_LEAVES.defaultBlockState());
        return helper.absolutePos(base);
    }

    private static BlockPos installNearbyFurnace(GameTestHelper helper) {
        BlockPos furnace = new BlockPos(2, 1, 1);
        helper.setBlock(furnace, Blocks.FURNACE.defaultBlockState());
        return helper.absolutePos(furnace);
    }

    private static BlockPos installTreeRequiringTenBlockReach(GameTestHelper helper) {
        enableMobGriefing(helper);
        BlockPos base = new BlockPos(1, 1, 2);
        helper.setBlock(base.below(), Blocks.DIRT.defaultBlockState());
        for (int height = 0; height < 12; height++) {
            helper.setBlock(base.above(height), Blocks.OAK_LOG.defaultBlockState());
        }
        helper.setBlock(base.above(12), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(base.above(11).west(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(base.above(11).east(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(base.above(11).north(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(base.above(11).south(), Blocks.OAK_LEAVES.defaultBlockState());
        return helper.absolutePos(base);
    }

    private static void enableMobGriefing(GameTestHelper helper) {
        helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING)
                .set(true, helper.getLevel().getServer());
    }

    private static void harvestTree(GameTestHelper helper, LumberjackGoal goal) {
        helper.assertTrue(goal.canUse(), "lumberjack did not detect the test tree");
        goal.start();
        harvestTreeAfterStart(helper, goal);
    }

    private static void harvestTreeAfterStart(GameTestHelper helper, LumberjackGoal goal) {
        for (int tick = 0; tick < 3 * 61 + 2; tick++) {
            goal.tick();
        }
        BlockPos furnacePos = helper.absolutePos(new BlockPos(2, 1, 1));
        if (helper.getLevel().getBlockEntity(furnacePos) instanceof FurnaceBlockEntity) {
            for (int tick = 0; tick < 450; tick++) {
                goal.tick();
                tickFurnace(helper, furnacePos);
            }
        }
    }

    private static void tickFurnace(GameTestHelper helper, BlockPos furnacePos) {
        if (helper.getLevel().getBlockEntity(furnacePos) instanceof FurnaceBlockEntity furnace) {
            AbstractFurnaceBlockEntity.serverTick(helper.getLevel(), furnacePos,
                    helper.getLevel().getBlockState(furnacePos), furnace);
        }
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
