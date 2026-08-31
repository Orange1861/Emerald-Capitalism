package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.entity.ai.LumberjackGoal;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

        helper.assertValueEqual(countItem(lumberjack, Items.OAK_LOG), 1,
                "the bootstrap fuel log was not consumed before charcoal production");
        helper.assertValueEqual(countItem(lumberjack, Items.CHARCOAL), 1,
                "the lumberjack did not collect furnace-produced charcoal");
        helper.assertValueEqual(countItem(lumberjack, Items.OAK_PLANKS), 0,
                "bootstrap planks were incorrectly returned to the lumberjack inventory");
        helper.assertValueEqual(((FurnaceBlockEntity) helper.getLevel().getBlockEntity(furnacePos))
                        .getItem(1).getCount(), 3,
                "the lumberjack did not make four planks and use the first as furnace fuel");
        helper.assertTrue(helper.getLevel().getBlockState(base).is(Blocks.OAK_SAPLING),
                "lumberjack did not replant a compatible sapling at the tree base");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackRetainsLogsAcrossRepeatedTreeBatches(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        installNearbyFurnace(helper);

        LumberjackGoal goal = new LumberjackGoal(lumberjack);
        installSmallTree(helper);
        harvestTree(helper, goal);
        helper.assertValueEqual(countItem(lumberjack, Items.OAK_LOG), 1,
                "the first tree did not reserve one log for bootstrap fuel");
        helper.assertValueEqual(countItem(lumberjack, Items.CHARCOAL), 1,
                "the first tree did not produce its charcoal share");

        installSmallTree(helper);
        LumberjackGoal nextGoal = new LumberjackGoal(lumberjack);
        helper.assertTrue(nextGoal.canUse(), "lumberjack did not select the next replanted tree");
        nextGoal.start();
        harvestTreeAfterStart(helper, nextGoal);

        helper.assertValueEqual(countItem(lumberjack, Items.OAK_LOG), 2,
                "retained logs were recounted as newly harvested logs");
        helper.assertValueEqual(countItem(lumberjack, Items.CHARCOAL), 3,
                "the rolling quota did not convert the complete accumulated portion");
        helper.assertValueEqual(lumberjack.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).getCharcoalQuota(), 0.0D,
                "the completed rolling quota left an unexpected remainder");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackUsesCharcoalAsFurnaceFuel(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        lumberjack.getInventory().addItem(new ItemStack(Items.CHARCOAL));
        installNearbyFurnace(helper);
        installSmallTree(helper);

        harvestTree(helper, new LumberjackGoal(lumberjack));

        helper.assertValueEqual(countItem(lumberjack, Items.OAK_LOG), 2,
                "charcoal fuel path consumed a log instead of using charcoal");
        helper.assertValueEqual(countItem(lumberjack, Items.CHARCOAL), 1,
                "the furnace-produced charcoal was not returned to the lumberjack");
        helper.assertValueEqual(lumberjack.getData(
                EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).getCharcoalQuota(), 0.5D,
                "the charcoal-fueled conversion discarded the fractional quota");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackChargesQuotaWhenFurnaceInputIsInserted(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        BlockPos furnacePos = installNearbyFurnace(helper);
        installSmallTree(helper);

        LumberjackGoal goal = new LumberjackGoal(lumberjack);
        harvestTreeThroughInputInsertion(helper, goal);

        FurnaceBlockEntity furnace = (FurnaceBlockEntity) helper.getLevel().getBlockEntity(furnacePos);
        helper.assertTrue(furnace != null && !furnace.getItem(0).isEmpty(),
                "the lumberjack did not insert a log before the interruption test");
        helper.assertValueEqual(lumberjack.getData(
                        EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).getCharcoalQuota(), 0.5D,
                "the lumberjack did not charge the quota when the furnace input was inserted");

        goal.stop();
        helper.assertValueEqual(lumberjack.getData(
                        EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).getCharcoalQuota(), 0.5D,
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
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        BlockPos furnacePos = installNearbyFurnace(helper);
        installSmallTree(helper);

        LumberjackGoal goal = new LumberjackGoal(lumberjack);
        harvestTreeThroughInputInsertion(helper, goal);
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

        helper.assertTrue(lumberjack.wantsToPickUp(new ItemStack(Items.OAK_SAPLING)),
                "lumberjack did not accept a sapling when its work inventory had space");
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

    private static void harvestTreeThroughInputInsertion(GameTestHelper helper, LumberjackGoal goal) {
        helper.assertTrue(goal.canUse(), "lumberjack did not detect the test tree");
        goal.start();
        for (int tick = 0; tick < 3 * 61 + 2; tick++) {
            goal.tick();
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
