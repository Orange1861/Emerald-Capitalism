package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldChestBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import com.orangevillager61.emeraldcapitalism.entity.ai.MayorDoorRepairGoal;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.Map;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class MayorDoorRepairGameTests {

    private MayorDoorRepairGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void mayorRepairGoalIsInjectedAtDaytimePriority(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos mayorPos = helper.absolutePos(new BlockPos(1, 1, 1));
        Villager mayor = com.orangevillager61.emeraldcapitalism.util.EntityCreation.create(EntityType.VILLAGER, level);
        helper.assertTrue(mayor != null, "could not create the Mayor injection fixture");
        if (mayor == null) {
            return;
        }
        mayor.moveTo(mayorPos.getX() + 0.5D, mayorPos.getY(), mayorPos.getZ() + 0.5D,
                0.0F, 0.0F);
        mayor.setVillagerData(mayor.getVillagerData().setProfession(ECAPVillagerProfessions.MAYOR.get()));
        helper.assertTrue(level.addFreshEntity(mayor), "could not add the Mayor injection fixture");

        var repairGoal = mayor.goalSelector.getAvailableGoals().stream()
                .filter(entry -> entry.getGoal() instanceof MayorDoorRepairGoal)
                .findFirst()
                .orElse(null);
        helper.assertTrue(repairGoal != null,
                "Mayor did not receive the repair goal when it joined the level");
        if (repairGoal != null) {
            helper.assertValueEqual(repairGoal.getPriority(), MayorDoorRepairGoal.GOAL_PRIORITY,
                    "Mayor repair goal priority did not match the daytime work priority");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void mayorConvertsBankPlanksIntoMissingDoor(GameTestHelper helper) {
        RepairFixture fixture = createFixture(helper);
        if (fixture == null) {
            return;
        }
        ServerLevel level = fixture.level();
        BlockPos bankPos = fixture.bankPos();
        BlockPos doorPos = fixture.doorPos();
        BankBlockEntity bank = fixture.bank();
        VillageRecord village = fixture.village();

        Villager mayor = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 0);
        mayor.setVillagerData(mayor.getVillagerData().setProfession(ECAPVillagerProfessions.MAYOR.get()));
        MayorDoorRepairGoal goal = new MayorDoorRepairGoal(mayor);
        BlockPos bankApproach = BankBlock.getDepositApproachPos(bank.getBlockState(), bankPos);
        mayor.startSleeping(mayor.blockPosition());
        helper.assertFalse(goal.canUse(), "Mayor selected the repair task while sleeping");
        mayor.stopSleeping();
        mayor.setPos(bankApproach.getX() + 0.5D, bankApproach.getY(), bankApproach.getZ() + 0.5D);
        helper.assertTrue(goal.canUse(), "Mayor did not select the daytime door-repair task");
        Villager conversationalVillager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 3, 1, 0);
        mayor.getBrain().setMemory(MemoryModuleType.INTERACTION_TARGET, conversationalVillager);
        goal.start();
        helper.assertTrue(village.getClaimedDoorPositions().contains(doorPos),
                "Mayor did not claim the selected missing door");
        helper.assertValueEqual(bank.getTotalPlankCount(), 6,
                "Mayor withdrew wood before reaching the bank");
        helper.assertValueEqual(mayor.getInventory().countItem(Items.OAK_PLANKS), 0,
                "Mayor picked up wood before reaching the bank");
        helper.assertValueEqual(mayor.getInventory().countItem(Items.OAK_DOOR), 0,
                "Mayor crafted a door before reaching the bank");
        var firstWalkTarget = mayor.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        helper.assertTrue(firstWalkTarget != null,
                "Mayor did not receive a walk target for the bank approach");
        if (firstWalkTarget != null) {
            helper.assertValueEqual(firstWalkTarget.getTarget().currentBlockPosition(), bankApproach,
                    "Mayor did not walk to the bank before the missing door");
        }
        helper.assertTrue(mayor.getBrain().getMemory(MemoryModuleType.INTERACTION_TARGET).isEmpty(),
                "Mayor kept a villager conversation target when repair started");
        helper.assertFalse(new MayorDoorRepairGoal(mayor).canUse(),
                "Mayor retriggered the task while the existing door claim was active");
        goal.tick();
        helper.assertValueEqual(bank.getTotalPlankCount(), 0,
                "Mayor did not withdraw six planks at the bank");
        helper.assertValueEqual(mayor.getInventory().countItem(Items.OAK_PLANKS), 0,
                "Mayor kept the bank planks instead of crafting with them");
        helper.assertValueEqual(mayor.getInventory().countItem(Items.OAK_DOOR), 1,
                "Mayor did not craft an oak door from the withdrawn planks");
        mayor.setPos(doorPos.getX() + 0.5D, doorPos.getY(), doorPos.getZ() + 0.5D);
        for (int tick = 0; tick < 5; tick++) {
            goal.tick();
        }
        goal.stop();

        helper.assertTrue(VillageRecord.isDoorBase(level.getBlockState(doorPos))
                        && level.getBlockState(doorPos.above()).is(Blocks.OAK_DOOR),
                "Mayor did not place the repaired oak door: lower=" + level.getBlockState(doorPos)
                        + ", upper=" + level.getBlockState(doorPos.above())
                        + ", doors=" + mayor.getInventory().countItem(Items.OAK_DOOR));
        helper.assertValueEqual(level.getBlockState(doorPos).getValue(DoorBlock.FACING), Direction.WEST,
                "Mayor did not restore the cached door facing");
        helper.assertValueEqual(level.getBlockState(doorPos).getValue(DoorBlock.HINGE), DoorHingeSide.RIGHT,
                "Mayor did not restore the cached door hinge side");
        helper.assertTrue(village.getMissingDoorRegistry().isEmpty(),
                "repaired door remained in the missing-door registry");
        helper.assertTrue(village.getClaimedDoorPositions().isEmpty(),
                "Mayor did not release the repaired door claim");
        helper.assertValueEqual(bank.getTotalPlankCount(), 0,
                "bank did not provide exactly six plank-equivalents for the repair");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void mayorDeclinesRepairWhenInitialBankCheckHasInsufficientPlanks(GameTestHelper helper) {
        RepairFixture fixture = createFixture(helper);
        if (fixture == null) {
            return;
        }
        ServerLevel level = fixture.level();
        BankBlockEntity bank = fixture.bank();
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(
                fixture.bankPos().south());
        helper.assertTrue(chest != null, "initial-bank-check fixture did not create its linked chest");
        if (chest == null) {
            return;
        }
        chest.setItem(0, ItemStack.EMPTY);
        BankBlockEntity.serverTick(level, fixture.bankPos(), level.getBlockState(fixture.bankPos()), bank);
        helper.assertValueEqual(bank.getTotalPlankCount(), 0,
                "initial-bank-check fixture still had planks after clearing its chest");

        Villager mayor = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 0);
        mayor.setVillagerData(mayor.getVillagerData().setProfession(ECAPVillagerProfessions.MAYOR.get()));
        MayorDoorRepairGoal goal = new MayorDoorRepairGoal(mayor);

        helper.assertFalse(goal.canUse(),
                "Mayor started a repair after the initial bank plank check failed");
        helper.assertFalse(goal.canContinueToUse(),
                "Mayor kept a repair goal active after the initial bank check failed");
        helper.assertTrue(fixture.village().getClaimedDoorPositions().isEmpty(),
                "Mayor claimed a door before the initial bank plank check succeeded");
        helper.assertTrue(mayor.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty(),
                "Mayor published a walk target after the initial bank plank check failed");
        helper.assertValueEqual(mayor.getInventory().countItem(Items.OAK_DOOR), 0,
                "Mayor received a door after the initial bank plank check failed");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void mayorDeclinesRepairWhenInitialBankCheckCannotFindBank(GameTestHelper helper) {
        RepairFixture fixture = createFixture(helper);
        if (fixture == null) {
            return;
        }
        ServerLevel level = fixture.level();
        level.removeBlock(fixture.bankPos(), false);

        Villager mayor = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 0);
        mayor.setVillagerData(mayor.getVillagerData().setProfession(ECAPVillagerProfessions.MAYOR.get()));
        MayorDoorRepairGoal goal = new MayorDoorRepairGoal(mayor);

        helper.assertFalse(goal.canUse(),
                "Mayor started a repair after its registered bank became unavailable");
        helper.assertFalse(goal.canContinueToUse(),
                "Mayor kept a repair goal active after its registered bank became unavailable");
        helper.assertTrue(fixture.village().getClaimedDoorPositions().isEmpty(),
                "Mayor claimed a door before its registered bank was found");
        helper.assertTrue(mayor.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty(),
                "Mayor published a walk target when its registered bank was unavailable");
        helper.assertValueEqual(mayor.getInventory().countItem(Items.OAK_DOOR), 0,
                "Mayor received a door when its registered bank was unavailable");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_mayor_repair_journey", timeoutTicks = 500)
    public static void mayorCompletesScheduledBankAndDoorMovementLegs(GameTestHelper helper) {
        Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before =
                PerformanceTimingCounters.snapshot();
        PerformanceTimingCounters.EventCountingScope eventScope =
                PerformanceTimingCounters.beginEventCountingScope();
        RepairJourneyProgress progress = new RepairJourneyProgress(eventScope, before);
        try {
            for (int x = 0; x < 20; x++) {
                for (int z = 0; z < 20; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                }
            }
            RepairFixture fixture = createFixture(helper,
                    new BlockPos(5, 1, 5), new BlockPos(5, 1, 6),
                    new BlockPos(9, 1, 5));
            if (fixture == null) {
                progress.restore();
                return;
            }
            fixture.village().setDoorRepairEnabled(true);

            helper.setBlock(new BlockPos(8, 1, 3), ECAPBlocks.VILLAGE_MANAGER.get().defaultBlockState());
            Villager mayor = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 8, 1, 4);
            mayor.setVillagerData(mayor.getVillagerData()
                    .setProfession(ECAPVillagerProfessions.MAYOR.get()));
            mayor.refreshBrain(helper.getLevel());
            BlockPos mayorJobSite = helper.absolutePos(new BlockPos(8, 1, 3));
            mayor.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                    net.minecraft.core.GlobalPos.of(helper.getLevel().dimension(), mayorJobSite));
            mayor.goalSelector.removeAllGoals(ignored -> true);
            mayor.goalSelector.addGoal(MayorDoorRepairGoal.GOAL_PRIORITY,
                    new MayorDoorRepairGoal(mayor));
            helper.runAfterDelay(1,
                    () -> awaitScheduledDoorRepair(helper, fixture, progress, 450));
            helper.runAfterDelay(490, progress::restore);
        } catch (RuntimeException | Error failure) {
            progress.restore();
            throw failure;
        }
    }

    private static void awaitScheduledDoorRepair(GameTestHelper helper, RepairFixture fixture,
                                                 RepairJourneyProgress progress,
                                                 int remainingTicks) {
        try {
            BlockPos doorPos = fixture.doorPos();
            ServerLevel level = fixture.level();
            if (VillageRecord.isDoorBase(level.getBlockState(doorPos))
                    && level.getBlockState(doorPos.above()).is(Blocks.OAK_DOOR)
                    && fixture.village().getMissingDoorRegistry().isEmpty()) {
                Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> after =
                        PerformanceTimingCounters.snapshot();
                helper.assertTrue(counterDelta(after, progress.before,
                                PerformanceTimingCounters.Operation.MOVEMENT_ARRIVAL) >= 2L,
                        "scheduled Mayor repair did not complete both bank and door movement legs");
                helper.assertTrue(counterDelta(after, progress.before,
                                PerformanceTimingCounters.Operation.MOVEMENT_PATH_PLAN_ATTEMPT) >= 2L,
                        "scheduled Mayor repair did not use the vanilla sink for both routes");
                helper.succeed();
                return;
            }
            if (remainingTicks <= 0) {
                progress.restore();
                helper.fail("Mayor did not complete the scheduled bank-to-door repair journey: pos="
                        + fixture.village().getMissingDoorRegistry() + ", block="
                        + level.getBlockState(doorPos));
                return;
            }
            helper.runAfterDelay(1, () -> awaitScheduledDoorRepair(
                    helper, fixture, progress, remainingTicks - 1));
        } catch (RuntimeException | Error failure) {
            progress.restore();
            throw failure;
        } finally {
            if (helper.getLevel().getBlockState(fixture.doorPos()).is(Blocks.OAK_DOOR)) {
                progress.restore();
            }
        }
    }

    private static long counterDelta(
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> after,
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before,
            PerformanceTimingCounters.Operation operation) {
        return after.get(operation).calls() - before.get(operation).calls();
    }

    @GameTest(template = "empty_3x3x3")
    public static void mayorRefundsDoorWhenRepairIsDisabledMidTrip(GameTestHelper helper) {
        RepairFixture fixture = createFixture(helper);
        if (fixture == null) {
            return;
        }
        ServerLevel level = fixture.level();
        BlockPos bankPos = fixture.bankPos();
        BlockPos doorPos = fixture.doorPos();
        BankBlockEntity bank = fixture.bank();
        VillageRecord village = fixture.village();

        Villager mayor = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 0);
        mayor.setVillagerData(mayor.getVillagerData().setProfession(ECAPVillagerProfessions.MAYOR.get()));
        MayorDoorRepairGoal goal = new MayorDoorRepairGoal(mayor);
        BlockPos bankApproach = BankBlock.getDepositApproachPos(bank.getBlockState(), bankPos);
        mayor.startSleeping(mayor.blockPosition());
        helper.assertFalse(goal.canUse(), "Mayor selected the repair task while sleeping");
        mayor.stopSleeping();
        mayor.setPos(bankApproach.getX() + 0.5D, bankApproach.getY(), bankApproach.getZ() + 0.5D);
        helper.assertTrue(goal.canUse(), "Mayor did not select the daytime door-repair task");

        goal.start();
        helper.assertTrue(village.getClaimedDoorPositions().contains(doorPos),
                "Mayor did not claim the selected missing door");
        goal.tick();
        helper.assertValueEqual(bank.getTotalPlankCount(), 0,
                "Mayor did not withdraw six planks at the bank");
        village.setDoorRepairEnabled(false);
        helper.assertFalse(goal.canContinueToUse(),
                "Mayor continued repairing after door repair was disabled");
        goal.stop();

        helper.assertValueEqual(bank.getTotalPlankCount(), 6,
                "Mayor did not return the withdrawn planks after repair was disabled");
        helper.assertValueEqual(mayor.getInventory().countItem(Items.OAK_DOOR), 0,
                "Mayor kept a converted door after repair was disabled");
        helper.assertTrue(village.getClaimedDoorPositions().isEmpty(),
                "Mayor did not release the door claim after interruption");
        helper.assertFalse(VillageRecord.isDoorBase(level.getBlockState(doorPos)),
                "Mayor placed a door after repair was disabled");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void nonPlayerDoorRemovalBecomesRepairTarget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos doorPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos bellPos = helper.absolutePos(new BlockPos(0, 1, 1));
        for (int x = 0; x <= 2; x++) {
            level.setBlock(helper.absolutePos(new BlockPos(x, 0, 1)),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }

        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.EAST)
                .setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT);
        level.setBlock(doorPos, lower, Block.UPDATE_ALL);
        level.setBlock(doorPos.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER),
                Block.UPDATE_ALL);

        VillageRecord village = VillageRegistryData.get(level).getOrCreateVillage(
                UUID.randomUUID(), bellPos, new AABB(bellPos).inflate(6.0D));
        village.fullScan(level);
        helper.assertTrue(village.getDoorRegistry().contains(doorPos),
                "door was not present in the published village cache");
        VillageRecord.DoorPlacement placement = village.getDoorPlacement(doorPos);
        helper.assertTrue(placement != null,
                "door placement was not present in the published village cache");
        if (placement != null) {
            helper.assertValueEqual(placement.facing(), Direction.EAST,
                    "door facing was not recorded in the village cache");
            helper.assertValueEqual(placement.hinge(), DoorHingeSide.RIGHT,
                    "door hinge side was not recorded in the village cache");
        }

        // This is the same direct world mutation used by BreakDoorGoal after
        // its 240-tick breaking animation completes.
        level.removeBlock(doorPos, false);
        level.removeBlock(doorPos.above(), false);

        VillageRegistryManager manager = new VillageRegistryManager(level);
        for (int tick = 0; tick < 20; tick++) {
            manager.tick(level);
        }
        manager.shutdown();

        helper.assertTrue(village.getMissingDoorRegistry().contains(doorPos),
                "periodic village verification did not queue the removed door");
        helper.assertValueEqual(village.getDoorPlacement(doorPos), placement,
                "door placement was discarded when the door became missing");
        helper.succeed();
    }

    private static RepairFixture createFixture(GameTestHelper helper) {
        return createFixture(helper, new BlockPos(1, 1, 1),
                new BlockPos(1, 1, 2), new BlockPos(2, 1, 0));
    }

    private static RepairFixture createFixture(GameTestHelper helper,
                                               BlockPos bankLocalPos,
                                               BlockPos chestLocalPos,
                                               BlockPos doorLocalPos) {
        ServerLevel level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(bankLocalPos);
        BlockPos chestPos = helper.absolutePos(chestLocalPos);
        BlockPos doorPos = helper.absolutePos(doorLocalPos);
        for (int x = -1; x <= 4; x++) {
            helper.setBlock(new BlockPos(x, 0, 0), Blocks.STONE.defaultBlockState());
        }
        helper.setBlock(bankLocalPos, ECAPBlocks.BANK.get().defaultBlockState()
                .setValue(BankBlock.BANKER_AVAILABLE, false));
        helper.setBlock(chestLocalPos, ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());

        BankBlockEntity bank = (BankBlockEntity) level.getBlockEntity(bankPos);
        EmeraldChestBlockEntity chest = (EmeraldChestBlockEntity) level.getBlockEntity(chestPos);
        if (bank == null || chest == null) {
            helper.fail("bank repair fixture did not create its block entities");
            return null;
        }

        UUID villageId = UUID.randomUUID();
        VillageRegistryData registry = VillageRegistryData.get(level);
        VillageRecord village = registry.getOrCreateVillage(villageId, bankPos,
                new AABB(bankPos.getX() - 6, bankPos.getY() - 3, bankPos.getZ() - 6,
                        bankPos.getX() + 6, bankPos.getY() + 3, bankPos.getZ() + 6));
        BlockState originalDoor = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.WEST)
                .setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT);
        village.addDoor(doorPos, originalDoor);
        village.markDoorMissing(doorPos);
        registry.registerBankPosition(villageId, bankPos);
        bank.setVillageId(villageId);
        chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 6));
        BankBlockEntity.serverTick(level, bankPos, level.getBlockState(bankPos), bank);
        return new RepairFixture(level, bankPos, doorPos, bank, village);
    }

    private record RepairFixture(ServerLevel level, BlockPos bankPos, BlockPos doorPos,
                                 BankBlockEntity bank, VillageRecord village) {
    }

    private static final class RepairJourneyProgress {
        private final PerformanceTimingCounters.EventCountingScope eventScope;
        private final Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before;
        private boolean restored;

        private RepairJourneyProgress(PerformanceTimingCounters.EventCountingScope eventScope,
                                      Map<PerformanceTimingCounters.Operation,
                                              PerformanceTimingCounters.Snapshot> before) {
            this.eventScope = eventScope;
            this.before = before;
        }

        private void restore() {
            if (!restored) {
                restored = true;
                eventScope.close();
            }
        }
    }
}
