package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.attachments.EmeraldCapitalismAttachments;
import com.orangevillager61.emeraldcapitalism.attachments.VillagerStatsAttachment;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
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
import java.util.Map;

/** Isolated scheduled journey coverage for the production begging behavior. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class BeggingJourneyGameTests {

    private BeggingJourneyGameTests() {
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_begging_journey", timeoutTicks = 1_000)
    public static void starvingVillagerBegsAndReceivesFood(GameTestHelper helper) {
        Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before =
                PerformanceTimingCounters.snapshot();
        PerformanceTimingCounters.EventCountingScope eventScope =
                PerformanceTimingCounters.beginEventCountingScope();
        JourneyProgress progress = new JourneyProgress(eventScope, before);
        try {
            for (int x = 0; x < 20; x++) {
                for (int z = 0; z < 20; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                }
            }
            Villager beggar = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
            Villager donor = helper.spawn(EntityType.VILLAGER, 8, 1, 1);
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
            helper.runAfterDelay(1, () -> awaitScheduledTransfer(helper, progress,
                    beggar, donor, beggarStats, 900));
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
            if (countItem(donor, Items.BREAD) == 5) {
                helper.assertTrue(beggarStats.getBegDonorUUID() == null,
                        "successful begging left stale donor state");
                Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> after =
                        PerformanceTimingCounters.snapshot();
                helper.assertTrue(delta(after, progress.before,
                                PerformanceTimingCounters.Operation.MOVEMENT_ARRIVAL) >= 1L,
                        "begging did not complete its real donor movement leg");
                helper.assertTrue(delta(after, progress.before,
                                PerformanceTimingCounters.Operation.MOVEMENT_PATH_PLAN_ATTEMPT) >= 1L,
                        "scheduled begging did not use MoveToTargetSink path planning");
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

    private static long delta(
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> after,
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before,
            PerformanceTimingCounters.Operation operation) {
        return after.get(operation).calls() - before.get(operation).calls();
    }

    private static final class JourneyProgress {
        private final PerformanceTimingCounters.EventCountingScope eventScope;
        private final Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before;
        private boolean finished;

        private JourneyProgress(PerformanceTimingCounters.EventCountingScope eventScope,
                                Map<PerformanceTimingCounters.Operation,
                                        PerformanceTimingCounters.Snapshot> before) {
            this.eventScope = eventScope;
            this.before = before;
        }

        private void finish() {
            if (!finished) {
                finished = true;
                eventScope.close();
            }
        }
    }
}
