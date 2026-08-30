package com.orangevillager61.emeraldcapitalism.test;

import com.mojang.datafixers.util.Pair;
import com.orangevillager61.emeraldcapitalism.behavior.UseZombieSmellBehavior;
import com.orangevillager61.emeraldcapitalism.item.RottenFleshCoverItem;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEffects;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.VillagerGoalPackages;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class ZombieSmellGameTests {

    private ZombieSmellGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void usingCoverAppliesThirtySecondZombieSmell(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(ECAPItems.ROTTEN_FLESH_COVER.get()));

        ((RottenFleshCoverItem) ECAPItems.ROTTEN_FLESH_COVER.get())
                .use(helper.getLevel(), player, InteractionHand.MAIN_HAND);

        MobEffectInstance smell = player.getEffect(ECAPEffects.ZOMBIE_SMELL);
        helper.assertTrue(smell != null
                        && smell.getDuration() == RottenFleshCoverItem.ZOMBIE_SMELL_DURATION_TICKS,
                "using rotten flesh cover did not apply Zombie Smell for exactly 30 seconds");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
                "using rotten flesh cover did not consume one cover");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void zombieSmellClearsAndRejectsZombieTargets(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos zombiePos = helper.absolutePos(new BlockPos(2, 1, 1));
        player.setPos(zombiePos.getX() + 0.5D, zombiePos.getY(), zombiePos.getZ() + 0.5D);
        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 2, 1, 1);

        zombie.setTarget(player);
        helper.assertTrue(zombie.getTarget() == player,
                "test zombie did not accept its initial player target");

        player.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_SMELL, RottenFleshCoverItem.ZOMBIE_SMELL_DURATION_TICKS,
                0, false, true, true));
        helper.assertTrue(zombie.getTarget() == null,
                "adding Zombie Smell did not clear an existing zombie target");

        zombie.setTarget(player);
        helper.assertTrue(zombie.getTarget() == null,
                "Zombie Smell did not reject a new zombie target assignment");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void villagerUsesCoverForZombieWithClearSight(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 2, 1, 1);
        villager.getInventory().addItem(new ItemStack(ECAPItems.ROTTEN_FLESH_COVER.get()));

        BehaviorControl<Villager> behavior = findBehavior(
                VillagerGoalPackages.getCorePackage(VillagerProfession.NONE, 0.5F));
        helper.assertTrue(behavior.tryStart(helper.getLevel(), villager, helper.getLevel().getGameTime()),
                "villager did not use a cover when a zombie had clear sight");
        helper.assertTrue(villager.getEffect(ECAPEffects.ZOMBIE_SMELL) != null,
                "villager cover use did not apply Zombie Smell");
        helper.assertValueEqual(villager.getInventory().countItem(ECAPItems.ROTTEN_FLESH_COVER.get()), 0,
                "villager cover use did not consume one cover");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void villagerKeepsCoverWhenZombieSightIsBlocked(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        helper.setBlock(new net.minecraft.core.BlockPos(2, 1, 1), Blocks.STONE.defaultBlockState());
        helper.setBlock(new net.minecraft.core.BlockPos(2, 2, 1), Blocks.STONE.defaultBlockState());
        helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 3, 1, 1);
        villager.getInventory().addItem(new ItemStack(ECAPItems.ROTTEN_FLESH_COVER.get()));

        BehaviorControl<Villager> behavior = findBehavior(
                VillagerGoalPackages.getCorePackage(VillagerProfession.NONE, 0.5F));
        helper.assertFalse(behavior.tryStart(helper.getLevel(), villager, helper.getLevel().getGameTime()),
                "villager used a cover through a blocking wall");
        helper.assertTrue(villager.getEffect(ECAPEffects.ZOMBIE_SMELL) == null,
                "blocked zombie sight incorrectly applied Zombie Smell");
        helper.assertValueEqual(villager.getInventory().countItem(ECAPItems.ROTTEN_FLESH_COVER.get()), 1,
                "blocked zombie sight incorrectly consumed the cover");
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    private static BehaviorControl<Villager> findBehavior(
            List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> packageEntries) {
        return packageEntries.stream()
                .filter(entry -> entry.getSecond().getClass() == UseZombieSmellBehavior.class)
                .map(entry -> (BehaviorControl<Villager>) entry.getSecond())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing UseZombieSmellBehavior"));
    }
}
