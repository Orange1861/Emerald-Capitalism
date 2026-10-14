package com.orangevillager61.emeraldcapitalism.test;

import com.mojang.logging.LogUtils;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestSpec;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestSubmission;
import com.orangevillager61.emeraldcapitalism.util.EntityCreation;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Map;

/** Isolated lifecycle coverage because ServerStoppingEvent clears server-wide state. */
@GameTestHolder("emeraldcapitalism_shutdown")
@PrefixGameTestTemplate(false)
public final class ZzzServerStopLifecycleGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ShutdownExpectation shutdownExpectation;

    private ZzzServerStopLifecycleGameTests() {
    }

    @GameTest(template = "empty_20x3x20", batch = "actual_server_stop", timeoutTicks = 80)
    public static void serverStoppingEventCountsActiveRequestsExactlyOnce(GameTestHelper helper) {
        boolean previousEvents = PerformanceTimingCounters.isEventCountingEnabled();
        boolean armed = false;
        try {
            PerformanceTimingCounters.setEventCountingEnabled(true);
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before =
                    PerformanceTimingCounters.snapshot();
            ServerLevel level = helper.getLevel();
            BlockPos villagerPos = helper.absolutePos(new BlockPos(50, 1, 50));
            level.getChunk(villagerPos);
            level.setBlockAndUpdate(villagerPos.below(), Blocks.STONE.defaultBlockState());
            Villager villager = EntityCreation.create(EntityType.VILLAGER, level);
            helper.assertTrue(villager != null, "server-stop fixture could not create its villager");
            if (villager == null) {
                return;
            }
            villager.moveTo(villagerPos.getX() + 0.5D, villagerPos.getY(),
                    villagerPos.getZ() + 0.5D, 0.0F, 0.0F);
            villager.setNoAi(true);
            if (!level.addFreshEntity(villager)) {
                helper.fail("server-stop fixture villager could not be added to the level");
                return;
            }
            MovementRequestSubmission request = MovementRequestManager.submit(villager,
                    new MovementRequestSpec("regression:actual-server-stop", "pending",
                            villagerPos.offset(8, 0, 0), 0.6F, 0,
                            "arrival-v1", "ground-v1", MovementRequestManager.PRIORITY_WORK, null));
            helper.assertTrue(request.accepted(), "server-stop fixture request was rejected");
            long expectedShutdowns = MovementRequestManager.activeRequestCount();
            helper.assertValueEqual(expectedShutdowns, 1L,
                    "server-stop fixture did not leave exactly one movement request active");
            if (!request.accepted() || expectedShutdowns != 1L) {
                return;
            }

            shutdownExpectation = new ShutdownExpectation(before, expectedShutdowns, previousEvents);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,
                    (ServerStoppingEvent event) -> verifyActualServerStop());
            helper.succeed();
            armed = true;
        } finally {
            if (!armed) {
                shutdownExpectation = null;
                PerformanceTimingCounters.setEventCountingEnabled(previousEvents);
            }
        }
    }

    private static void verifyActualServerStop() {
        ShutdownExpectation expectation = shutdownExpectation;
        if (expectation == null) {
            return;
        }

        try {
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> after =
                    PerformanceTimingCounters.snapshot();
            long shutdownDelta = delta(after, expectation.before(),
                    PerformanceTimingCounters.Operation.MOVEMENT_SHUTDOWN);
            long activeRequests = MovementRequestManager.activeRequestCount();
            if (shutdownDelta != expectation.activeRequests() || activeRequests != 0L) {
                throw new IllegalStateException("Actual ServerStoppingEvent accounting mismatch: expected="
                        + expectation.activeRequests() + ", shutdownDelta=" + shutdownDelta
                        + ", activeAfterCleanup=" + activeRequests
                        + ", unloadedDelta=" + delta(after, expectation.before(),
                        PerformanceTimingCounters.Operation.MOVEMENT_UNLOADED));
            }
            if (delta(after, expectation.before(), PerformanceTimingCounters.Operation.MOVEMENT_UNLOADED) != 0L) {
                throw new IllegalStateException("Server-stop fixture was unloaded before actual shutdown");
            }
            LOGGER.info("[ECAP][Test] actual_server_stop_passed activeRequests={} shutdownDelta={}",
                    expectation.activeRequests(), shutdownDelta);
        } finally {
            shutdownExpectation = null;
            PerformanceTimingCounters.setEventCountingEnabled(expectation.previousEvents());
        }
    }

    private record ShutdownExpectation(
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before,
            long activeRequests,
            boolean previousEvents) {
    }

    private static long delta(
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> after,
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before,
            PerformanceTimingCounters.Operation operation) {
        return after.get(operation).calls() - before.get(operation).calls();
    }
}
