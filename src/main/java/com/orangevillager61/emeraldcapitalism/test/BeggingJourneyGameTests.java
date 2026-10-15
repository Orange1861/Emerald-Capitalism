package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestTerminationReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Isolated scheduled journey coverage for the production begging behavior. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class BeggingJourneyGameTests {

    private BeggingJourneyGameTests() {
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_begging_journey", timeoutTicks = 1_000)
    public static void starvingVillagerBegsAndReceivesFood(GameTestHelper helper) {
        JourneyProgress progress = new JourneyProgress();
        try {
            for (int x = 0; x < 20; x++) {
                for (int z = 0; z < 20; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                }
            }
            Villager beggar = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
            // Keep the donor at the measured hand-off position. Its ordinary
            // inventory/brain state is still used, but autonomous wandering
            // after the transfer would make the movement-leg assertion
            // nondeterministic.
            Villager donor = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 8, 1, 1);
            VillagerStatsAttachment beggarStats =
                    beggar.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
            VillagerStatsAttachment donorStats =
                    donor.getData(EmeraldCapitalismAttachments.VILLAGER_STATS);
            long gameTime = helper.getLevel().getGameTime();

            beggarStats.setHungerLevel(0);
            beggarStats.setLastBegTime(Math.max(0L, gameTime - 600L));
            donorStats.setHungerLevel(20);
            donor.getInventory().addItem(new ItemStack(Items.BREAD, 10));
            setVisibleDonor(helper, beggar, donor);
            // The attachment deliberately rejects negative persisted game
            // times. Let the world advance past the 600-tick cooldown rather
            // than manufacturing an invalid timestamp at game time zero.
            helper.runAfterDelay(601, () -> awaitScheduledTransfer(helper, progress,
                    beggar, donor, beggarStats, 350));
        } catch (RuntimeException | Error failure) {
            progress.finish();
            throw failure;
        }
    }

    private static void awaitScheduledTransfer(GameTestHelper helper, JourneyProgress progress,
                                               Villager beggar, Villager donor,
                                               VillagerStatsAttachment beggarStats,
                                               int remainingTicks) {
        try {
            setVisibleDonor(helper, beggar, donor);
            double distanceToDonor = beggar.distanceToSqr(donor);
            progress.closestDonorDistanceSq = Math.min(progress.closestDonorDistanceSq,
                    distanceToDonor);
            if (distanceToDonor <= 9.0D) {
                progress.reachedDonor = true;
            }
            if (MovementRequestManager.terminalCount(beggar,
                    MovementRequestTerminationReason.ARRIVED) >= 1L) {
                progress.reachedDonor = true;
            }
            // The production behavior transfers only from its interaction
            // branch. This durable timestamp covers the case where the test
            // callback runs after the villager has already walked away again.
            if (beggarStats.getLastBegTime() > 0L) {
                progress.reachedDonor = true;
            }
            if (countItem(donor, Items.BREAD) == 5) {
                helper.assertTrue(beggarStats.getBegDonorUUID() == null,
                        "successful begging left stale donor state");
                helper.assertTrue(progress.reachedDonor,
                        "begging transferred food without completing the donor movement leg; "
                                + "currentDistanceSq=" + distanceToDonor
                                + ", closestDistanceSq=" + progress.closestDonorDistanceSq
                                + ", arrivalTerminals=" + MovementRequestManager.terminalCount(
                                        beggar, MovementRequestTerminationReason.ARRIVED)
                                + ", donorPos=" + donor.position());
                helper.assertTrue(beggarStats.getLastBegTime() > 0L,
                        "scheduled begging did not record its completed interaction");
                progress.finish();
                helper.succeed();
                return;
            }
            if (remainingTicks <= 0) {
                progress.finish();
                helper.fail("scheduled begging did not transfer food; beggar=" + beggar.position()
                        + ", donor=" + donor.position()
                        + ", donorBread=" + countItem(donor, Items.BREAD)
                        + ", request=" + com.orangevillager61.emeraldcapitalism.entity.ai
                        .MovementRequestManager.activeHandle(beggar));
                return;
            }
            helper.runAfterDelay(1, () -> awaitScheduledTransfer(helper, progress,
                    beggar, donor, beggarStats, remainingTicks - 1));
        } catch (RuntimeException | Error failure) {
            progress.finish();
            throw failure;
        }
    }

    private static void setVisibleDonor(GameTestHelper helper, Villager beggar, Villager donor) {
        beggar.getBrain().setMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,
//? if >=1.21.4 {
                /*new NearestVisibleLivingEntities(helper.getLevel(), beggar, List.<LivingEntity>of(donor)));
*///?} else {
                new NearestVisibleLivingEntities(beggar, List.<LivingEntity>of(donor)));
 //?}
    }

    private static int countItem(Villager villager, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static final class JourneyProgress {
        private boolean finished;
        private boolean reachedDonor;
        private double closestDonorDistanceSq = Double.POSITIVE_INFINITY;

        private void finish() {
            if (!finished) {
                finished = true;
            }
        }
    }
}
