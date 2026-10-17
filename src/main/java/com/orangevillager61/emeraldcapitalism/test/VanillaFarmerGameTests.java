package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Regression coverage for vanilla farmer harvest and replant behavior. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class VanillaFarmerGameTests {

    private VanillaFarmerGameTests() {
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_farmer_vanilla_work", timeoutTicks = 3_300)
    public static void normalFarmerHarvestsAndReplantsMatureWheat(GameTestHelper helper) {
        installFloor(helper);
        BlockPos jobSite = new BlockPos(2, 1, 2);
        // Keep the crop at the edge of the secondary-POI sensor range from
        // the composter. The farmer starts on the opposite side of the
        // composter, so normal AI must first reach its job site and then
        // navigate out to the crop.
        BlockPos farmland = new BlockPos(6, 1, 2);
        BlockPos crop = farmland.above();
        helper.setBlock(jobSite, Blocks.COMPOSTER.defaultBlockState());
        helper.setBlock(farmland, Blocks.FARMLAND.defaultBlockState());
        helper.setBlock(crop, Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE));

        Villager farmer = helper.spawn(EntityType.VILLAGER, 0, 1, 2);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        farmer.setOnGround(true);
        // Keep the test inside the daytime work window so the farmer's normal
        // work package can select the crop behavior.
        helper.getLevel().setDayTime(5_000L);
        farmer.refreshBrain(helper.getLevel());
        farmer.getInventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));

        int previousRandomTickSpeed = helper.getLevel().getGameRules()
                .getInt(GameRules.RULE_RANDOMTICKING);
        helper.getLevel().getGameRules().getRule(GameRules.RULE_RANDOMTICKING)
                .set(0, helper.getLevel().getServer());
        helper.runAfterDelay(1, () -> awaitHarvestAndReplant(helper, farmer,
                helper.absolutePos(crop), previousRandomTickSpeed, 3_000));
    }

    private static void installFloor(GameTestHelper helper) {
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static void awaitHarvestAndReplant(GameTestHelper helper, Villager farmer,
                                                BlockPos cropPos, int previousRandomTickSpeed,
                                                int remainingTicks) {
        BlockState crop = helper.getLevel().getBlockState(cropPos);
        if (crop.is(Blocks.WHEAT) && crop.getValue(CropBlock.AGE) < CropBlock.MAX_AGE) {
            helper.getLevel().getGameRules().getRule(GameRules.RULE_RANDOMTICKING)
                    .set(previousRandomTickSpeed, helper.getLevel().getServer());
            helper.assertValueEqual(0, crop.getValue(CropBlock.AGE),
                    "farmer harvested the crop but did not replant it at age zero");
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            helper.getLevel().getGameRules().getRule(GameRules.RULE_RANDOMTICKING)
                    .set(previousRandomTickSpeed, helper.getLevel().getServer());
            helper.fail("farmer did not harvest and replant mature wheat; crop=" + crop
                    + ", pos=" + farmer.position() + ", blockPos=" + farmer.blockPosition()
                    + ", jobSite=" + farmer.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                    + ", secondaryJobSite="
                    + farmer.getBrain().getMemory(MemoryModuleType.SECONDARY_JOB_SITE)
                    + ", activity=" + farmer.getBrain().getActiveNonCoreActivity()
                    + ", runningBehaviors=" + farmer.getBrain().getRunningBehaviors().stream()
                    .map(behavior -> behavior.getClass().getSimpleName() + ":" + behavior.getStatus()
                            + ":" + behavior)
                    .toList()
                    + ", runningGoals=" + farmer.goalSelector.getAvailableGoals().stream()
                    .filter(goal -> goal.isRunning())
                    .map(goal -> goal.getGoal().getClass().getSimpleName())
                    .toList()
                    + ", managerHandle=" + MovementRequestManager.activeHandle(farmer)
                    + ", walkTarget=" + farmer.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                    + ", path=" + farmer.getBrain().getMemory(MemoryModuleType.PATH));
            return;
        }
        helper.runAfterDelay(1, () -> awaitHarvestAndReplant(helper, farmer, cropPos,
                previousRandomTickSpeed, remainingTicks - 1));
    }
}
