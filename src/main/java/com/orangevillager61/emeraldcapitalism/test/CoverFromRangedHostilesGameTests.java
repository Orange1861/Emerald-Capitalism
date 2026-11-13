package com.orangevillager61.emeraldcapitalism.test;

import com.mojang.datafixers.util.Pair;
import com.orangevillager61.emeraldcapitalism.behavior.CoverSeekingFlee;
import com.orangevillager61.emeraldcapitalism.behavior.VillagerGoalPackageIntegration;
import com.orangevillager61.emeraldcapitalism.util.EntityCreation;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.VillagerGoalPackages;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A panicking villager that sees a ranged hostile tries to get out of its sight. Journey tests are
 * natural AI after fixture setup; the package test is a component test and is labeled.
 */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class CoverFromRangedHostilesGameTests {

    private CoverFromRangedHostilesGameTests() {
    }

    /** Natural AI: a long wall stands between a spot behind it and a pillager; the villager goes there. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_ranged_cover", timeoutTicks = 500)
    public static void panickingVillagerWalksBehindCoverFromAPillager(GameTestHelper helper) {
        if (helper.getLevel().getDifficulty() == Difficulty.PEACEFUL) {
            helper.fail("panic needs difficulty easy or higher");
            return;
        }
        installFloor(helper);
        // A wall at x = 4 across z = 8..12, three high. Behind it (x >= 5) the pillager has no line.
        for (int z = 8; z <= 12; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(4, y, z), Blocks.STONE.defaultBlockState());
            }
        }
        Villager villager = helper.spawn(EntityType.VILLAGER, 3, 1, 10);
        Monster pillager = held(helper, EntityType.PILLAGER, 0, 10);

        boolean[] panicked = {false};
        poll(helper, 300, () -> {
            panicked[0] |= villager.getBrain().isActive(Activity.PANIC);
            return panicked[0] && !villager.hasLineOfSight(pillager);
        }, () -> "the villager never got out of the pillager's sight: panicked=" + panicked[0]
                + " pos=" + villager.position(), () -> {
            pillager.discard();
            helper.succeed();
        });
    }

    /** Natural AI: a melee hostile is not a reason to seek cover; the villager flees as vanilla does. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_ranged_cover", timeoutTicks = 300)
    public static void panickingVillagerStillFleesAMeleeHostileInTheOpen(GameTestHelper helper) {
        if (helper.getLevel().getDifficulty() == Difficulty.PEACEFUL) {
            helper.fail("panic needs difficulty easy or higher");
            return;
        }
        installFloor(helper);
        Villager villager = helper.spawn(EntityType.VILLAGER, 10, 1, 10);
        Monster zombie = held(helper, EntityType.ZOMBIE, 8, 10);
        double start = villager.distanceTo(zombie);
        poll(helper, 200, () -> villager.distanceTo(zombie) >= start + 1.5D,
                () -> "the villager did not run from the zombie: " + start + " -> " + villager.distanceTo(zombie),
                () -> {
                    zombie.discard();
                    helper.succeed();
                });
    }

    /** Component: both walk-away movers are wrapped once, nothing else changes, and wrapping is idempotent. */
    @GameTest(template = "empty_3x3x3")
    public static void panicPackageWrapsBothMoversOnceAndKeepsVanillaOrder(GameTestHelper helper) {
        List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> panic =
                VillagerGoalPackages.getPanicPackage(VillagerProfession.NONE, 0.5F);
        long wrappers = panic.stream().filter(entry -> entry.getSecond() instanceof CoverSeekingFlee).count();
        helper.assertValueEqual((int) wrappers, 2, "the panic package must wrap exactly its two movers");
        helper.assertTrue(panic.stream().filter(entry -> entry.getSecond() instanceof CoverSeekingFlee)
                        .allMatch(entry -> entry.getFirst() == 1),
                "a wrapped mover moved away from vanilla's priority");
        List<Integer> priorities = panic.stream().map(Pair::getFirst).toList();
        helper.assertValueEqual(priorities, List.of(0, 1, 1, 3, 5),
                "the panic package no longer has vanilla's entries in vanilla's order");

        List<Pair<Integer, ? extends BehaviorControl<? super Villager>>> again =
                VillagerGoalPackageIntegration.addPanicBehaviors(0.5F, panic);
        helper.assertValueEqual(again.stream().filter(entry -> entry.getSecond() instanceof CoverSeekingFlee).count(),
                2L, "wrapping twice wrapped a wrapper");
        helper.assertValueEqual(again.size(), panic.size(), "wrapping twice changed the package size");
        helper.succeed();
    }

    private static Monster held(GameTestHelper helper, EntityType<? extends Monster> type, int x, int z) {
        Monster monster = EntityCreation.create(type, helper.getLevel());
        BlockPos pos = helper.absolutePos(new BlockPos(x, 1, z));
        monster.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
        // Held and harmless: the villager reacts to it being there, not to anything it does.
        monster.setNoAi(true);
        monster.setInvulnerable(true);
        monster.setPersistenceRequired();
        helper.getLevel().addFreshEntity(monster);
        return monster;
    }

    private static void installFloor(GameTestHelper helper) {
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static void poll(GameTestHelper helper, int remaining, BooleanSupplier done,
                             java.util.function.Supplier<String> failure, Runnable next) {
        if (done.getAsBoolean()) {
            next.run();
            return;
        }
        if (remaining <= 0) {
            helper.fail(failure.get());
            return;
        }
        helper.runAfterDelay(1, () -> poll(helper, remaining - 1, done, failure, next));
    }

    @SuppressWarnings("unused")
    private static boolean sees(LivingEntity viewer, LivingEntity target) {
        return viewer.hasLineOfSight(target);
    }
}
