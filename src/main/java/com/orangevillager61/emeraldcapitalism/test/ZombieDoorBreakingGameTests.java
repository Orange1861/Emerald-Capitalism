package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class ZombieDoorBreakingGameTests {

    private ZombieDoorBreakingGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void doorBreakingUsesConfiguredDifficultyRule(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Difficulty previousDifficulty = level.getDifficulty();
        boolean previousAnyDifficulty = Config.zombiesCanBreakDoorsOnAnyDifficulty;
        try {
            level.getServer().setDifficulty(Difficulty.EASY, true);
            Config.zombiesCanBreakDoorsOnAnyDifficulty = true;

            BlockPos localDoorPos = new BlockPos(2, 1, 1);
            BlockPos doorPos = helper.absolutePos(localDoorPos);
            helper.setBlock(localDoorPos, Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
            helper.setBlock(localDoorPos.above(), Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));

            Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 1, 1);
            zombie.setCanBreakDoors(true);
            zombie.horizontalCollision = true;
            helper.assertTrue(zombie.getNavigation().moveTo(
                            new Path(List.of(new Node(doorPos.getX(), doorPos.getY() - 1, doorPos.getZ())),
                                    doorPos, false), 1.0D),
                    "zombie test path could not be installed");

            BreakDoorGoal goal = zombie.goalSelector.getAvailableGoals().stream()
                    .map(wrappedGoal -> wrappedGoal.getGoal())
                    .filter(BreakDoorGoal.class::isInstance)
                    .map(BreakDoorGoal.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("zombie is missing its break-door goal"));
            helper.assertTrue(goal.canUse(),
                    "door-breaking zombie should use its goal on Easy when enabled");

            Config.zombiesCanBreakDoorsOnAnyDifficulty = false;
            helper.assertFalse(goal.canUse(),
                    "door-breaking zombie should retain vanilla Hard-only behavior when disabled");
            helper.succeed();
        } finally {
            Config.zombiesCanBreakDoorsOnAnyDifficulty = previousAnyDifficulty;
            level.getServer().setDifficulty(previousDifficulty, true);
        }
    }

    @GameTest(template = "empty_3x3x3")
    public static void spawnUsesConfiguredDoorBreakingChance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int previousChance = Config.zombieDoorBreakingChancePercent;
        try {
            DifficultyInstance neutralDifficulty = new DifficultyInstance(
                    Difficulty.PEACEFUL, 0L, 0L, 0.0F);
            Config.zombieDoorBreakingChancePercent = 0;
            Zombie cannotBreakDoors = EntityType.ZOMBIE.create(level);
            helper.assertTrue(cannotBreakDoors != null, "could not create 0% zombie fixture");
            cannotBreakDoors.moveTo(helper.absolutePos(new BlockPos(1, 1, 1)), 0.0F, 0.0F);
            cannotBreakDoors.finalizeSpawn(level, neutralDifficulty, MobSpawnType.COMMAND,
                    new Zombie.ZombieGroupData(false, false));
            level.addFreshEntity(cannotBreakDoors);
            helper.assertFalse(cannotBreakDoors.canBreakDoors(),
                    "0% chance must prevent the door-breaking ability");

            Config.zombieDoorBreakingChancePercent = 100;
            Zombie canBreakDoors = EntityType.ZOMBIE.create(level);
            helper.assertTrue(canBreakDoors != null, "could not create 100% zombie fixture");
            canBreakDoors.moveTo(helper.absolutePos(new BlockPos(2, 1, 1)), 0.0F, 0.0F);
            canBreakDoors.finalizeSpawn(level, neutralDifficulty, MobSpawnType.COMMAND,
                    new Zombie.ZombieGroupData(false, false));
            level.addFreshEntity(canBreakDoors);
            helper.assertTrue(canBreakDoors.canBreakDoors(),
                    "100% chance must grant the door-breaking ability");
            helper.succeed();
        } finally {
            Config.zombieDoorBreakingChancePercent = previousChance;
        }
    }

    @GameTest(template = "empty_3x3x3")
    public static void loadedZombiePreservesDoorBreakingAbility(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Zombie original = EntityType.ZOMBIE.create(level);
        helper.assertTrue(original != null, "could not create saved zombie fixture");
        original.setCanBreakDoors(true);
        CompoundTag saved = original.saveWithoutId(new CompoundTag());

        Zombie restored = EntityType.ZOMBIE.create(level);
        helper.assertTrue(restored != null, "could not create restored zombie fixture");
        restored.load(saved);
        restored.moveTo(helper.absolutePos(new BlockPos(1, 1, 1)), 0.0F, 0.0F);
        level.addFreshEntity(restored);
        helper.assertTrue(restored.canBreakDoors(),
                "joining a loaded zombie must not reroll its persisted door-breaking ability");
        helper.succeed();
    }
}
