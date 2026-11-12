package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.behavior.AvoidBoatBehavior;
import com.orangevillager61.emeraldcapitalism.behavior.BrainMovementIntent;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Step 2 coverage for boat avoidance on the Brain movement-intent helper.
 *
 * <p>The first test is natural AI: ordinary vanilla AI and {@code MoveToTargetSink} do the whole
 * escape. The others are component tests that drive {@link AvoidBoatBehavior} directly to pin
 * the memory-ownership rules, and are labeled so.
 */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class BoatAvoidanceIntentGameTests {

    private static final String OWNER = "emeraldcapitalism:boat_avoidance";
    private static final double SAFE_DISTANCE_SQR = 4.6D * 4.6D;

    private BoatAvoidanceIntentGameTests() {
    }

    /** Natural AI: nothing steers the villager after spawn. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_boat_avoidance_intent", timeoutTicks = 500)
    public static void boatAvoidanceNaturallyWalksAwayAndLeavesNoIntentBehind(GameTestHelper helper) {
        installFloor(helper);
        Villager villager = helper.spawn(EntityType.VILLAGER, 10, 1, 10);
        Boat boat = spawnBoat(helper, 12, 1, 10);
        boolean previous = Config.enableBoatAvoidance;
        Config.enableBoatAvoidance = true;
        Progress progress = new Progress(villager, boat, previous);
        helper.runAfterDelay(1, () -> awaitEscape(helper, progress, 160));
    }

    private static void awaitEscape(GameTestHelper helper, Progress progress, int remaining) {
        try {
            WalkTarget walkTarget = progress.villager.getBrain()
                    .getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
            if (OWNER.equals(BrainMovementIntent.ownerOf(walkTarget))) {
                progress.published = true;
                helper.assertTrue(MovementRequestManager.activeHandle(progress.villager) == null,
                        "boat avoidance went through MovementRequestManager");
            }
            if (progress.published && progress.villager.distanceToSqr(progress.boat) >= SAFE_DISTANCE_SQR) {
                progress.boat.discard();
                helper.runAfterDelay(60, () -> verifyIntentGone(helper, progress));
                return;
            }
            if (remaining <= 0) {
                String detail = "published=" + progress.published + ", pos=" + progress.villager.position()
                        + ", boat=" + progress.boat.position() + ", walkTarget=" + walkTarget;
                progress.finish();
                helper.fail("the villager did not walk away from the boat by itself: " + detail);
                return;
            }
            helper.runAfterDelay(1, () -> awaitEscape(helper, progress, remaining - 1));
        } catch (RuntimeException | Error failure) {
            progress.finish();
            throw failure;
        }
    }

    private static void verifyIntentGone(GameTestHelper helper, Progress progress) {
        try {
            helper.assertFalse(BrainMovementIntent.isPublishedIntent(progress.villager.getBrain()
                            .getMemory(MemoryModuleType.WALK_TARGET).orElse(null)),
                    "the boat intent's walk target outlived the boat");
            helper.assertFalse(BrainMovementIntent.isPublishedIntent(progress.villager.getBrain()
                            .getMemory(MemoryModuleType.LOOK_TARGET).orElse(null)),
                    "the boat intent's look target outlived the boat");
            progress.finish();
            helper.succeed();
        } catch (RuntimeException | Error failure) {
            progress.finish();
            throw failure;
        }
    }

    /** Component: another behavior's newer WALK_TARGET ends boat avoidance and survives its stop. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_boat_avoidance_intent")
    public static void stoppedBoatAvoidanceCannotEraseANewerWalkTarget(GameTestHelper helper) {
        installFloor(helper);
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 10, 1, 10);
        spawnBoat(helper, 11, 1, 10);
        boolean previous = Config.enableBoatAvoidance;
        Config.enableBoatAvoidance = true;
        try {
            Behavior<Villager> behavior = new AvoidBoatBehavior();
            long now = helper.getLevel().getGameTime();
            helper.assertTrue(behavior.tryStart(helper.getLevel(), villager, now),
                    "boat avoidance did not start for a boat one block away");
            WalkTarget ours = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
            helper.assertTrue(ours != null && OWNER.equals(BrainMovementIntent.ownerOf(ours)),
                    "boat avoidance did not publish its own walk target");

            WalkTarget newer = new WalkTarget(helper.absolutePos(new BlockPos(3, 1, 3)), 0.6F, 1);
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, newer);
            behavior.tickOrStop(helper.getLevel(), villager, now + 1);

            helper.assertTrue(behavior.getStatus() == Behavior.Status.STOPPED,
                    "boat avoidance kept running over a newer walk target");
            helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null) == newer,
                    "stopping boat avoidance erased or replaced a newer walk target");
            helper.assertFalse(BrainMovementIntent.isPublishedIntent(
                            villager.getBrain().getMemory(MemoryModuleType.LOOK_TARGET).orElse(null)),
                    "stopping boat avoidance left its look target behind");
        } finally {
            Config.enableBoatAvoidance = previous;
        }
        helper.succeed();
    }

    /** Component: a moving threat replaces the target instead of escaping toward the old side. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_boat_avoidance_intent")
    public static void boatAvoidanceReplacesItsTargetWhenTheBoatMoves(GameTestHelper helper) {
        installFloor(helper);
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 10, 1, 10);
        Boat boat = spawnBoat(helper, 12, 1, 10);
        boolean previous = Config.enableBoatAvoidance;
        Config.enableBoatAvoidance = true;
        try {
            Behavior<Villager> behavior = new AvoidBoatBehavior();
            long now = helper.getLevel().getGameTime();
            helper.assertTrue(behavior.tryStart(helper.getLevel(), villager, now),
                    "boat avoidance did not start");
            double firstX = target(villager).x;
            helper.assertTrue(firstX < villager.getX(),
                    "a boat east of the villager did not produce a westward target");

            Vec3 villagerPos = villager.position();
            boat.setPos(villagerPos.x - 2.0D, villagerPos.y, villagerPos.z);
            behavior.tickOrStop(helper.getLevel(), villager, now + 11);
            helper.assertTrue(behavior.getStatus() == Behavior.Status.RUNNING,
                    "boat avoidance stopped while the boat was still close");
            helper.assertTrue(target(villager).x > villager.getX(),
                    "a boat that moved west did not move the target east");
            behavior.doStop(helper.getLevel(), villager, now + 12);
        } finally {
            Config.enableBoatAvoidance = previous;
        }
        helper.succeed();
    }

    private static Vec3 target(Villager villager) {
        return villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                .map(walk -> walk.getTarget().currentPosition()).orElseThrow();
    }

    private static void installFloor(GameTestHelper helper) {
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static Boat spawnBoat(GameTestHelper helper, int x, int y, int z) {
//? if >=1.21.4 {
        /*return helper.spawn(EntityType.OAK_BOAT, x, y, z);
*///?} else {
        return helper.spawn(EntityType.BOAT, x, y, z);
 //?}
    }

    private static final class Progress {
        private final Villager villager;
        private final Boat boat;
        private final boolean previousConfig;
        private boolean published;
        private boolean finished;

        private Progress(Villager villager, Boat boat, boolean previousConfig) {
            this.villager = villager;
            this.boat = boat;
            this.previousConfig = previousConfig;
        }

        private void finish() {
            if (!finished) {
                finished = true;
                Config.enableBoatAvoidance = previousConfig;
            }
        }
    }
}
