package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestSpec;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestSubmission;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestTerminationReason;
import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/** Safe integration coverage for the synchronous server-stop cleanup boundary. */
@GameTestHolder("emeraldcapitalism_shutdown")
@PrefixGameTestTemplate(false)
public final class ZzzServerStopLifecycleGameTests {

    private ZzzServerStopLifecycleGameTests() {
    }

    /**
     * Mirrors the production ServerStoppingEvent call synchronously. All
     * assertions run before success is reported, and a second cleanup proves
     * that no late callback can add another terminal transition.
     */
    @GameTest(template = "empty_20x3x20", batch = "server_stop_cleanup", timeoutTicks = 80)
    public static void serverStopCleanupIsSynchronousAndIdempotent(GameTestHelper helper) {
        boolean previousEvents = PerformanceTimingCounters.isEventCountingEnabled();
        PerformanceTimingCounters.setEventCountingEnabled(true);
        Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before =
                PerformanceTimingCounters.snapshot();
        try {
            Villager first = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
            Villager second = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 3, 1, 1);
            MovementRequestSubmission firstRequest = MovementRequestManager.submit(first,
                    spec("regression:server-stop:first", first.blockPosition().offset(8, 0, 0)));
            MovementRequestSubmission secondRequest = MovementRequestManager.submit(second,
                    spec("regression:server-stop:second", second.blockPosition().offset(8, 0, 0)));
            helper.assertTrue(firstRequest.accepted() && secondRequest.accepted(),
                    "server-stop fixture requests were rejected");
            helper.assertValueEqual(MovementRequestManager.activeRequestCount(), 2L,
                    "server-stop fixture did not publish two active requests");

            // This is the exact manager boundary used by EmeraldCapitalism.onServerStopping.
            MovementRequestManager.clearAll(MovementRequestTerminationReason.SERVER_SHUTDOWN);
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> after =
                    PerformanceTimingCounters.snapshot();
            helper.assertValueEqual(MovementRequestManager.activeRequestCount(), 0L,
                    "server-stop cleanup left active requests behind");
            helper.assertValueEqual(delta(after, before,
                            PerformanceTimingCounters.Operation.MOVEMENT_SHUTDOWN), 2L,
                    "server-stop cleanup did not terminally account each request once");
            helper.assertValueEqual(delta(after, before,
                            PerformanceTimingCounters.Operation.MOVEMENT_UNLOADED), 0L,
                    "server-stop cleanup misclassified requests as entity unloads");

            MovementRequestManager.clearAll(MovementRequestTerminationReason.SERVER_SHUTDOWN);
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> repeated =
                    PerformanceTimingCounters.snapshot();
            helper.assertValueEqual(delta(repeated, after,
                            PerformanceTimingCounters.Operation.MOVEMENT_SHUTDOWN), 0L,
                    "repeated server-stop cleanup changed terminal counters");
            helper.succeed();
        } finally {
            MovementRequestManager.clearAll();
            PerformanceTimingCounters.setEventCountingEnabled(previousEvents);
        }
    }

    private static MovementRequestSpec spec(String owner, BlockPos destination) {
        return new MovementRequestSpec(owner, "server-stop", destination, 0.6F, 0,
                "arrival-v1", "ground-v1", MovementRequestManager.PRIORITY_WORK, null);
    }

    private static long delta(
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> after,
            Map<PerformanceTimingCounters.Operation, PerformanceTimingCounters.Snapshot> before,
            PerformanceTimingCounters.Operation operation) {
        return after.get(operation).calls() - before.get(operation).calls();
    }
}
