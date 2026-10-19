package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.entity.ai.HarvestQueuedCropGoal;
import com.orangevillager61.emeraldcapitalism.event.VillageRegistryEvents;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageWorkCell;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Integration and regression coverage for village-scoped farmer work. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class FarmerWorkPhase8GameTests {

    private FarmerWorkPhase8GameTests() {
    }

    @GameTest(template = "empty_20x3x20")
    public static void matureCropGrowthIndexesAllPositionsIdempotentlyAndIgnoresOutsideVillage(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos villageCenter = helper.absolutePos(new BlockPos(2, 1, 2));
        VillageRecord village = createTrackedVillage(level, villageCenter,
                helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(8, 3, 8)));

        BlockPos first = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos second = helper.absolutePos(new BlockPos(3, 2, 2));
        BlockPos outside = helper.absolutePos(new BlockPos(12, 2, 2));
        placeMatureWheat(helper, first);
        placeMatureWheat(helper, second);
        placeMatureWheat(helper, outside);

        BlockState previous = Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE - 1);
        BlockState mature = Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE);
        VillageRegistryEvents.onCropGrowth(new CropGrowEvent.Post(level, first, previous, mature));
        VillageRegistryEvents.onCropGrowth(new CropGrowEvent.Post(level, second, previous, mature));
        VillageRegistryEvents.onCropGrowth(new CropGrowEvent.Post(level, first, previous, mature));
        VillageRegistryEvents.onCropGrowth(new CropGrowEvent.Post(level, outside, previous, mature));

        helper.assertValueEqual(village.getMatureCropWorkQueue().size(), 2,
                "two mature crops should be indexed exactly once");
        helper.assertTrue(village.getMatureCropWorkQueue().contains(first),
                "first mature crop was not indexed");
        helper.assertTrue(village.getMatureCropWorkQueue().contains(second),
                "second mature crop was not indexed");
        helper.assertFalse(village.getMatureCropWorkQueue().contains(outside),
                "crop growth outside a village was indexed");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void cropGrowthPostUsesTheNewMatureState(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos villageCenter = helper.absolutePos(new BlockPos(2, 1, 2));
        VillageRecord village = createTrackedVillage(level, villageCenter,
                helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(8, 3, 8)));
        BlockPos crop = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState(), 3);
        BlockState previous = Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE - 1);
        BlockState mature = Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE);
        level.setBlock(crop, previous, 3);

        VillageRegistryEvents.onCropGrowth(new CropGrowEvent.Post(level, crop, previous, mature));

        helper.assertTrue(village.getMatureCropWorkQueue().contains(crop),
                "a growth post with a mature new state was not queued");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_queued_crop_harvest_autonomous")
    public static void newlyGrownCropEventEnqueuesMatureCrop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos crop = helper.absolutePos(new BlockPos(4, 2, 4));
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.FARMLAND.defaultBlockState());
        BlockState previous = Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE - 1);
        BlockState mature = Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE);
        helper.setBlock(new BlockPos(4, 2, 4), mature);
        VillageRecord village = createTrackedVillage(level,
                helper.absolutePos(new BlockPos(2, 1, 2)),
                helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(8, 3, 8)));

        VillageRegistryEvents.onCropGrowth(new CropGrowEvent.Post(level, crop, previous, mature));
        helper.assertTrue(village.getMatureCropWorkQueue().contains(crop),
                "the newly grown mature crop was not queued from CropGrowEvent.Post");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_queued_crop_harvest_autonomous",
            timeoutTicks = 700)
    public static void realCropGrowthReachesFarmerQueueAndHarvest(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        installFloor(helper);
        BlockPos localCrop = new BlockPos(10, 2, 2);
        BlockPos crop = helper.absolutePos(localCrop);
        helper.setBlock(localCrop.below(), Blocks.FARMLAND.defaultBlockState());
        BlockState previous = Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE - 1);
        BlockState mature = Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE);
        helper.setBlock(localCrop, mature);
        VillageRecord village = createTrackedVillage(level,
                helper.absolutePos(new BlockPos(2, 1, 2)),
                helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(19, 3, 19)));
        VillageRegistryEvents.onCropGrowth(new CropGrowEvent.Post(level, crop, previous, mature));
        helper.assertTrue(village.getMatureCropWorkQueue().contains(crop),
                "newly grown crop did not reach the farmer queue");

        Villager farmer = helper.spawn(EntityType.VILLAGER, 2, 1, 2);
        farmer.refreshBrain(level);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        farmer.setVillagerXp(1);
        farmer.setOnGround(true);
        farmer.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
        farmer.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        farmer.getBrain().eraseMemory(MemoryModuleType.PATH);
        village.addMember(new VillagerPOIRecord(
                farmer.getUUID(), farmer.getName().getString(), "FARMER",
                null, null, null, farmer.getHealth(), VillagerPOIRecord.Status.ACTIVE,
                0, level.getGameTime()));
        helper.assertTrue(village.refreshFarmerRoster(farmer),
                "real-growth farmer was not registered with the village roster");

        helper.runAfterDelay(1, () -> awaitRealCropHarvest(
                helper, farmer, village, crop, 680));
    }

    private static void awaitRealCropHarvest(GameTestHelper helper, Villager farmer,
                                              VillageRecord village, BlockPos crop,
                                              int remainingTicks) {
        ServerLevel level = helper.getLevel();
        if (level.getBlockState(crop).isAir()
                && !village.getMatureCropWorkQueue().contains(crop)) {
            helper.assertTrue(farmer.blockPosition().distSqr(crop) <= 9.0D,
                    "real-growth crop was harvested without the farmer reaching it: "
                            + farmer.blockPosition() + " crop=" + crop);
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("real crop growth did not reach farmer harvest: pos=" + farmer.position()
                    + ", crop=" + crop + ", block=" + level.getBlockState(crop)
                    + ", queue=" + village.getMatureCropWorkQueue()
                    + ", lease=" + village.getWorkCellLeasedTo(farmer.getUUID())
                    + ", walkTarget=" + farmer.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                    .orElse(null));
            return;
        }
        helper.runAfterDelay(1, () -> awaitRealCropHarvest(
                helper, farmer, village, crop, remainingTicks - 1));
    }

    @GameTest(template = "empty_20x3x20")
    public static void fullScanIndexesAlreadyMatureCropsForQueuedWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos villageCenter = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos crop = helper.absolutePos(new BlockPos(5, 2, 5));
        placeMatureWheat(helper, crop);
        VillageRecord village = createTrackedVillage(level, villageCenter,
                helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(8, 3, 8)));

        village.fullScan(level);

        helper.assertTrue(village.getFarmlandRegistry().contains(crop.below()),
                "full scan did not retain the crop's farmland position");
        helper.assertTrue(village.getMatureCropWorkQueue().contains(crop),
                "full scan did not queue an already-mature crop");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void staleMatureCropEntriesAreRemovedAfterHarvestReplacementAndReload(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos villageCenter = helper.absolutePos(new BlockPos(2, 1, 2));
        VillageRecord village = createTrackedVillage(level, villageCenter,
                helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(8, 3, 8)));

        BlockPos harvested = helper.absolutePos(new BlockPos(2, 2, 2));
        placeMatureWheat(helper, harvested);
        helper.assertTrue(village.addMatureCropWork(harvested, level.getGameTime()),
                "mature crop should enter the queue");
        helper.assertTrue(village.removeMatureCropWork(harvested),
                "successful harvest should remove the queued crop");
        helper.assertFalse(village.getMatureCropWorkQueue().contains(harvested),
                "harvested crop remained in the queue");

        BlockPos replaced = helper.absolutePos(new BlockPos(3, 2, 2));
        placeMatureWheat(helper, replaced);
        helper.assertTrue(village.addMatureCropWork(replaced, level.getGameTime()),
                "replacement test crop should enter the queue");
        level.setBlock(replaced, Blocks.STONE.defaultBlockState(), 3);
        helper.assertTrue(level.getBlockState(replaced).is(Blocks.STONE),
                "replacement fixture was not written at the absolute crop position");
        helper.assertFalse(village.getMatureCropWorkQueue().contains(replaced),
                "replacement should invalidate the stale crop entry");

        BlockPos persisted = helper.absolutePos(new BlockPos(4, 2, 2));
        placeMatureWheat(helper, persisted);
        helper.assertTrue(village.addMatureCropWork(persisted, level.getGameTime()),
                "reload test crop should enter the queue");
        VillageRecord loaded = VillageRecord.load(village.save(new CompoundTag()));
        helper.assertTrue(loaded.getMatureCropWorkQueue().contains(persisted),
                "valid mature crop queue entry did not survive reload");
        helper.assertTrue(loaded.getClaimedMatureCropPositions().isEmpty(),
                "crop claims must not survive reload");

        level.setBlock(persisted, Blocks.AIR.defaultBlockState(), 3);
        helper.assertTrue(loaded.validateMatureCropWork(level, persisted),
                "broken crop should invalidate the reloaded queue entry");
        helper.assertFalse(loaded.getMatureCropWorkQueue().contains(persisted),
                "broken crop remained in the reloaded queue");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void cropClaimsReleaseForUnloadProfessionChangeAndPathFailure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos villageCenter = helper.absolutePos(new BlockPos(2, 1, 2));
        VillageRecord village = createTrackedVillage(level, villageCenter,
                helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(8, 3, 8)));
        BlockPos crop = helper.absolutePos(new BlockPos(4, 2, 2));
        placeMatureWheat(helper, crop);
        village.addMatureCropWork(crop, level.getGameTime());

        Villager unloadedFarmer = spawnRegisteredFarmer(helper, village, new BlockPos(2, 1, 2));
        long firstTick = level.getGameTime();
        helper.assertValueEqual(village.dispatchFarmerWork(level, firstTick), 1,
                "queued crop should be leased to the loaded farmer");
        helper.assertTrue(village.claimMatureCropPosition(unloadedFarmer.getUUID(), crop),
                "leased farmer could not claim its crop");
        helper.assertTrue(village.getClaimedMatureCropPositions().contains(crop),
                "crop claim was not recorded");

        unloadedFarmer.discard();
        village.removeFarmerFromRoster(unloadedFarmer.getUUID());
        helper.assertTrue(village.getClaimedMatureCropPositions().isEmpty(),
                "unloaded farmer left a crop claim behind");
        helper.assertTrue(village.getWorkCellLeasedTo(unloadedFarmer.getUUID()) == null,
                "unloaded farmer left a work lease behind");

        Villager changedProfession = spawnRegisteredFarmer(helper, village, new BlockPos(2, 1, 2));
        helper.assertValueEqual(village.dispatchFarmerWork(level, firstTick + 1), 1,
                "replacement farmer was not assigned after unload");
        helper.assertTrue(village.claimMatureCropPosition(changedProfession.getUUID(), crop),
                "replacement farmer could not claim the crop");
        changedProfession.setVillagerData(changedProfession.getVillagerData()
                .setProfession(VillagerProfession.LIBRARIAN));
        village.refreshFarmerRoster(changedProfession);
        helper.assertTrue(village.getClaimedMatureCropPositions().isEmpty(),
                "profession change left a crop claim behind");
        helper.assertTrue(village.getWorkCellLeasedTo(changedProfession.getUUID()) == null,
                "profession change left a work lease behind");

        changedProfession.setVillagerData(changedProfession.getVillagerData()
                .setProfession(VillagerProfession.FARMER));
        village.refreshFarmerRoster(changedProfession);
        long failureTick = firstTick + 2;
        helper.assertValueEqual(village.dispatchFarmerWork(level, failureTick), 1,
                "farmer was not reassigned after profession recovery");
        helper.assertTrue(village.claimMatureCropPosition(changedProfession.getUUID(), crop),
                "farmer could not reclaim the crop before path failure");
        helper.assertTrue(village.reportWorkLeaseFailure(changedProfession.getUUID(), crop, failureTick),
                "path failure did not release the work lease");
        VillageWorkCell cell = village.getWorkCell(crop);
        helper.assertTrue(cell != null && cell.getActiveLease() == null,
                "path failure left an active lease");
        helper.assertTrue(village.getClaimedMatureCropPositions().isEmpty(),
                "path failure left a crop claim");
        helper.assertTrue(cell != null && cell.getLeaseRetryAfter() > failureTick,
                "path failure did not apply a retry delay");
        helper.assertValueEqual(village.dispatchFarmerWork(level, failureTick + 1), 0,
                "failed cell was reassigned before its retry delay");
        helper.assertValueEqual(village.dispatchFarmerWork(level, cell.getLeaseRetryAfter()), 1,
                "failed cell was not reassigned after its retry delay");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void pendingCellsPreferNearbyFarmersAndSeparateAssignments(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VillageRecord village = createTrackedVillage(level,
                helper.absolutePos(new BlockPos(2, 1, 2)),
                helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(20, 3, 8)));
        BlockPos firstCellWork = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos firstCellExtra = helper.absolutePos(new BlockPos(3, 1, 2));
        BlockPos secondCellWork = helper.absolutePos(new BlockPos(19, 1, 2));
        addRepairPosition(village, firstCellWork);
        addRepairPosition(village, firstCellExtra);
        addRepairPosition(village, secondCellWork);

        Villager firstFarmer = spawnRegisteredFarmer(helper, village, new BlockPos(2, 1, 2));
        Villager secondFarmer = spawnRegisteredFarmer(helper, village, new BlockPos(18, 1, 2));
        helper.assertValueEqual(village.dispatchFarmerWork(level, level.getGameTime()), 2,
                "two pending cells should receive two available farmers");
        VillageWorkCell firstCell = village.getWorkCell(firstCellWork);
        VillageWorkCell secondCell = village.getWorkCell(secondCellWork);
        helper.assertTrue(firstCell != null && firstCell.getActiveLease() != null
                        && firstCell.getActiveLease().farmerId().equals(firstFarmer.getUUID()),
                "the nearest farmer did not receive the higher-pending cell");
        helper.assertTrue(secondCell != null && secondCell.getActiveLease() != null
                        && secondCell.getActiveLease().farmerId().equals(secondFarmer.getUUID()),
                "the second cell was not assigned to its nearest available farmer");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void oneFarmerReceivesCellsSequentiallyAsWorkCompletes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VillageRecord village = createTrackedVillage(level,
                helper.absolutePos(new BlockPos(2, 1, 2)),
                helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(20, 3, 8)));
        BlockPos first = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos firstExtra = helper.absolutePos(new BlockPos(3, 1, 2));
        BlockPos second = helper.absolutePos(new BlockPos(19, 1, 2));
        addRepairPosition(village, first);
        addRepairPosition(village, firstExtra);
        addRepairPosition(village, second);
        Villager farmer = spawnRegisteredFarmer(helper, village, new BlockPos(2, 1, 2));

        long gameTime = level.getGameTime();
        helper.assertValueEqual(village.dispatchFarmerWork(level, gameTime), 1,
                "the farmer should receive the cell with the most pending work");
        VillageWorkCell firstCell = village.getWorkCell(first);
        helper.assertTrue(firstCell != null && firstCell.getActiveLease() != null,
                "first work cell was not leased");
        helper.assertTrue(firstCell.getActiveLease().farmerId().equals(farmer.getUUID()),
                "first cell was not assigned to the available farmer");

        village.removeFromRepairQueue(first);
        village.removeFromRepairQueue(firstExtra);
        village.releaseWorkLease(farmer.getUUID(), first);
        helper.assertValueEqual(village.dispatchFarmerWork(level, gameTime + 1), 1,
                "the same farmer should receive the next cell after completion");
        VillageWorkCell secondCell = village.getWorkCell(second);
        helper.assertTrue(secondCell != null && secondCell.getActiveLease() != null
                        && secondCell.getActiveLease().farmerId().equals(farmer.getUUID()),
                "the next pending cell was not assigned sequentially");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void queuedCropHarvestWithoutSeedsDoesNotReplant(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        installFloor(helper);
        BlockPos crop = helper.absolutePos(new BlockPos(3, 2, 2));
        placeMatureWheat(helper, crop);
        VillageRecord village = createTrackedVillage(level,
                helper.absolutePos(new BlockPos(2, 1, 2)),
                helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(8, 3, 8)));
        village.addMatureCropWork(crop, level.getGameTime());
        Villager farmer = spawnRegisteredFarmer(helper, village, new BlockPos(2, 1, 2));
        HarvestQueuedCropGoal goal = new HarvestQueuedCropGoal(farmer);

        helper.assertTrue(goal.canUse(), "farmer could not select queued mature crop");
        goal.start();
        helper.assertTrue(goal.getTargetPos() != null,
                "queued crop goal lost its target while starting navigation");
        // Simulate the movement request completing before the goal's next tick.
        // The arrival path must still be allowed to execute the harvest.
        farmer.setPos(crop.getX() + 0.5D, crop.getY() - 1.0D, crop.getZ() + 0.5D);
        helper.assertTrue(goal.canContinueToUse(),
                "queued crop goal stopped when the farmer reached its standing position");
        goal.tick();
        helper.assertTrue(level.getBlockState(crop).isAir(),
                "queued crop was not harvested");
        helper.assertFalse(village.getMatureCropWorkQueue().contains(crop),
                "successful queued harvest left a stale queue entry");
        helper.assertTrue(level.getBlockState(crop).isAir(),
                "farmer without suitable seeds unexpectedly replanted the crop");
        goal.stop();
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_queued_crop_harvest_journey",
            timeoutTicks = 500)
    public static void queuedMatureCropUsesRealFarmerJourney(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        installFloor(helper);
        BlockPos crop = helper.absolutePos(new BlockPos(10, 2, 2));
        placeMatureWheat(helper, crop);
        VillageRecord village = createTrackedVillage(level,
                helper.absolutePos(new BlockPos(2, 1, 2)),
                helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(19, 3, 19)));
        village.addMatureCropWork(crop, level.getGameTime());

        Villager farmer = helper.spawn(EntityType.VILLAGER, 2, 1, 2);
        farmer.refreshBrain(level);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        farmer.setVillagerXp(1);
        farmer.setOnGround(true);
        farmer.goalSelector.removeAllGoals(ignored -> true);
        farmer.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
        farmer.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        farmer.getBrain().eraseMemory(MemoryModuleType.PATH);
        village.addMember(new VillagerPOIRecord(
                farmer.getUUID(), farmer.getName().getString(), "FARMER",
                null, null, null, farmer.getHealth(), VillagerPOIRecord.Status.ACTIVE,
                0, level.getGameTime()));
        helper.assertTrue(village.refreshFarmerRoster(farmer),
                "farmer was not registered with the village roster");

        BlockPos start = farmer.blockPosition();
        HarvestQueuedCropGoal goal = new HarvestQueuedCropGoal(farmer);
        helper.assertTrue(goal.canUse(), "farmer could not select the queued mature crop");
        // Start the custom work goal directly so this regression isolates its
        // real navigation/harvest journey from unrelated vanilla activity
        // arbitration. MoveToTargetSink still executes the actual route.
        goal.start();
        helper.runAfterDelay(1,
                () -> awaitQueuedCropJourney(helper, farmer, goal, start, crop, village, 450));
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_queued_crop_harvest_autonomous",
            timeoutTicks = 700)
    public static void normalVillagerAiHarvestsQueuedCropAtAbsolutePosition(GameTestHelper helper) {
        installFloor(helper);
        BlockPos localCrop = new BlockPos(10, 2, 2);
        BlockPos crop = helper.absolutePos(localCrop);
        helper.setBlock(localCrop.below(), Blocks.FARMLAND.defaultBlockState());
        BlockState previousCrop = Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE - 1);
        BlockState matureCrop = Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE);
        helper.setBlock(localCrop, matureCrop);
        BlockPos localComposter = new BlockPos(2, 1, 2);
        BlockPos composter = helper.absolutePos(localComposter);
        helper.setBlock(localComposter, Blocks.COMPOSTER.defaultBlockState());
        VillageRecord village = createTrackedVillage(helper.getLevel(),
                helper.absolutePos(new BlockPos(2, 1, 2)),
                helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(19, 3, 19)));
        VillageRegistryEvents.onCropGrowth(new CropGrowEvent.Post(
                helper.getLevel(), crop, previousCrop, matureCrop));
        helper.assertTrue(village.getMatureCropWorkQueue().contains(crop),
                "growth event did not enqueue the newly mature crop");

        Villager farmer = helper.spawn(EntityType.VILLAGER, 3, 1, 2);
        helper.getLevel().setDayTime(1000L);
        farmer.refreshBrain(helper.getLevel());
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        farmer.setVillagerXp(1);
        farmer.setOnGround(true);
        farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(helper.getLevel().dimension(), composter));
        // Vanilla GoToJobSite can leave this target in memory after the farmer
        // has already arrived. It must not make custom village work starve.
        farmer.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(new BlockPosTracker(composter), 0.5F, 1));
        helper.assertTrue(farmer.goalSelector.getAvailableGoals().stream()
                        .anyMatch(wrapper -> wrapper.getGoal() instanceof HarvestQueuedCropGoal),
                "normal villager registration did not install the queued crop goal");
        village.addMember(new VillagerPOIRecord(
                farmer.getUUID(), farmer.getName().getString(), "FARMER",
                null, null, null, farmer.getHealth(), VillagerPOIRecord.Status.ACTIVE,
                0, helper.getLevel().getGameTime()));
        helper.assertTrue(village.refreshFarmerRoster(farmer),
                "farmer was not registered with the village roster");

        helper.runAfterDelay(1, () -> awaitAutonomousQueuedCropHarvest(
                helper, farmer, crop, village, 680));
    }

    private static void awaitAutonomousQueuedCropHarvest(GameTestHelper helper, Villager farmer,
                                                           BlockPos crop, VillageRecord village,
                                                           int remainingTicks) {
        if (helper.getLevel().getBlockState(crop).isAir()
                && !village.getMatureCropWorkQueue().contains(crop)) {
            helper.assertTrue(farmer.blockPosition().distSqr(crop) <= 9.0D,
                    "queued crop was harvested without the autonomous farmer reaching it: "
                            + farmer.blockPosition() + " crop=" + crop);
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("normal villager AI did not harvest queued crop: pos=" + farmer.position()
                    + ", crop=" + crop + ", block=" + helper.getLevel().getBlockState(crop)
                    + ", queue=" + village.getMatureCropWorkQueue()
                    + ", lease=" + village.getWorkCellLeasedTo(farmer.getUUID())
                    + ", walkTarget=" + farmer.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null));
            return;
        }
        helper.runAfterDelay(1, () -> awaitAutonomousQueuedCropHarvest(
                helper, farmer, crop, village, remainingTicks - 1));
    }

    private static void awaitQueuedCropJourney(GameTestHelper helper, Villager farmer,
                                                HarvestQueuedCropGoal goal, BlockPos start, BlockPos crop,
                                                VillageRecord village, int remainingTicks) {
        if (helper.getLevel().getBlockState(crop).isAir()
                && !village.getMatureCropWorkQueue().contains(crop)) {
            helper.assertTrue(farmer.blockPosition().distSqr(start) > 9.0D,
                    "queued crop was harvested without a real farmer journey: "
                            + farmer.blockPosition() + " start=" + start);
            goal.stop();
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail("farmer did not reach queued mature crop: pos="
                    + farmer.blockPosition() + ", target=" + crop + ", block="
                    + helper.getLevel().getBlockState(crop) + ", active="
                    + com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager
                    .activeHandle(farmer) + ", walkTarget="
                    + farmer.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null));
            return;
        }
        helper.assertTrue(goal.canContinueToUse(),
                "queued crop goal stopped before reaching its target: pos="
                        + farmer.blockPosition() + ", profession="
                        + farmer.getVillagerData().getProfession());
        goal.tick();
        helper.runAfterDelay(1, () -> awaitQueuedCropJourney(
                helper, farmer, goal, start, crop, village, remainingTicks - 1));
    }

    @GameTest(template = "empty_3x3x3")
    public static void farmerDispatchDoesNotPerformRepeatedFullVillageScans(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VillageRecord village = createTrackedVillage(level,
                helper.absolutePos(new BlockPos(1, 1, 1)),
                helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(2, 2, 2)));
        addRepairPosition(village, helper.absolutePos(new BlockPos(1, 1, 1)));
        Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before =
                PerformanceTimingCounters.snapshot();
        for (int i = 0; i < 20; i++) {
            village.dispatchFarmerWork(level, level.getGameTime() + i);
        }
        Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> after =
                PerformanceTimingCounters.snapshot();
        helper.assertValueEqual(after.get(PerformanceTimingCounters.Operation.VILLAGE_FULL_SCAN).calls(),
                before.get(PerformanceTimingCounters.Operation.VILLAGE_FULL_SCAN).calls(),
                "farmer dispatch performed a full-village scan");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void vanillaFarmlandBehaviorIsNotOverriddenByHarvestMixin(GameTestHelper helper) {
        try (InputStream stream = FarmerWorkPhase8GameTests.class.getClassLoader()
                .getResourceAsStream("emeraldcapitalism.mixins.json")) {
            helper.assertTrue(stream != null, "mixin configuration was not packaged");
            if (stream == null) {
                return;
            }
            String mixins = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            helper.assertFalse(mixins.contains("HarvestFarmlandMixin"),
                    "HarvestFarmlandMixin must not override vanilla farmer behavior");
        } catch (IOException exception) {
            helper.fail("could not read mixin configuration: " + exception.getMessage());
            return;
        }
        helper.succeed();
    }

    private static VillageRecord createTrackedVillage(ServerLevel level, BlockPos center,
                                                       BlockPos min, BlockPos max) {
        return VillageRegistryData.get(level).getOrCreateVillage(
                UUID.randomUUID(), center,
                new AABB(min.getX(), min.getY(), min.getZ(),
                        max.getX() + 1.0D, max.getY() + 1.0D, max.getZ() + 1.0D));
    }

    private static void addRepairPosition(VillageRecord village, BlockPos position) {
        village.addFarmland(position);
        village.addToRepairQueue(position);
    }

    private static void placeMatureWheat(GameTestHelper helper, BlockPos crop) {
        helper.getLevel().setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState(), 3);
        helper.getLevel().setBlock(crop, Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE), 3);
    }

    private static void installFloor(GameTestHelper helper) {
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static Villager spawnRegisteredFarmer(GameTestHelper helper, VillageRecord village,
                                                   BlockPos position) {
        ServerLevel level = helper.getLevel();
        Villager farmer = helper.spawnWithNoFreeWill(EntityType.VILLAGER, position);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        if (!village.hasMember(farmer.getUUID())) {
            village.addMember(new VillagerPOIRecord(
                    farmer.getUUID(), farmer.getName().getString(), "FARMER",
                    null, null, null, farmer.getHealth(), VillagerPOIRecord.Status.ACTIVE,
                    0, level.getGameTime()));
        }
        helper.assertTrue(village.refreshFarmerRoster(farmer),
                "farmer was not added to the village roster");
        return farmer;
    }
}
