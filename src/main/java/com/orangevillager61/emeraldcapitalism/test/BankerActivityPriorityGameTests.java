package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.behavior.BankerSchedule;
import com.orangevillager61.emeraldcapitalism.behavior.BankerWorkSideBehavior;
import com.orangevillager61.emeraldcapitalism.behavior.BrainMovementIntent;
import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.registry.ECAPVillagerProfessions;
import com.orangevillager61.emeraldcapitalism.test.BankerWorkSideGameTests.Site;
import com.orangevillager61.emeraldcapitalism.util.EntityCreation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.schedule.Schedule;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Step 4 (Phase 13) priority tests on the banker: a mod WORK behavior yields to vanilla's panic,
 * raid, rest and trading, stops exactly once with no movement intent left behind, resumes after,
 * and holds its place through the bell hours. Journey tests are natural AI after fixture setup;
 * component tests are labeled.
 */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class BankerActivityPriorityGameTests {

    private static final String BANKER_INTENT = "emeraldcapitalism:banker_work_side";
    private static final long BELL_TIME = 9_200L;
    private static final long REST_TIME = 13_000L;
    private static final int ARRIVAL_LIMIT = 500;

    private BankerActivityPriorityGameTests() {
    }

    /** Component: the schedule is vanilla's with WORK in place of MEET. */
    @GameTest(template = "empty_3x3x3")
    public static void bankerScheduleWorksTheBellHoursAndKeepsTheRest(GameTestHelper helper) {
        Schedule vanilla = Schedule.VILLAGER_DEFAULT;
        for (int time : new int[] {10, 1_999, 2_000, 8_999, 9_000, 9_500, 10_999, 11_000, 11_999, 12_000, 23_000}) {
            Activity expected = vanilla.getActivityAt(time) == Activity.MEET
                    ? Activity.WORK : vanilla.getActivityAt(time);
            helper.assertValueEqual(BankerSchedule.SCHEDULE.getActivityAt(time), expected,
                    "banker schedule differs from vanilla's other than at the bell hours: day time " + time);
        }
        helper.assertValueEqual(vanilla.getActivityAt(9_500), Activity.MEET,
                "control: vanilla's schedule no longer meets at 9500");
        helper.assertValueEqual(BankerSchedule.SCHEDULE.getActivityAt(9_500), Activity.WORK,
                "the banker is not working at the bell hours");
        helper.succeed();
    }

    /** Natural AI: through the bell hours the banker stays and works instead of walking to the bell. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_banker_activity", timeoutTicks = 1_400)
    public static void bankerHoldsTheWorkSideThroughTheBellHours(GameTestHelper helper) {
        Run run = new Run(helper, BELL_TIME);
        BankerWorkSideGameTests.installFloor(helper);
        helper.setBlock(new BlockPos(17, 1, 17), Blocks.BELL.defaultBlockState());
        Site site = BankerWorkSideGameTests.site(helper, new BlockPos(10, 1, 10), new BlockPos(3, 1, 3), 0, 19);
        // Fixture: the village's meeting point, which would pull an ordinary villager to the bell.
        site.banker().getBrain().setMemory(MemoryModuleType.MEETING_POINT,
                GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(17, 1, 17))));
        // A freshly built Brain keeps vanilla's schedule for up to 20 ticks until the reconcile runs
        // and may finish a MEET stroll first, so the hold is judged once the banker has been
        // working at the work side for 40 ticks in a row.
        int[] steady = {0};
        until(run, ARRIVAL_LIMIT, () -> {
            boolean working = atWorkSide(helper, site) && site.banker().getBrain().isActive(Activity.WORK);
            steady[0] = working ? steady[0] + 1 : 0;
            return steady[0] >= 40;
        }, "the banker did not settle at the work side as a worker",
                () -> during(run, 300, () -> {
                    if (!atWorkSide(helper, site)) {
                        return "the banker left the work side at the bell hours: " + site.banker().position();
                    }
                    if (site.banker().getBrain().isActive(Activity.MEET)) {
                        return "the banker was in the MEET activity";
                    }
                    return null;
                }, run::succeed));
    }

    /** Natural AI: panic while the banker is walking to the work side. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_banker_activity", timeoutTicks = 1_500)
    public static void panicStopsTheBankerWalkingToWorkAndWorkResumes(GameTestHelper helper) {
        panicScenario(helper, false);
    }

    /** Natural AI: panic while the banker is standing at the work side. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_banker_activity", timeoutTicks = 1_500)
    public static void panicStopsTheBankerAtTheWorkSideAndWorkResumes(GameTestHelper helper) {
        panicScenario(helper, true);
    }

    private static void panicScenario(GameTestHelper helper, boolean atWorkSide) {
        Run run = new Run(helper, BankerWorkSideGameTests.WORK_DAY_TIME);
        if (helper.getLevel().getDifficulty() == Difficulty.PEACEFUL) {
            run.fail("panic needs difficulty easy or higher");
            return;
        }
        BankerWorkSideGameTests.installFloor(helper);
        Site site = BankerWorkSideGameTests.site(helper, new BlockPos(10, 1, 10), new BlockPos(3, 1, 3), 0, 19);
        Runnable threat = () -> {
            Pillager pillager = EntityCreation.create(EntityType.PILLAGER, helper.getLevel());
            Villager banker = site.banker();
            pillager.moveTo(banker.getX() + 3.0D, banker.getY(), banker.getZ(), 0.0F, 0.0F);
            // Held and harmless: the banker reacts to it being there.
            pillager.setNoAi(true);
            pillager.setInvulnerable(true);
            pillager.setPersistenceRequired();
            helper.getLevel().addFreshEntity(pillager);
            until(run, 200, () -> banker.getBrain().isActive(Activity.PANIC),
                    "the banker did not panic at the hostile", () ->
                    afterActivityChange(run, () -> during(run, 40, () -> interruptionViolation(banker), () -> {
                        pillager.discard();
                        until(run, 700, () -> atWorkSide(helper, site)
                                        && banker.getBrain().isActive(Activity.WORK),
                                "the banker did not return to the work side after the threat left",
                                run::succeed);
                    })));
        };
        BooleanSupplier checkpoint = atWorkSide
                ? () -> atWorkSide(helper, site)
                : () -> bankerOwnsWalkTarget(site.banker());
        until(run, ARRIVAL_LIMIT, checkpoint, "the banker never reached the panic checkpoint", threat);
    }

    /** Natural AI: a trade interrupts the work side and the banker goes back afterwards. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_banker_activity", timeoutTicks = 1_400)
    public static void tradingStopsTheBankerWorkAndWorkResumes(GameTestHelper helper) {
        Run run = new Run(helper, BankerWorkSideGameTests.WORK_DAY_TIME);
        BankerWorkSideGameTests.installFloor(helper);
        Site site = BankerWorkSideGameTests.site(helper, new BlockPos(10, 1, 10), new BlockPos(3, 1, 3), 0, 19);
        Villager banker = site.banker();
        until(run, ARRIVAL_LIMIT, () -> atWorkSide(helper, site), "the banker did not reach the work side", () -> {
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.moveTo(banker.getX() + 1.0D, banker.getY(), banker.getZ(), 0.0F, 0.0F);
            banker.setTradingPlayer(player);
            // One tick lets the running behavior notice the trade and stop.
            helper.runAfterDelay(1, () -> during(run, 60, () -> {
                if (bankerOwnsWalkTarget(banker) || bankerBehaviorRunning(banker)) {
                    return "the banker kept working during a trade";
                }
                return null;
            }, () -> {
                banker.setTradingPlayer(null);
                until(run, 400, () -> atWorkSide(helper, site) && bankerBehaviorRunning(banker),
                        "the banker did not return to work after the trade", run::succeed);
            }));
        });
    }

    /** Natural AI: at rest hours the banker's work behavior ends and leaves nothing behind. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_banker_activity", timeoutTicks = 1_400)
    public static void restHoursStopTheBankerWork(GameTestHelper helper) {
        Run run = new Run(helper, BankerWorkSideGameTests.WORK_DAY_TIME);
        BankerWorkSideGameTests.installFloor(helper);
        Site site = BankerWorkSideGameTests.site(helper, new BlockPos(10, 1, 10), new BlockPos(3, 1, 3), 0, 19);
        Villager banker = site.banker();
        until(run, ARRIVAL_LIMIT, () -> atWorkSide(helper, site), "the banker did not reach the work side", () -> {
            helper.getLevel().setDayTime(REST_TIME);
            until(run, 60, () -> banker.getBrain().isActive(Activity.REST),
                    "the banker did not move to the REST activity", () ->
                    afterActivityChange(run, () -> during(run, 40, () -> interruptionViolation(banker), run::succeed)));
        });
    }

    /** Component: forcing the raid activity, as SetRaidStatus does, stops the banker's work once. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_banker_activity")
    public static void raidActivityStopsTheBankerWorkAndWorkResumes(GameTestHelper helper) {
        BankerWorkSideGameTests.installFloor(helper);
        ServerLevel level = helper.getLevel();
        long previousDayTime = level.getDayTime();
        level.setDayTime(BankerWorkSideGameTests.WORK_DAY_TIME);
        try {
            Site site = BankerWorkSideGameTests.site(helper, new BlockPos(10, 1, 10), new BlockPos(4, 1, 10), 0, 19);
            Villager banker = site.banker();
            banker.setNoAi(true);
            banker.moveTo(site.workPos().getX() + 0.5D, site.workPos().getY(), site.workPos().getZ() + 0.5D,
                    0.0F, 0.0F);
            BankerSchedule.apply(banker);
            banker.getBrain().setActiveActivityIfPossible(Activity.WORK);
            tick(banker, level, 3);
            helper.assertTrue(bankerBehaviorRunning(banker), "the banker's work behavior did not start");

            banker.getBrain().setActiveActivityIfPossible(Activity.RAID);
            tick(banker, level, 1);
            helper.assertFalse(bankerBehaviorRunning(banker),
                    "the banker's work behavior kept running in the RAID activity");
            helper.assertFalse(bankerOwnsWalkTarget(banker), "the banker left a walk target in the RAID activity");

            banker.getBrain().setActiveActivityIfPossible(Activity.WORK);
            tick(banker, level, 3);
            helper.assertTrue(bankerBehaviorRunning(banker), "the banker's work did not resume after the raid");
        } finally {
            level.setDayTime(previousDayTime);
        }
        helper.succeed();
    }

    /** Component: losing the profession stops the behavior and erases only its own walk target. */
    @GameTest(template = "empty_20x3x20", batch = "ecap_banker_activity")
    public static void professionChangeEndsTheBankerWork(GameTestHelper helper) {
        BankerWorkSideGameTests.installFloor(helper);
        ServerLevel level = helper.getLevel();
        long previousDayTime = level.getDayTime();
        level.setDayTime(BankerWorkSideGameTests.WORK_DAY_TIME);
        try {
            Site site = BankerWorkSideGameTests.site(helper, new BlockPos(10, 1, 10), new BlockPos(4, 1, 10), 0, 19);
            Villager banker = site.banker();
            banker.setNoAi(true);
            // A grounded mob can plan; an airborne one makes the sink report the target unreachable.
            banker.setOnGround(true);
            BankerSchedule.apply(banker);
            banker.getBrain().setActiveActivityIfPossible(Activity.WORK);
            tick(banker, level, 3);
            helper.assertTrue(bankerOwnsWalkTarget(banker),
                    "the banker did not publish a walk target to the work side");

            banker.setVillagerData(banker.getVillagerData().setProfession(VillagerProfession.NONE));
            banker.refreshBrain(level);
            helper.assertFalse(bankerOwnsWalkTarget(banker),
                    "the banker's walk target outlived its profession");
            helper.assertFalse(bankerBehaviorRunning(banker),
                    "the banker's work behavior outlived its profession");
            helper.assertTrue(banker.getVillagerData().getProfession() != ECAPVillagerProfessions.BANKER.get(),
                    "fixture error: the profession did not change");
        } finally {
            level.setDayTime(previousDayTime);
        }
        helper.succeed();
    }

    /** What an interrupted banker must not be doing: running, or owning a walk target. */
    private static String interruptionViolation(Villager banker) {
        if (bankerBehaviorRunning(banker)) {
            return "the banker's work behavior was still running during the interruption";
        }
        if (bankerOwnsWalkTarget(banker)) {
            return "the banker still owned a walk target during the interruption";
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static boolean bankerBehaviorRunning(Villager banker) {
        return banker.getBrain().getRunningBehaviors().stream()
                .anyMatch(behavior -> behavior instanceof BankerWorkSideBehavior);
    }

    private static boolean bankerOwnsWalkTarget(Villager banker) {
        return BANKER_INTENT.equals(BrainMovementIntent.ownerOf(
                banker.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null)));
    }

    private static boolean atWorkSide(GameTestHelper helper, Site site) {
        ServerLevel level = helper.getLevel();
        return BankBlock.isAtBankerWorkPos(level.getBlockState(site.bankPos()), site.bankPos(),
                site.banker().position());
    }

    private static void tick(Villager banker, ServerLevel level, int times) {
        for (int index = 0; index < times; index++) {
            banker.getBrain().tick(level, banker);
        }
    }

    /** Polls each tick until {@code condition} holds, then runs {@code next}. */
    private static void until(Run run, int remaining, BooleanSupplier condition, String failure, Runnable next) {
        if (run.done) {
            return;
        }
        if (condition.getAsBoolean()) {
            next.run();
            return;
        }
        if (remaining <= 0) {
            run.fail(failure);
            return;
        }
        run.helper.runAfterDelay(1, () -> guarded(run, () -> until(run, remaining - 1, condition, failure, next)));
    }

    /**
     * The Brain changes the active activity after its own tick, so a behavior from the old
     * activity stops on the next Brain tick; interruption checks start two ticks later.
     */
    private static void afterActivityChange(Run run, Runnable next) {
        run.helper.runAfterDelay(2, () -> guarded(run, next));
    }

    /** Checks {@code violation} each tick for {@code ticks} ticks, then runs {@code next}. */
    private static void during(Run run, int ticks, Supplier<String> violation, Runnable next) {
        if (run.done) {
            return;
        }
        String problem = violation.get();
        if (problem != null) {
            run.fail(problem);
            return;
        }
        if (ticks <= 0) {
            next.run();
            return;
        }
        run.helper.runAfterDelay(1, () -> guarded(run, () -> during(run, ticks - 1, violation, next)));
    }

    private static void guarded(Run run, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | Error failure) {
            run.restoreTime();
            throw failure;
        }
    }

    /** Restores the day time however a scenario ends. */
    private static final class Run {
        private final GameTestHelper helper;
        private final long previousDayTime;
        private boolean done;

        private Run(GameTestHelper helper, long dayTime) {
            this.helper = helper;
            this.previousDayTime = helper.getLevel().getDayTime();
            helper.getLevel().setDayTime(dayTime);
        }

        private void restoreTime() {
            helper.getLevel().setDayTime(previousDayTime);
        }

        private void succeed() {
            done = true;
            restoreTime();
            helper.succeed();
        }

        private void fail(String message) {
            done = true;
            restoreTime();
            helper.fail(message);
        }
    }
}
