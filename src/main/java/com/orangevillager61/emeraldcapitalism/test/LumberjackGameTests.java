package com.orangevillager61.emeraldcapitalism.test;

import com.mojang.datafixers.util.Pair;
import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.behavior.BrainMovementIntent;
import com.orangevillager61.emeraldcapitalism.entity.ai.LumberjackWorkBehavior;
import com.orangevillager61.emeraldcapitalism.entity.ai.VillagerSessionCleanup;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.world.forestry.FurnacePool;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.VillagerGoalPackages;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Natural-AI tests for the lumberjack's Brain behavior. Every test spawns a real lumberjack with
 * its ordinary Brain at work hours and lets it do the whole job; no test constructs, starts, or
 * ticks the behavior.
 */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class LumberjackGameTests {

    /**
     * Tests of one batch run side by side in neighbouring cells, and a lumberjack searches 24
     * blocks, so a furnace test must not share a batch with tests that build trees.
     */
    private static final String BATCH = "ecap_lumberjack_brain";
    private static final String PRODUCTION_BATCH = "ecap_lumberjack_production";
    private static final long WORK_DAY_TIME = 2_500L;
    private static final BlockPos SAWMILL = new BlockPos(15, 1, 1);
    private static final BlockPos TREE_BASE = new BlockPos(15, 1, 18);

    private LumberjackGameTests() {
    }

    // ------------------------------------------------------------------ felling and replanting

    @GameTest(template = "empty_20x3x20", batch = BATCH, timeoutTicks = 1_800)
    public static void lumberjackFellsASmallTreeCollectsItsLogsAndReplants(GameTestHelper helper) {
        prepareWork(helper);
        buildTree(helper, TREE_BASE, 3);
        Villager lumberjack = lumberjack(helper, 15, 1, 3);
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        BlockPos base = helper.absolutePos(TREE_BASE);

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getBlockState(base).is(Blocks.OAK_SAPLING),
                    "the lumberjack has not replanted the tree it felled");
            helper.assertTrue(!helper.getLevel().getBlockState(base.above()).is(Blocks.OAK_LOG)
                            && !helper.getLevel().getBlockState(base.above(2)).is(Blocks.OAK_LOG),
                    "logs of the selected tree are still standing");
            helper.assertTrue(count(lumberjack, Items.OAK_LOG) >= 3,
                    "the felled logs did not reach the lumberjack's inventory");
        });
    }

    @GameTest(template = "empty_20x3x20", batch = BATCH, timeoutTicks = 1_800)
    public static void lumberjackReachesLogsAboveItsHead(GameTestHelper helper) {
        prepareWork(helper);
        buildTree(helper, TREE_BASE, 6);
        Villager lumberjack = lumberjack(helper, 15, 1, 3);
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        BlockPos base = helper.absolutePos(TREE_BASE);

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getBlockState(base).is(Blocks.OAK_SAPLING),
                    "the lumberjack did not finish a tall tree");
            helper.assertTrue(count(lumberjack, Items.OAK_LOG) >= 6,
                    "the upper logs of the tall tree were not all collected");
        });
    }

    @GameTest(template = "empty_20x3x20", batch = BATCH, timeoutTicks = 2_600)
    public static void lumberjackReachesLogsTenBlocksAboveHead(GameTestHelper helper) {
        prepareWork(helper);
        buildTree(helper, TREE_BASE, 12);
        Villager lumberjack = lumberjack(helper, 15, 1, 3);
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        BlockPos base = helper.absolutePos(TREE_BASE);

        helper.succeedWhen(() -> helper.assertTrue(
                helper.getLevel().getBlockState(base).is(Blocks.OAK_SAPLING),
                "the lumberjack could not harvest a log ten blocks above its head"));
    }

    @GameTest(template = "empty_20x3x20", batch = BATCH, timeoutTicks = 2_200)
    public static void twoLumberjacksNeverWorkTheSameTree(GameTestHelper helper) {
        prepareWork(helper);
        buildTree(helper, TREE_BASE, 3);
        Villager first = lumberjack(helper, 15, 1, 3);
        Villager second = lumberjack(helper, 14, 1, 3);
        first.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        second.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        BlockPos base = helper.absolutePos(TREE_BASE);

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getBlockState(base).is(Blocks.OAK_SAPLING),
                    "the tree was not felled and replanted");
            helper.assertValueEqual(count(first, Items.OAK_LOG) + count(second, Items.OAK_LOG), 3,
                    "the two lumberjacks did not collect exactly the tree's three logs between them");
            // Leaves drop saplings at random, so the count of saplings left is not fixed; the one
            // planted base block above proves exactly one lumberjack replanted.
            helper.assertTrue(count(first, Items.OAK_SAPLING) + count(second, Items.OAK_SAPLING) >= 1,
                    "a sapling should have been used on the tree base");
        });
    }

    // ------------------------------------------------------------------ trees that are left alone

    @GameTest(template = "empty_20x3x20", batch = BATCH, timeoutTicks = 700)
    public static void isolatedLogsAreLeftAlone(GameTestHelper helper) {
        prepareWork(helper);
        BlockPos log = new BlockPos(15, 1, 10);
        helper.setBlock(log.below(), Blocks.DIRT.defaultBlockState());
        helper.setBlock(log, Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(log.above(), Blocks.OAK_LOG.defaultBlockState());
        lumberjack(helper, 15, 1, 3);
        standsUntouched(helper, List.of(log, log.above()), Blocks.OAK_LOG, "logs without a leaf canopy");
    }

    @GameTest(template = "empty_20x3x20", batch = BATCH, timeoutTicks = 700)
    public static void horizontalLogConstructionsAreLeftAlone(GameTestHelper helper) {
        prepareWork(helper);
        BlockPos first = new BlockPos(15, 1, 10);
        helper.setBlock(first.below(), Blocks.DIRT.defaultBlockState());
        helper.setBlock(first, Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(first.east(), Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(first.east(2), Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(first.above(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(first.east().above(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(first.above(2), Blocks.OAK_LEAVES.defaultBlockState());
        lumberjack(helper, 15, 1, 3);
        standsUntouched(helper, List.of(first, first.east(), first.east(2)), Blocks.OAK_LOG,
                "a horizontal log construction");
    }

    @GameTest(template = "empty_20x3x20", batch = BATCH, timeoutTicks = 700)
    public static void cherryTreesArePreserved(GameTestHelper helper) {
        prepareWork(helper);
        BlockPos base = new BlockPos(15, 1, 10);
        helper.setBlock(base.below(), Blocks.DIRT.defaultBlockState());
        for (int height = 0; height < 3; height++) {
            helper.setBlock(base.above(height), Blocks.CHERRY_LOG.defaultBlockState());
        }
        helper.setBlock(base.above(3), Blocks.CHERRY_LEAVES.defaultBlockState());
        helper.setBlock(base.above(2).west(), Blocks.CHERRY_LEAVES.defaultBlockState());
        helper.setBlock(base.above(2).east(), Blocks.CHERRY_LEAVES.defaultBlockState());
        lumberjack(helper, 15, 1, 3);
        standsUntouched(helper, List.of(base, base.above(), base.above(2)), Blocks.CHERRY_LOG,
                "a cherry tree");
    }

    @GameTest(template = "empty_20x3x20", batch = BATCH, timeoutTicks = 700)
    public static void logsInsideTheLumberjacksHomeAreaAreLeftAlone(GameTestHelper helper) {
        prepareWork(helper);
        buildTree(helper, TREE_BASE, 3);
        Villager lumberjack = lumberjack(helper, 15, 1, 3);
        lumberjack.getBrain().setMemory(MemoryModuleType.HOME,
                GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(TREE_BASE.south())));
        BlockPos base = TREE_BASE;
        standsUntouched(helper, List.of(base, base.above(), base.above(2)), Blocks.OAK_LOG,
                "logs inside the protected home area");
    }

    // ------------------------------------------------------------------ the shared furnace

    @GameTest(template = "empty_20x3x20", batch = PRODUCTION_BATCH, timeoutTicks = 3_200)
    public static void lumberjackLoadsASharedFurnaceAndCollectsItsCharcoal(GameTestHelper helper) {
        prepareWork(helper);
        Village village = village(helper);
        BlockPos furnace = placeFurnace(helper, village, new BlockPos(13, 1, 3));
        Villager lumberjack = lumberjack(helper, 15, 1, 3);
        village.record().addMember(member(lumberjack, helper.getLevel()));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_LOG, 8));
        lumberjack.getInventory().addItem(new ItemStack(Items.CHARCOAL));
        lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).setCharcoalQuota(8.0D);

        helper.succeedWhen(() -> {
            FurnacePool pool = village.record().getFurnacePool(helper.getLevel().getGameTime());
            helper.assertTrue(count(lumberjack, Items.CHARCOAL) >= 8,
                    "the lumberjack has not collected the batch's charcoal");
            helper.assertValueEqual(count(lumberjack, Items.OAK_LOG), 0,
                    "the batch's logs were not all loaded into the furnace");
            helper.assertTrue(pool.claimOf(lumberjack.getUUID()).isEmpty(),
                    "the claim on the furnace was not released after the last charcoal was collected");
            helper.assertTrue(lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                            .getPendingCharcoalFurnace().isEmpty(),
                    "the saved furnace marker was not cleared after collection");
            FurnaceBlockEntity entity = (FurnaceBlockEntity) helper.getLevel().getBlockEntity(furnace);
            helper.assertTrue(entity != null && entity.getItem(0).isEmpty() && entity.getItem(2).isEmpty(),
                    "the furnace was not left empty");
        });
    }

    @GameTest(template = "empty_20x3x20", batch = PRODUCTION_BATCH, timeoutTicks = 1_500)
    public static void aFurnaceHoldingSomeoneElsesItemsIsNeverLoadedOrEmptied(GameTestHelper helper) {
        prepareWork(helper);
        Village village = village(helper);
        BlockPos furnace = placeFurnace(helper, village, new BlockPos(13, 1, 3));
        FurnaceBlockEntity entity = (FurnaceBlockEntity) helper.getLevel().getBlockEntity(furnace);
        entity.setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        Villager lumberjack = lumberjack(helper, 15, 1, 3);
        village.record().addMember(member(lumberjack, helper.getLevel()));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_LOG, 8));
        lumberjack.getInventory().addItem(new ItemStack(Items.CHARCOAL));
        lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).setCharcoalQuota(8.0D);

        helper.runAfterDelay(FurnacePool.RELOAD_GRACE_TICKS + 700, () -> {
            helper.assertValueEqual(entity.getItem(0).getCount(), 5,
                    "a player's input was changed by the lumberjack");
            helper.assertTrue(entity.getItem(0).is(Items.COBBLESTONE),
                    "a player's input was replaced");
            helper.assertValueEqual(count(lumberjack, Items.OAK_LOG), 8,
                    "logs were loaded into a furnace that held someone else's items");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_20x3x20", batch = BATCH, timeoutTicks = 1_800)
    public static void aVillageWithNoPoolFurnaceOwesNoCharcoal(GameTestHelper helper) {
        prepareWork(helper);
        Village village = village(helper);
        village.record().reconcileFurnacePool(helper.getLevel());
        buildTree(helper, TREE_BASE, 3);
        Villager lumberjack = lumberjack(helper, 15, 1, 3);
        village.record().addMember(member(lumberjack, helper.getLevel()));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).setCharcoalQuota(5.0D);
        BlockPos base = helper.absolutePos(TREE_BASE);

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getBlockState(base).is(Blocks.OAK_SAPLING),
                    "the tree was not felled");
            helper.assertTrue(lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                            .getCharcoalQuota() == 0.0D,
                    "a village without a pool furnace still owes charcoal");
        });
    }


    // ------------------------------------------------------------------ interruptions

    @GameTest(template = "empty_20x3x20", batch = BATCH, timeoutTicks = 1_200)
    public static void panicStopsTheLumberjacksWorkAndWorkResumes(GameTestHelper helper) {
        interruptionTest(helper, Activity.PANIC);
    }

    @GameTest(template = "empty_20x3x20", batch = BATCH, timeoutTicks = 1_200)
    public static void restStopsTheLumberjacksWorkAndWorkResumes(GameTestHelper helper) {
        interruptionTest(helper, Activity.REST);
    }

    @GameTest(template = "empty_20x3x20", batch = BATCH, timeoutTicks = 1_200)
    public static void raidStopsTheLumberjacksWorkAndWorkResumes(GameTestHelper helper) {
        interruptionTest(helper, Activity.RAID);
    }

    /**
     * Brain-level component test: the villager's AI is off and the test ticks its Brain once per
     * game tick, so the activity is under the test's control. The behavior is never ticked directly.
     */
    private static void interruptionTest(GameTestHelper helper, Activity interrupting) {
        prepareWork(helper);
        buildTree(helper, TREE_BASE, 6);
        Villager lumberjack = lumberjack(helper, 15, 1, 3);
        lumberjack.setNoAi(true);
        lumberjack.setOnGround(true);
        lumberjack.getBrain().setActiveActivityIfPossible(Activity.WORK);
        driveUntil(helper, lumberjack, 400, () -> running(lumberjack),
                "the lumberjack's work never started", () -> {
                    lumberjack.getBrain().setActiveActivityIfPossible(interrupting);
                    // The Brain stops a behavior of the old activity on its next tick.
                    driveFor(helper, lumberjack, 2, () -> null, () ->
                            driveFor(helper, lumberjack, 40, () -> running(lumberjack)
                                    ? "the work behavior kept running in the " + interrupting + " activity"
                                    : ownsWalkTarget(lumberjack)
                                    ? "the lumberjack left a walk target in the " + interrupting + " activity"
                                    : null, () -> {
                                lumberjack.getBrain().setActiveActivityIfPossible(Activity.WORK);
                                // The Brain leaves a behavior of the old activity running, and with the AI
                                // off nothing consumes the walk target it keeps writing (a hiding
                                // place, a flee direction), so it is cleared on every tick here.
                                driveResuming(helper, lumberjack, 400,
                                        "the lumberjack's work did not resume after " + interrupting,
                                        helper::succeed);
                            }));
                });
    }

    @GameTest(template = "empty_20x3x20", batch = PRODUCTION_BATCH, timeoutTicks = 800)
    public static void aLumberjackThatLeavesHandsItsFurnaceClaimToTheVillage(GameTestHelper helper) {
        prepareWork(helper);
        Village village = village(helper);
        BlockPos furnace = placeFurnace(helper, village, new BlockPos(13, 1, 3));
        FurnacePool pool = village.record().getFurnacePool(helper.getLevel().getGameTime());
        Villager lumberjack = lumberjack(helper, 15, 1, 3);
        village.record().addMember(member(lumberjack, helper.getLevel()));
        long later = helper.getLevel().getGameTime() + FurnacePool.RELOAD_GRACE_TICKS + 1L;
        helper.assertTrue(pool.request(lumberjack.getUUID(), later).isPresent(),
                "the lumberjack was not given the furnace");

        // The same cleanup that unloading and dying run through.
        VillagerSessionCleanup.onEntityLeaveLevel(new EntityLeaveLevelEvent(lumberjack, helper.getLevel()));

        helper.assertTrue(pool.isOrphaned(furnace.asLong()),
                "the furnace was not handed to the village when its lumberjack left");
        helper.assertTrue(pool.claimOf(lumberjack.getUUID()).isEmpty(),
                "the departed lumberjack still holds a claim");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", batch = PRODUCTION_BATCH, timeoutTicks = 800)
    public static void aProfessionChangeHandsTheFurnaceClaimToTheVillage(GameTestHelper helper) {
        prepareWork(helper);
        Village village = village(helper);
        BlockPos furnace = placeFurnace(helper, village, new BlockPos(13, 1, 3));
        FurnacePool pool = village.record().getFurnacePool(helper.getLevel().getGameTime());
        Villager lumberjack = lumberjack(helper, 15, 1, 3);
        village.record().addMember(member(lumberjack, helper.getLevel()));
        long later = helper.getLevel().getGameTime() + FurnacePool.RELOAD_GRACE_TICKS + 1L;
        pool.request(lumberjack.getUUID(), later);

        VillagerSessionCleanup.onProfessionChanged(lumberjack);

        helper.assertTrue(pool.isOrphaned(furnace.asLong()),
                "the furnace was not handed to the village when the lumberjack lost its profession");
        helper.succeed();
    }

    // ------------------------------------------------------------------ furnace edge cases

    @GameTest(template = "empty_20x3x20", batch = PRODUCTION_BATCH, timeoutTicks = 1_600)
    public static void aFurnaceRemovedWhileClaimedDropsTheClaimAndTheMarker(GameTestHelper helper) {
        prepareWork(helper);
        Village village = village(helper);
        BlockPos furnace = placeFurnace(helper, village, new BlockPos(13, 1, 3));
        Villager lumberjack = lumberjack(helper, 15, 1, 3);
        village.record().addMember(member(lumberjack, helper.getLevel()));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_LOG, 8));
        lumberjack.getInventory().addItem(new ItemStack(Items.CHARCOAL));
        lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).setCharcoalQuota(8.0D);

        boolean[] removed = {false};
        helper.succeedWhen(() -> {
            FurnacePool pool = village.record().getFurnacePool(helper.getLevel().getGameTime());
            // The registry manager reads one village's chunks at a time, many tests deep; this
            // calls the same throttled production reconciliation directly.
            village.record().reconcileFurnacePool(helper.getLevel());
            if (!removed[0]) {
                if (pool.claimOf(lumberjack.getUUID()).isPresent()
                        && lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                        .getPendingCharcoalFurnace().isPresent()) {
                    // The batch is loaded: now the furnace disappears.
                    helper.getLevel().setBlock(furnace, Blocks.AIR.defaultBlockState(), 3);
                    removed[0] = true;
                }
                helper.fail("waiting for the lumberjack to load the furnace");
            }
            helper.assertFalse(pool.containsFurnace(furnace.asLong()),
                    "the removed furnace stayed in the pool");
            helper.assertTrue(pool.claimOf(lumberjack.getUUID()).isEmpty(),
                    "the claim on a removed furnace survived");
            helper.assertTrue(lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                            .getPendingCharcoalFurnace().isEmpty(),
                    "the saved furnace marker survived the furnace");
        });
    }

    @GameTest(template = "empty_20x3x20", batch = PRODUCTION_BATCH, timeoutTicks = 6_000)
    public static void twoLumberjacksShareOneFurnaceOneAfterTheOther(GameTestHelper helper) {
        prepareWork(helper);
        Village village = village(helper);
        placeFurnace(helper, village, new BlockPos(13, 1, 3));
        Villager first = lumberjack(helper, 15, 1, 3);
        Villager second = lumberjack(helper, 14, 1, 3);
        for (Villager worker : List.of(first, second)) {
            village.record().addMember(member(worker, helper.getLevel()));
            worker.getInventory().addItem(new ItemStack(Items.OAK_LOG, 8));
            worker.getInventory().addItem(new ItemStack(Items.CHARCOAL));
            worker.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).setCharcoalQuota(8.0D);
        }

        helper.onEachTick(() -> {
            FurnacePool pool = village.record().getFurnacePool(helper.getLevel().getGameTime());
            helper.assertFalse(pool.claimOf(first.getUUID()).isPresent()
                            && pool.claimOf(second.getUUID()).isPresent(),
                    "two lumberjacks held claims on the one furnace at the same time");
        });
        helper.succeedWhen(() -> {
            FurnacePool pool = village.record().getFurnacePool(helper.getLevel().getGameTime());
            helper.assertTrue(count(first, Items.CHARCOAL) >= 8 && count(second, Items.CHARCOAL) >= 8,
                    "both lumberjacks should eventually collect their batch's charcoal: first charcoal="
                            + count(first, Items.CHARCOAL) + " logs=" + count(first, Items.OAK_LOG)
                            + " claim=" + pool.claimOf(first.getUUID()).isPresent()
                            + ", second charcoal=" + count(second, Items.CHARCOAL)
                            + " logs=" + count(second, Items.OAK_LOG)
                            + " claim=" + pool.claimOf(second.getUUID()).isPresent()
                            + ", queue=" + pool.queueOrder().size() + " furnaces=" + pool.furnaces().size()
                            + ", firstQuota=" + first.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                            .getCharcoalQuota()
                            + " secondQuota=" + second.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                            .getCharcoalQuota());
            helper.assertValueEqual(count(first, Items.OAK_LOG) + count(second, Items.OAK_LOG), 0,
                    "a batch's logs were left in an inventory");
        });
    }

    @GameTest(template = "empty_20x3x20", batch = PRODUCTION_BATCH, timeoutTicks = 1_400)
    public static void aLumberjackNeverUsesAnotherVillagesFurnace(GameTestHelper helper) {
        prepareWork(helper);
        ServerLevel level = helper.getLevel();
        VillageRegistryData registry = VillageRegistryData.get(level);
        VillageRecord villageA = registry.getOrCreateVillage(UUID.randomUUID(),
                helper.absolutePos(new BlockPos(2, 1, 2)), box(helper, 0, 9));
        VillageRecord villageB = registry.getOrCreateVillage(UUID.randomUUID(),
                helper.absolutePos(SAWMILL), box(helper, 10, 19));
        footprint(helper, registry, 0, 9);
        footprint(helper, registry, 10, 19);
        helper.setBlock(new BlockPos(5, 1, 3), Blocks.FURNACE.defaultBlockState());
        BlockPos foreign = helper.absolutePos(new BlockPos(5, 1, 3));
        helper.assertTrue(villageA.acceptFurnaceSighting(level, foreign),
                "village A did not accept its own furnace");
        helper.assertFalse(villageB.acceptFurnaceSighting(level, foreign),
                "village B accepted a furnace outside its bounds");

        Villager lumberjack = lumberjack(helper, 15, 1, 3);
        villageB.addMember(member(lumberjack, level));
        lumberjack.getInventory().addItem(new ItemStack(Items.OAK_LOG, 8));
        lumberjack.getInventory().addItem(new ItemStack(Items.CHARCOAL));
        lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION).setCharcoalQuota(8.0D);

        helper.runAfterDelay(FurnacePool.RELOAD_GRACE_TICKS + 700, () -> {
            helper.assertTrue(villageA.getFurnacePool(level.getGameTime())
                            .claimOf(lumberjack.getUUID()).isEmpty(),
                    "a lumberjack claimed another village's furnace");
            helper.assertTrue(((FurnaceBlockEntity) level.getBlockEntity(foreign)).getItem(0).isEmpty(),
                    "another village's furnace was loaded");
            helper.assertValueEqual(count(lumberjack, Items.OAK_LOG), 8,
                    "the lumberjack's logs left its inventory without a furnace of its own village");
            helper.succeed();
        });
    }

    /** A saved furnace marker becomes a claim again, as after a save and reload. */
    @GameTest(template = "empty_20x3x20", batch = PRODUCTION_BATCH, timeoutTicks = 900)
    public static void aSavedFurnaceMarkerIsRestoredAsAClaimAndItsCharcoalCollected(GameTestHelper helper) {
        prepareWork(helper);
        Village village = village(helper);
        BlockPos furnace = placeFurnace(helper, village, new BlockPos(13, 1, 3));
        FurnaceBlockEntity entity = (FurnaceBlockEntity) helper.getLevel().getBlockEntity(furnace);
        entity.setItem(2, new ItemStack(Items.CHARCOAL, 3));
        Villager lumberjack = lumberjack(helper, 15, 1, 3);
        village.record().addMember(member(lumberjack, helper.getLevel()));
        lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                .setPendingCharcoalFurnace(GlobalPos.of(helper.getLevel().dimension(), furnace));

        helper.succeedWhen(() -> {
            helper.assertTrue(count(lumberjack, Items.CHARCOAL) >= 3,
                    "the restored claim did not collect the charcoal waiting in the furnace");
            helper.assertTrue(lumberjack.getData(EmeraldCapitalismAttachments.LUMBERJACK_PRODUCTION)
                            .getPendingCharcoalFurnace().isEmpty(),
                    "the saved marker was not cleared after the claim finished");
        });
    }

    // ------------------------------------------------------------------ composition

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackWorkPackageReplacesVanillaMoversAndOtherJobsKeepThem(GameTestHelper helper) {
        List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> lumberjack =
                VillagerGoalPackages.getWorkPackage(ECAPVillagerProfessions.LUMBERJACK.get(), 0.5F);
        helper.assertValueEqual(count(lumberjack, LumberjackWorkBehavior.class), 1,
                "the lumberjack's work package must hold exactly one work behavior");
        helper.assertValueEqual(countNamed(lumberjack, "RunOne"), 1,
                "the lumberjack must keep only vanilla's look-around gate");
        // Vanilla's job-site walker is the package's only priority-2 entry.
        helper.assertValueEqual(countAtPriority(lumberjack, 2), 0,
                "vanilla's pull back to the job site must not run beside the lumberjack's work");
        helper.assertValueEqual(countAtPriority(VillagerGoalPackages.getWorkPackage(
                VillagerProfession.FARMER, 0.5F), 2), 1,
                "control: a farmer's work package keeps vanilla's job-site walker");
        for (VillagerProfession other : List.of(VillagerProfession.FARMER, VillagerProfession.LIBRARIAN,
                VillagerProfession.NONE)) {
            List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> work =
                    VillagerGoalPackages.getWorkPackage(other, 0.5F);
            helper.assertValueEqual(count(work, LumberjackWorkBehavior.class), 0,
                    "a non-lumberjack work package received the lumberjack behavior: " + other);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackWorkRunsOnlyOnTheBrain(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        villager.setVillagerData(villager.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        villager.refreshBrain(helper.getLevel());
        boolean legacyGoal = villager.goalSelector.getAvailableGoals().stream()
                .anyMatch(goal -> goal.getGoal().getClass().getSimpleName().contains("Lumberjack"));
        helper.assertFalse(legacyGoal, "a lumberjack goal is still registered on the GoalSelector");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void lumberjackPicksUpSaplingsLogsAndSticks(GameTestHelper helper) {
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        for (Item item : List.of(Items.OAK_SAPLING, Items.OAK_LOG, Items.STICK)) {
            helper.assertTrue(com.orangevillager61.emeraldcapitalism.util.VillagerPickupCompat.wants(
                            lumberjack, helper.getLevel(), new ItemStack(item)),
                    "the lumberjack did not accept " + item + " when its work inventory had space");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ fixtures

    private record Village(VillageRecord record) {
    }

    /** Work hours, mob griefing on, and a dirt floor; the Brain needs WORK hours and a job site. */
    private static void prepareWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(WORK_DAY_TIME);
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true, level.getServer());
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.DIRT.defaultBlockState());
            }
        }
        helper.setBlock(SAWMILL, ECAPBlocks.SAWMILL.get().defaultBlockState());
    }

    /** A normally registered lumberjack with its ordinary Brain, employed at the sawmill. */
    private static Villager lumberjack(GameTestHelper helper, int x, int y, int z) {
        ServerLevel level = helper.getLevel();
        Villager lumberjack = helper.spawnWithNoFreeWill(EntityType.VILLAGER, x, y, z);
        lumberjack.setVillagerData(lumberjack.getVillagerData()
                .setProfession(ECAPVillagerProfessions.LUMBERJACK.get()));
        // Keeps vanilla from resetting a level-one, zero-XP villager with no job on its first tick.
        lumberjack.setVillagerXp(1);
        lumberjack.refreshBrain(level);
        lumberjack.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(level.dimension(), helper.absolutePos(SAWMILL)));
        return lumberjack;
    }

    private static void buildTree(GameTestHelper helper, BlockPos base, int logs) {
        helper.setBlock(base.below(), Blocks.DIRT.defaultBlockState());
        for (int height = 0; height < logs; height++) {
            helper.setBlock(base.above(height), Blocks.OAK_LOG.defaultBlockState());
        }
        BlockPos top = base.above(logs - 1);
        helper.setBlock(top.above(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(top.west(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(top.east(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(top.north(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.setBlock(top.south(), Blocks.OAK_LEAVES.defaultBlockState());
    }

    private static void standsUntouched(GameTestHelper helper, List<BlockPos> positions, net.minecraft.world.level.block.Block block,
                                        String what) {
        helper.runAfterDelay(500, () -> {
            for (BlockPos position : positions) {
                helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(position)).is(block),
                        "the lumberjack cut " + what);
            }
            helper.succeed();
        });
    }

    /** The whole template is one village with a lumbermill footprint around the sawmill end. */
    private static Village village(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VillageRegistryData registry = VillageRegistryData.get(level);
        BlockPos min = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos max = helper.absolutePos(new BlockPos(19, 3, 19));
        VillageRecord record = registry.getOrCreateVillage(UUID.randomUUID(), helper.absolutePos(SAWMILL),
                new AABB(min.getX(), min.getY(), min.getZ(),
                        max.getX() + 1.0D, max.getY() + 1.0D, max.getZ() + 1.0D));
        BlockPos footMin = helper.absolutePos(new BlockPos(10, 0, 0));
        BlockPos footMax = helper.absolutePos(new BlockPos(19, 3, 8));
        registry.addLumbermillFootprint(new BoundingBox(footMin.getX(), footMin.getY(), footMin.getZ(),
                footMax.getX(), footMax.getY(), footMax.getZ()));
        return new Village(record);
    }

    private static BlockPos placeFurnace(GameTestHelper helper, Village village, BlockPos local) {
        helper.setBlock(local, Blocks.FURNACE.defaultBlockState());
        BlockPos furnace = helper.absolutePos(local);
        village.record().acceptFurnaceSighting(helper.getLevel(), furnace);
        return furnace;
    }

    private static VillagerPOIRecord member(Villager villager, ServerLevel level) {
        return new VillagerPOIRecord(villager.getUUID(), villager.getName().getString(),
                ECAPVillagerProfessions.LUMBERJACK.get().name(), null, null, null, villager.getHealth(),
                VillagerPOIRecord.Status.ACTIVE, 0, level.getGameTime());
    }

    private static boolean running(Villager villager) {
        return villager.getBrain().getRunningBehaviors().stream()
                .anyMatch(behavior -> behavior instanceof LumberjackWorkBehavior);
    }

    private static boolean ownsWalkTarget(Villager villager) {
        return "emeraldcapitalism:lumberjack_work".equals(BrainMovementIntent.ownerOf(
                villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null)));
    }

    /** Ticks the Brain once per game tick until {@code condition} holds, then runs {@code next}. */
    private static void driveUntil(GameTestHelper helper, Villager villager, int remaining,
                                   BooleanSupplier condition, String failure, Runnable next) {
        villager.getBrain().tick(helper.getLevel(), villager);
        if (condition.getAsBoolean()) {
            next.run();
            return;
        }
        if (remaining <= 0) {
            helper.fail(failure);
            return;
        }
        helper.runAfterDelay(1, () -> driveUntil(helper, villager, remaining - 1, condition, failure, next));
    }

    /**
     * Resumes work after an interruption. The Brain leaves a behavior of the old activity running and
     * it keeps writing a walk target that nothing consumes while the AI is off. Work can only start
     * while no walk target exists, and the start attempt comes before those writes, so the target is
     * cleared before each Brain tick.
     */
    private static void driveResuming(GameTestHelper helper, Villager villager, int remaining,
                                      String failure, Runnable next) {
        if (!ownsWalkTarget(villager)) {
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
        villager.getBrain().tick(helper.getLevel(), villager);
        if (running(villager)) {
            next.run();
            return;
        }
        if (remaining <= 0) {
            helper.fail(failure);
            return;
        }
        helper.runAfterDelay(1, () -> driveResuming(helper, villager, remaining - 1, failure, next));
    }

    /** Ticks the Brain for {@code ticks} game ticks; a non-null violation fails the test. */
    private static void driveFor(GameTestHelper helper, Villager villager, int ticks,
                                 Supplier<String> violation, Runnable next) {
        villager.getBrain().tick(helper.getLevel(), villager);
        String broken = violation.get();
        if (broken != null) {
            helper.fail(broken);
            return;
        }
        if (ticks <= 1) {
            next.run();
            return;
        }
        helper.runAfterDelay(1, () -> driveFor(helper, villager, ticks - 1, violation, next));
    }

    private static AABB box(GameTestHelper helper, int minX, int maxX) {
        BlockPos min = helper.absolutePos(new BlockPos(minX, 0, 0));
        BlockPos max = helper.absolutePos(new BlockPos(maxX, 3, 19));
        return new AABB(min.getX(), min.getY(), min.getZ(),
                max.getX() + 1.0D, max.getY() + 1.0D, max.getZ() + 1.0D);
    }

    private static void footprint(GameTestHelper helper, VillageRegistryData registry, int minX, int maxX) {
        BlockPos min = helper.absolutePos(new BlockPos(minX, 0, 0));
        BlockPos max = helper.absolutePos(new BlockPos(maxX, 3, 8));
        registry.addLumbermillFootprint(new BoundingBox(min.getX(), min.getY(), min.getZ(),
                max.getX(), max.getY(), max.getZ()));
    }

    private static int count(Villager villager, Item item) {
        int count = 0;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int count(List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> entries, Class<?> type) {
        return (int) entries.stream().filter(entry -> entry.getSecond().getClass() == type).count();
    }

    private static int countAtPriority(List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> entries,
                                       int priority) {
        return (int) entries.stream().filter(entry -> entry.getFirst() == priority).count();
    }

    private static int countNamed(List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> entries,
                                  String simpleName) {
        return (int) entries.stream()
                .filter(entry -> entry.getSecond().getClass().getSimpleName().equals(simpleName)).count();
    }
}
