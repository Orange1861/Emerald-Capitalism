package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageWorkCell;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Component tests, through the village's own roster, dispatch, and lease code, that
 * bank employment excludes a farmer-profession villager from queued farmer work.
 */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class FarmerBankEmploymentGameTests {

    private static final BlockPos BANK_LOCAL = new BlockPos(10, 1, 10);
    private static final BlockPos WORK_LOCAL = new BlockPos(3, 1, 5);

    private FarmerBankEmploymentGameTests() {
    }

    private record Site(VillageRecord village, BankBlockEntity bank, BlockPos bankPos, BlockPos work) {
    }

    @GameTest(template = "empty_20x3x20")
    public static void aBankEmployeeFarmerIsNotOnTheRosterAndTheNormalFarmerGetsTheWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site site = site(helper);
        // The employee stands closer to the work than the normal farmer, so a
        // distance-based choice would pick it if it were eligible.
        Villager normal = farmer(helper, site.village(), new BlockPos(3, 1, 3));
        Villager employee = farmer(helper, site.village(), new BlockPos(3, 1, 4));
        helper.assertTrue(site.bank().registerEmployeeFromJob(level, employee, site.bankPos()),
                "the farmer-profession villager was not registered as the bank's employee");

        helper.assertFalse(site.village().getFarmerRoster().contains(employee.getUUID()),
                "acquiring bank employment must remove the farmer from the roster");
        helper.assertFalse(site.village().refreshFarmerRoster(employee),
                "roster refresh must reject a bank employee");
        helper.assertTrue(site.village().getFarmerRoster().contains(normal.getUUID()),
                "the ordinary farmer must stay on the roster");

        helper.assertValueEqual(site.village().dispatchFarmerWork(level, level.getGameTime()), 1,
                "exactly one farmer should receive the queued work");
        VillageWorkCell cell = site.village().getWorkCell(site.work());
        helper.assertTrue(cell != null && cell.getActiveLease() != null
                        && cell.getActiveLease().farmerId().equals(normal.getUUID()),
                "queued work must go to the farmer who is not a bank employee");
        helper.assertTrue(site.village().getWorkCellLeasedTo(employee.getUUID()) == null,
                "the bank employee must hold no lease");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void aBankEmployeeFarmerAloneInTheVillageIsNeverLeasedWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site site = site(helper);
        Villager employee = farmer(helper, site.village(), new BlockPos(3, 1, 4));
        site.bank().registerEmployeeFromJob(level, employee, site.bankPos());

        helper.assertValueEqual(site.village().dispatchFarmerWork(level, level.getGameTime()), 0,
                "dispatch must not lease work to a bank employee");
        helper.assertFalse(site.village().ensureFarmerWorkLease(level, employee, level.getGameTime()),
                "a goal asking for a lease on a bank employee's behalf must be refused");
        helper.assertTrue(site.village().getWorkCellLeasedTo(employee.getUUID()) == null,
                "the bank employee must hold no lease");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void acquiringBankEmploymentReleasesTheFarmersLeaseAndClaims(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site site = site(helper);
        Villager farmer = farmer(helper, site.village(), new BlockPos(3, 1, 4));
        helper.assertValueEqual(site.village().dispatchFarmerWork(level, level.getGameTime()), 1,
                "the farmer should receive the queued work before it is employed");
        helper.assertTrue(site.village().claimRepairPosition(farmer.getUUID(), site.work()),
                "the farmer could not claim its repair position");
        helper.assertTrue(site.village().getClaimedPositions().contains(site.work()),
                "the claim was not recorded");

        helper.assertTrue(site.bank().registerEmployeeFromJob(level, farmer, site.bankPos()),
                "the farmer was not registered as the bank's employee");

        helper.assertTrue(site.village().getWorkCellLeasedTo(farmer.getUUID()) == null,
                "acquiring bank employment must release the farmer's lease");
        helper.assertFalse(site.village().getClaimedPositions().contains(site.work()),
                "acquiring bank employment must release the farmer's exact work claims");
        helper.assertFalse(site.village().getFarmerRoster().contains(farmer.getUUID()),
                "acquiring bank employment must remove the farmer from the roster");
        helper.succeed();
    }

    @GameTest(template = "empty_20x3x20")
    public static void leavingBankEmploymentLetsAnOrdinaryFarmerBeLeasedWorkAgain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site site = site(helper);
        Villager farmer = farmer(helper, site.village(), new BlockPos(3, 1, 4));
        site.bank().registerEmployeeFromJob(level, farmer, site.bankPos());
        helper.assertFalse(site.village().refreshFarmerRoster(farmer),
                "the employed farmer must be excluded");

        helper.assertTrue(site.bank().removeEmployee(farmer.getUUID()),
                "the employee was not removed from the bank");

        helper.assertTrue(site.village().refreshFarmerRoster(farmer),
                "a farmer who is no longer employed must be eligible again");
        helper.assertValueEqual(site.village().dispatchFarmerWork(level, level.getGameTime()), 1,
                "the farmer should receive queued work after leaving bank employment");
        helper.assertTrue(site.village().getWorkCellLeasedTo(farmer.getUUID()) != null,
                "the farmer holds no lease after leaving bank employment");
        helper.succeed();
    }

    private static Site site(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        BankerWorkSideGameTests.placeBank(helper, BANK_LOCAL);
        BlockPos bankPos = helper.absolutePos(BANK_LOCAL);
        BankBlockEntity bank = (BankBlockEntity) level.getBlockEntity(bankPos);
        if (bank == null) {
            throw new IllegalStateException("bank block entity was not created");
        }
        UUID villageId = UUID.randomUUID();
        VillageRegistryData registry = VillageRegistryData.get(level);
        BlockPos min = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos max = helper.absolutePos(new BlockPos(19, 3, 19));
        VillageRecord village = registry.getOrCreateVillage(villageId, bankPos,
                new AABB(min.getX(), min.getY(), min.getZ(),
                        max.getX() + 1.0D, max.getY() + 1.0D, max.getZ() + 1.0D));
        registry.registerBankPosition(villageId, bankPos);
        bank.setVillageId(villageId);

        BlockPos work = helper.absolutePos(WORK_LOCAL);
        village.addFarmland(work);
        village.addToRepairQueue(work);
        return new Site(village, bank, bankPos, work);
    }

    private static Villager farmer(GameTestHelper helper, VillageRecord village, BlockPos local) {
        ServerLevel level = helper.getLevel();
        Villager farmer = helper.spawnWithNoFreeWill(EntityType.VILLAGER, local);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        // Level-one zero-XP farmers with no job site are reset by vanilla on the first Brain tick.
        farmer.setVillagerXp(1);
        village.addMember(new VillagerPOIRecord(
                farmer.getUUID(), farmer.getName().getString(), "FARMER",
                null, null, null, farmer.getHealth(), VillagerPOIRecord.Status.ACTIVE,
                0, level.getGameTime()));
        helper.assertTrue(village.refreshFarmerRoster(farmer), "farmer was not added to the village roster");
        return farmer;
    }
}
