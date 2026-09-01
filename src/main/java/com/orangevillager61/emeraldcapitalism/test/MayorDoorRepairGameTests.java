package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldChestBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.entity.ai.MayorDoorRepairGoal;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
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
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class MayorDoorRepairGameTests {

    private MayorDoorRepairGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void mayorRepairGoalIsInjectedAtDaytimePriority(GameTestHelper helper) {
        Villager mayor = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        mayor.setVillagerData(mayor.getVillagerData().setProfession(ECAPVillagerProfessions.MAYOR.get()));

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
        mayor.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), bankPos));
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
        helper.assertTrue(village.getMissingDoorRegistry().isEmpty(),
                "repaired door remained in the missing-door registry");
        helper.assertTrue(village.getClaimedDoorPositions().isEmpty(),
                "Mayor did not release the repaired door claim");
        helper.assertValueEqual(bank.getTotalPlankCount(), 0,
                "bank did not provide exactly six plank-equivalents for the repair");
        helper.succeed();
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
        mayor.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), bankPos));
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

        BlockState lower = Blocks.OAK_DOOR.defaultBlockState();
        level.setBlock(doorPos, lower, Block.UPDATE_ALL);
        level.setBlock(doorPos.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER),
                Block.UPDATE_ALL);

        VillageRecord village = VillageRegistryData.get(level).getOrCreateVillage(
                UUID.randomUUID(), bellPos, new AABB(bellPos).inflate(6.0D));
        village.fullScan(level);
        helper.assertTrue(village.getDoorRegistry().contains(doorPos),
                "door was not present in the published village cache");

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
        helper.succeed();
    }

    private static RepairFixture createFixture(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chestPos = helper.absolutePos(new BlockPos(1, 1, 2));
        BlockPos doorPos = helper.absolutePos(new BlockPos(2, 1, 0));
        for (int x = -1; x <= 4; x++) {
            helper.setBlock(new BlockPos(x, 0, 0), Blocks.STONE.defaultBlockState());
        }
        helper.setBlock(new BlockPos(1, 1, 1), ECAPBlocks.BANK.get().defaultBlockState());
        helper.setBlock(new BlockPos(1, 1, 2), ECAPBlocks.EMERALD_CHEST.get().defaultBlockState());

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
        village.addDoor(doorPos);
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
}
