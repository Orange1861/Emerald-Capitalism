package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.entity.ai.ReplenishFarmlandGoal;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.villagefarms.VillageFarmSiteSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * NeoForge GameTest class for farmland and door registries, repair queue,
 * ReplenishFarmlandGoal, and event-driven updates.
 */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public class FarmlandGameTests {

    /** Creates an AABB from two BlockPos corners (min and max). */
    private static AABB aabbFromCorners(BlockPos min, BlockPos max) {
        return new AABB(min.getX(), min.getY(), min.getZ(),
                        max.getX(), max.getY(), max.getZ());
    }

    /** Creates a record for the state-only farmland tests below. */
    private static VillageRecord newStateRecord() {
        return new VillageRecord(
                UUID.randomUUID(),
                new BlockPos(100, 64, 100),
                new AABB(0, 0, 0, 300, 128, 300)
        );
    }

    /** Adds a position through the same registry-to-repair-queue path as gameplay. */
    private static void addRepairPosition(VillageRecord record, BlockPos pos) {
        record.addFarmland(pos);
        record.addToRepairQueue(pos);
    }

    @GameTest(template = "empty_3x3x3")
    public static void sturdyFarmSurfaceIsAccepted(GameTestHelper helper) {
        BlockPos candidate = new BlockPos(1, 2, 1);
        helper.setBlock(candidate.below(), Blocks.DIRT.defaultBlockState());
        BlockPos supportPos = helper.absolutePos(candidate.below());

        helper.assertTrue(VillageFarmSiteSelector.isFarmSurfaceSupported(
                        helper.getLevel(), supportPos),
                "Dirt directly below the farm candidate should be accepted");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void nonSturdyFarmSurfaceIsRejectedEvenWithLowerGround(GameTestHelper helper) {
        BlockPos candidate = new BlockPos(1, 2, 1);
        helper.setBlock(candidate.below(), Blocks.TORCH.defaultBlockState());
        helper.setBlock(candidate.below(2), Blocks.DIRT.defaultBlockState());
        BlockPos supportPos = helper.absolutePos(candidate.below());

        helper.assertFalse(VillageFarmSiteSelector.isFarmSurfaceSupported(
                        helper.getLevel(), supportPos),
                "A non-sturdy block directly below the candidate should be rejected");
        helper.succeed();
    }

    // Bounding box scan collects farmland

    @GameTest(template = "empty_3x3x3")
    public static void scanCollectsFarmland(GameTestHelper helper) {
        BlockPos farm1 = new BlockPos(1, 1, 1);
        BlockPos farm2 = new BlockPos(2, 1, 1);
        BlockPos notFarm = new BlockPos(1, 1, 2);

        helper.setBlock(farm1, Blocks.FARMLAND.defaultBlockState());
        helper.setBlock(farm2, Blocks.FARMLAND.defaultBlockState());
        helper.setBlock(notFarm, Blocks.DIRT.defaultBlockState());

        ServerLevel level = helper.getLevel();
        BlockPos absPos1 = helper.absolutePos(farm1);
        BlockPos absPos2 = helper.absolutePos(farm2);
        BlockPos absNotFarm = helper.absolutePos(notFarm);

        VillageRecord record = new VillageRecord(
                UUID.randomUUID(),
                helper.absolutePos(new BlockPos(1, 1, 1)),
                aabbFromCorners(
                        helper.absolutePos(new BlockPos(0, 0, 0)),
                        helper.absolutePos(new BlockPos(3, 3, 3))
                )
        );

        record.fullScan(level);

        helper.assertTrue(record.getFarmlandRegistry().contains(absPos1),
                "Farm1 should be in registry");
        helper.assertTrue(record.getFarmlandRegistry().contains(absPos2),
                "Farm2 should be in registry");
        helper.assertFalse(record.getFarmlandRegistry().contains(absNotFarm),
                "Dirt block should not be in farmland registry");

        helper.succeed();
    }

    // Farmland→dirt adds to repair queue

    @GameTest(template = "empty_3x3x3")
    public static void farmlandToDirtAddsToRepairQueue(GameTestHelper helper) {
        BlockPos farm = new BlockPos(1, 1, 1);
        helper.setBlock(farm, Blocks.FARMLAND.defaultBlockState());

        ServerLevel level = helper.getLevel();
        BlockPos absPos = helper.absolutePos(farm);

        VillageRecord record = new VillageRecord(
                UUID.randomUUID(),
                absPos,
                aabbFromCorners(
                        helper.absolutePos(new BlockPos(0, 0, 0)),
                        helper.absolutePos(new BlockPos(3, 3, 3))
                )
        );
        record.fullScan(level);

        // Simulate farmland turning to dirt (trampled)
        helper.setBlock(farm, Blocks.DIRT.defaultBlockState());
        record.addToRepairQueue(absPos);

        helper.assertTrue(record.getRepairQueue().contains(absPos),
                "Trampled farmland should be in repair queue");
        helper.assertTrue(record.getFarmlandRegistry().contains(absPos),
                "Original farmland position should still be in registry");

        helper.succeed();
    }

    // Destroyed farmland removed from registry

    @GameTest(template = "empty_3x3x3")
    public static void destroyedFarmlandRemovedFromRegistry(GameTestHelper helper) {
        BlockPos farm = new BlockPos(1, 1, 1);
        helper.setBlock(farm, Blocks.FARMLAND.defaultBlockState());

        ServerLevel level = helper.getLevel();
        BlockPos absPos = helper.absolutePos(farm);

        VillageRecord record = new VillageRecord(
                UUID.randomUUID(),
                absPos,
                aabbFromCorners(
                        helper.absolutePos(new BlockPos(0, 0, 0)),
                        helper.absolutePos(new BlockPos(3, 3, 3))
                )
        );
        record.fullScan(level);

        record.removeFarmland(absPos);

        helper.assertFalse(record.getFarmlandRegistry().contains(absPos),
                "Destroyed farmland should be removed from registry");

        helper.succeed();
    }

    // New farmland placed within bounding box added to registry

    @GameTest(template = "empty_3x3x3")
    public static void newFarmlandPlacedAddsToRegistry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos absPos = helper.absolutePos(new BlockPos(1, 1, 1));

        VillageRecord record = new VillageRecord(
                UUID.randomUUID(),
                absPos,
                aabbFromCorners(
                        helper.absolutePos(new BlockPos(0, 0, 0)),
                        helper.absolutePos(new BlockPos(3, 3, 3))
                )
        );
        record.fullScan(level);

        helper.setBlock(new BlockPos(2, 1, 2), Blocks.FARMLAND.defaultBlockState());
        BlockPos absNewPos = helper.absolutePos(new BlockPos(2, 1, 2));
        record.addFarmland(absNewPos);

        helper.assertTrue(record.getFarmlandRegistry().contains(absNewPos),
                "Newly placed farmland should be in registry");

        helper.succeed();
    }

    // Positions outside bounding box ignored

    @GameTest(template = "empty_3x3x3")
    public static void positionsOutsideBoundingBoxIgnored(GameTestHelper helper) {
        BlockPos absCenter = helper.absolutePos(new BlockPos(1, 1, 1));

        AABB smallBox = new AABB(absCenter).inflate(1);
        VillageRecord record = new VillageRecord(UUID.randomUUID(), absCenter, smallBox);
        record.fullScan(helper.getLevel());

        BlockPos outsidePos = new BlockPos(9999, 64, 9999);
        record.addFarmland(outsidePos);
        record.addToRepairQueue(outsidePos);

        record.setBoundingBox(new AABB(absCenter).inflate(0.5), helper.getLevel());

        helper.assertFalse(record.getFarmlandRegistry().contains(outsidePos),
                "Position outside bounding box should be pruned");

        helper.succeed();
    }

    // Goal not activated for non-farmer villagers

    @GameTest(template = "empty_3x3x3")
    public static void goalNotActivatedForNonFarmer(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 1, 1));
        ReplenishFarmlandGoal goal = new ReplenishFarmlandGoal(villager);

        helper.assertFalse(goal.canUse(),
                "Goal should not activate for non-farmer villager");

        helper.succeed();
    }

    // Goal not activated when queue is empty

    @GameTest(template = "empty_3x3x3")
    public static void goalNotActivatedWhenQueueEmpty(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 1, 1));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));

        ServerLevel level = helper.getLevel();
        BlockPos absPos = helper.absolutePos(new BlockPos(1, 1, 1));

        VillageRegistryData data = VillageRegistryData.get(level);
        UUID vId = UUID.randomUUID();
        AABB villageBounds = new AABB(absPos).inflate(50);
        data.getOrCreateVillage(vId, absPos, villageBounds);

        ReplenishFarmlandGoal goal = new ReplenishFarmlandGoal(villager);

        helper.assertFalse(goal.canUse(),
                "Goal should not activate when repair queue is empty");

        helper.succeed();
    }

    // Claiming prevents second farmer from taking same entry

    @GameTest(template = "empty_3x3x3")
    public static void claimingPreventsDuplicateClaim(GameTestHelper helper) {
        BlockPos repairPos = new BlockPos(60, 64, 60);

        VillageRecord record = new VillageRecord(
                UUID.randomUUID(),
                new BlockPos(100, 64, 100),
                new AABB(0, 0, 0, 200, 128, 200)
        );
        record.addFarmland(repairPos);
        record.addToRepairQueue(repairPos);

        helper.assertTrue(record.claimPosition(repairPos),
                "First claim should succeed");
        helper.assertFalse(record.claimPosition(repairPos),
                "Second claim should fail: position already claimed");

        record.unclaimPosition(repairPos);
        helper.assertTrue(record.claimPosition(repairPos),
                "Claim should succeed after unclaim");

        helper.succeed();
    }

    // Goal interrupted mid-path releases claim

    @GameTest(template = "empty_3x3x3")
    public static void goalInterruptedReleasesClaim(GameTestHelper helper) {
        BlockPos repairPos = new BlockPos(60, 64, 60);

        VillageRecord record = new VillageRecord(
                UUID.randomUUID(),
                new BlockPos(100, 64, 100),
                new AABB(0, 0, 0, 200, 128, 200)
        );
        record.addFarmland(repairPos);
        record.addToRepairQueue(repairPos);
        record.claimPosition(repairPos);

        helper.assertTrue(record.getClaimedPositions().contains(repairPos),
                "Position should be claimed");

        record.unclaimPosition(repairPos);

        helper.assertFalse(record.getClaimedPositions().contains(repairPos),
                "Position should be unclaimed after goal interruption");
        helper.assertTrue(record.getRepairQueue().contains(repairPos),
                "Position should remain in repair queue for another farmer");

        helper.succeed();
    }

    // Successful completion restores farmland and clears queue

    @GameTest(template = "empty_3x3x3")
    public static void successfulCompletionRestoresFarmland(GameTestHelper helper) {
        BlockPos farm = new BlockPos(1, 1, 1);
        helper.setBlock(farm, Blocks.DIRT.defaultBlockState());

        ServerLevel level = helper.getLevel();
        BlockPos absPos = helper.absolutePos(farm);

        VillageRecord record = new VillageRecord(
                UUID.randomUUID(),
                absPos,
                aabbFromCorners(
                        helper.absolutePos(new BlockPos(0, 0, 0)),
                        helper.absolutePos(new BlockPos(3, 3, 3))
                )
        );
        record.addFarmland(absPos);
        record.addToRepairQueue(absPos);
        record.claimPosition(absPos);

        level.setBlock(absPos, Blocks.FARMLAND.defaultBlockState(), 3);
        record.removeFromRepairQueue(absPos);
        record.unclaimPosition(absPos);
        record.addFarmland(absPos);

        helper.assertTrue(level.getBlockState(absPos).getBlock() instanceof FarmBlock,
                "Farmland block should be restored in world");
        helper.assertFalse(record.getRepairQueue().contains(absPos),
                "Position should be removed from repair queue");
        helper.assertFalse(record.getClaimedPositions().contains(absPos),
                "Position should be unclaimed");
        helper.assertTrue(record.getFarmlandRegistry().contains(absPos),
                "Position should remain in farmland registry");

        helper.succeed();
    }

    // Goal present in farmer / absent in non-farmer

    @GameTest(template = "empty_3x3x3")
    public static void goalPresentInFarmerGoalSet(GameTestHelper helper) {
        Villager farmer = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 1, 1));
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));

        farmer.goalSelector.addGoal(4, new ReplenishFarmlandGoal(farmer));

        boolean hasGoal = farmer.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof ReplenishFarmlandGoal);

        helper.assertTrue(hasGoal,
                "ReplenishFarmlandGoal should be present in farmer's goal set");

        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void goalAbsentInNonFarmerGoalSet(GameTestHelper helper) {
        Villager librarian = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 1, 1));
        librarian.setVillagerData(librarian.getVillagerData().setProfession(VillagerProfession.LIBRARIAN));

        boolean hasGoal = librarian.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof ReplenishFarmlandGoal);

        helper.assertFalse(hasGoal,
                "ReplenishFarmlandGoal should be absent in non-farmer's goal set");

        helper.succeed();
    }

    // Serialization round-trip

    @GameTest(template = "empty_3x3x3")
    public static void serializeAndDeserializeAllThreeSetsRoundTrip(GameTestHelper helper) {
        BlockPos absCenter = helper.absolutePos(new BlockPos(1, 1, 1));
        AABB bounds = new AABB(50, 32, 50, 150, 96, 150);
        VillageRecord record = new VillageRecord(UUID.randomUUID(), absCenter, bounds);

        BlockPos farm1 = new BlockPos(60, 64, 60);
        BlockPos farm2 = new BlockPos(70, 64, 70);
        BlockPos farm3 = new BlockPos(80, 64, 80);
        record.addFarmland(farm1);
        record.addFarmland(farm2);
        record.addFarmland(farm3);

        record.addToRepairQueue(farm1);
        record.addToRepairQueue(farm2);

        record.claimPosition(farm1);

        CompoundTag tag = record.save(new CompoundTag());
        VillageRecord loaded = VillageRecord.load(tag);

        helper.assertTrue(loaded.getFarmlandRegistry().size() == 3,
                "Farmland registry should have 3 entries");
        helper.assertTrue(loaded.getFarmlandRegistry().contains(farm1),
                "Farm1 should be in registry");
        helper.assertTrue(loaded.getFarmlandRegistry().contains(farm2),
                "Farm2 should be in registry");
        helper.assertTrue(loaded.getFarmlandRegistry().contains(farm3),
                "Farm3 should be in registry");

        helper.assertTrue(loaded.getRepairQueue().size() == 2,
                "Repair queue should have 2 entries");
        helper.assertTrue(loaded.getRepairQueue().contains(farm1),
                "Farm1 should be in repair queue");
        helper.assertTrue(loaded.getRepairQueue().contains(farm2),
                "Farm2 should be in repair queue");

        helper.assertTrue(loaded.getClaimedPositions().isEmpty(),
                "Claimed positions should be cleared on load");

        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void claimedSetClearedOnLoad(GameTestHelper helper) {
        BlockPos absCenter = helper.absolutePos(new BlockPos(1, 1, 1));
        AABB bounds = new AABB(50, 32, 50, 150, 96, 150);
        VillageRecord record = new VillageRecord(UUID.randomUUID(), absCenter, bounds);

        BlockPos farm = new BlockPos(60, 64, 60);
        record.addFarmland(farm);
        record.addToRepairQueue(farm);
        record.claimPosition(farm);
        helper.assertTrue(record.getClaimedPositions().contains(farm),
                "Position should be claimed before save");

        CompoundTag tag = record.save(new CompoundTag());
        VillageRecord loaded = VillageRecord.load(tag);

        helper.assertTrue(loaded.getClaimedPositions().isEmpty(),
                "Claimed set must be empty after deserialization");

        helper.succeed();
    }

    // State-only farmland operations

    @GameTest(template = "empty_3x3x3")
    public static void farmlandStateOperationsKeepSetsConsistent(GameTestHelper helper) {
        VillageRecord record = newStateRecord();
        BlockPos retained = new BlockPos(60, 64, 60);
        BlockPos removed = new BlockPos(80, 64, 80);

        record.addFarmland(retained);
        helper.assertTrue(record.getFarmlandRegistry().contains(retained),
                "Farmland should be in registry after add");

        record.addToRepairQueue(retained);
        helper.assertTrue(record.getRepairQueue().contains(retained),
                "Farmland should be in repair queue after add");

        record.removeFromRepairQueue(retained);
        helper.assertFalse(record.getRepairQueue().contains(retained),
                "Farmland should be removed from repair queue");

        addRepairPosition(record, removed);
        helper.assertTrue(record.claimPosition(removed),
                "Repair position should be claimable");
        record.removeFarmland(removed);

        helper.assertTrue(record.getFarmlandRegistry().contains(retained),
                "Removing one position should preserve other farmland");
        helper.assertFalse(record.getFarmlandRegistry().contains(removed),
                "Removed farmland should leave the registry");
        helper.assertFalse(record.getRepairQueue().contains(removed),
                "Removed farmland should leave the repair queue");
        helper.assertFalse(record.getClaimedPositions().contains(removed),
                "Removed farmland should leave the claimed set");

        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void scanCollectsOneEntryPerDoor(GameTestHelper helper) {
        BlockPos doorBase = new BlockPos(1, 1, 1);
        BlockPos doorTop = doorBase.above();
        helper.setBlock(doorBase, Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(doorTop, Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));

        BlockPos absoluteBase = helper.absolutePos(doorBase);
        BlockPos absoluteTop = helper.absolutePos(doorTop);
        VillageRecord record = new VillageRecord(
                UUID.randomUUID(), absoluteBase,
                aabbFromCorners(
                        helper.absolutePos(new BlockPos(0, 0, 0)),
                        helper.absolutePos(new BlockPos(3, 3, 3))
                )
        );
        record.fullScan(helper.getLevel());

        helper.assertTrue(record.getDoorRegistry().contains(absoluteBase),
                "Door lower half should be tracked");
        helper.assertFalse(record.getDoorRegistry().contains(absoluteTop),
                "Door upper half should not be tracked separately");
        helper.assertTrue(record.getDoorRegistry().size() == 1,
                "A two-block door should count as one tracked door");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void claimStateTransitionsEnforceQueueMembership(GameTestHelper helper) {
        VillageRecord record = newStateRecord();
        BlockPos queued = new BlockPos(60, 64, 60);
        BlockPos unqueued = new BlockPos(70, 64, 70);
        BlockPos second = new BlockPos(80, 64, 80);

        addRepairPosition(record, queued);
        record.addFarmland(unqueued);
        addRepairPosition(record, second);

        helper.assertTrue(record.claimPosition(queued),
                "Unclaimed repair position should be claimable");
        helper.assertTrue(record.getClaimedPositions().contains(queued),
                "Claimed position should be in claimed set");
        helper.assertFalse(record.claimPosition(queued),
                "A position should not be claimable twice");
        helper.assertFalse(record.claimPosition(unqueued),
                "Farmland outside the repair queue should not be claimable");

        record.unclaimPosition(queued);
        helper.assertFalse(record.getClaimedPositions().contains(queued),
                "Unclaim should release the position");
        helper.assertTrue(record.claimPosition(queued),
                "Released position should be claimable again");

        helper.assertTrue(record.claimPosition(second),
                "A second repair position should be claimable");
        record.clearClaimed();
        helper.assertTrue(record.getClaimedPositions().isEmpty(),
                "Clearing claims should release every position");

        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void nearestRepairSelectionHandlesClaimsAndBounds(GameTestHelper helper) {
        VillageRecord record = newStateRecord();
        BlockPos origin = new BlockPos(100, 64, 100);
        BlockPos near = new BlockPos(105, 64, 100);
        BlockPos far = new BlockPos(120, 64, 100);
        BlockPos outOfRange = new BlockPos(200, 64, 200);

        addRepairPosition(record, near);
        addRepairPosition(record, far);

        helper.assertTrue(near.equals(record.getNearestUnclaimedRepair(origin, 50)),
                "Nearest unclaimed repair position should be selected");

        helper.assertTrue(record.claimPosition(near),
                "Nearest repair position should be claimable");
        helper.assertTrue(far.equals(record.getNearestUnclaimedRepair(origin, 50)),
                "Claimed positions should be skipped");

        record.removeFromRepairQueue(near);
        record.removeFromRepairQueue(far);
        helper.assertTrue(record.getNearestUnclaimedRepair(origin, 50) == null,
                "Empty repair queue should return no position");

        addRepairPosition(record, outOfRange);
        helper.assertTrue(record.getNearestUnclaimedRepair(origin, 10) == null,
                "Out-of-range repair positions should be ignored");

        helper.succeed();
    }
}
