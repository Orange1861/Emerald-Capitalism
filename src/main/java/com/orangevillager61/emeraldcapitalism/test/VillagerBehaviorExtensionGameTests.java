package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.behavior.VillagerBehaviorDefinition;
import com.orangevillager61.emeraldcapitalism.behavior.VillagerBehaviorLifecycle;
import com.orangevillager61.emeraldcapitalism.behavior.VillagerBehaviorRegistry;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestHandle;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestManager;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestSpec;
import com.orangevillager61.emeraldcapitalism.entity.ai.MovementRequestSubmission;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Proves that a test-only behavior can use the extension API without central changes. */
@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class VillagerBehaviorExtensionGameTests {

    private static final String TEST_ID = "emeraldcapitalism:test_extension_journey";
    private static final Map<Villager, MovementRequestHandle> HANDLES = new WeakHashMap<>();
    private static final Map<Villager, BlockPos> DESTINATIONS = new WeakHashMap<>();
    private static final VillagerBehaviorLifecycle LIFECYCLE = new VillagerBehaviorLifecycle() {
        @Override
        public void start(Villager villager) {
            submit(villager);
        }

        @Override
        public void cancel(Villager villager) {
            MovementRequestHandle handle = HANDLES.remove(villager);
            if (handle != null) {
                MovementRequestManager.cancel(villager, handle);
            }
            DESTINATIONS.remove(villager);
        }

        @Override
        public void tick(Villager villager) {
            MovementRequestHandle handle = HANDLES.get(villager);
            BlockPos destination = DESTINATIONS.get(villager);
            if (handle != null && destination != null) {
                MovementRequestSubmission submission = MovementRequestManager.maintain(
                        villager, handle, spec(
                        TEST_ID, "test-destination-v1", destination, 0.6F,
                        MovementRequestManager.PRIORITY_WORK));
                if (submission.accepted()) {
                    HANDLES.put(villager, submission.handle());
                } else {
                    HANDLES.remove(villager);
                }
            }
        }
    };
    private static final VillagerBehaviorDefinition TEST_DEFINITION = new VillagerBehaviorDefinition(
            TEST_ID,
            MovementRequestManager.PRIORITY_WORK,
            villager -> villager.getVillagerData().getProfession() == VillagerProfession.FARMER,
            EnumSet.of(Goal.Flag.MOVE),
            Set.of(MemoryModuleType.WALK_TARGET),
            VillagerBehaviorDefinition.InterruptionPolicy.RESUME_IF_ELIGIBLE,
            LIFECYCLE);

    private VillagerBehaviorExtensionGameTests() {
    }

    @GameTest(template = "empty_20x3x20", batch = "ecap_navigation_regressions", timeoutTicks = 500)
    public static void testOnlyBehaviorRegistersMovesRefreshesAndCleansUp(GameTestHelper helper) {
        installFloor(helper, 20);
        // Constrain only this disposable actor to a straight, loaded corridor
        // so unrelated ambient wandering cannot turn the extension journey
        // into a second navigation task.
        for (int x = 0; x < 20; x++) {
            helper.setBlock(new BlockPos(x, 1, 0), Blocks.STONE.defaultBlockState());
            helper.setBlock(new BlockPos(x, 2, 0), Blocks.STONE.defaultBlockState());
            helper.setBlock(new BlockPos(x, 1, 2), Blocks.STONE.defaultBlockState());
            helper.setBlock(new BlockPos(x, 2, 2), Blocks.STONE.defaultBlockState());
        }
        BlockPos jobSite = helper.absolutePos(new BlockPos(0, 1, 1));
        helper.setBlock(new BlockPos(0, 1, 1), Blocks.COMPOSTER.defaultBlockState());
        Villager villager = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        villager.refreshBrain(helper.getLevel());
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(helper.getLevel().dimension(), jobSite));
        // Keep this extension test focused on the brain-facing capability;
        // ordinary direct-navigation goals are unrelated competing producers
        // and are removed only from this disposable fixture.
        villager.goalSelector.removeAllGoals(ignored -> true);

        VillagerBehaviorRegistry.remove(TEST_ID);
        VillagerBehaviorRegistry.register(TEST_DEFINITION);
        VillagerBehaviorRegistry.refresh(villager);
        helper.runAfterDelay(499, () -> {
            VillagerBehaviorRegistry.remove(TEST_ID);
            HANDLES.remove(villager);
            DESTINATIONS.remove(villager);
        });
        helper.assertTrue(VillagerBehaviorRegistry.isBound(villager, TEST_ID),
                "test behavior did not bind exactly once");

        // A brain refresh/profession reconciliation is idempotent.
        VillagerBehaviorRegistry.refresh(villager);
        VillagerBehaviorRegistry.refresh(villager);
        helper.assertTrue(VillagerBehaviorRegistry.isBound(villager, TEST_ID),
                "repeated refresh duplicated the test behavior");

        MovementRequestHandle firstHandle = HANDLES.get(villager);
        helper.assertTrue(firstHandle != null && MovementRequestManager.isActive(villager, firstHandle),
                "test behavior did not own its movement request");
        awaitArrival(helper, villager, helper.absolutePos(new BlockPos(9, 1, 1)), 350);
    }

    @GameTest(template = "empty_3x3x3", batch = "ecap_navigation_regressions")
    public static void movementExecutorAdoptsMeaningfulChangesAndRejectsStaleCleanup(
            GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        BlockPos origin = villager.blockPosition();
        MovementRequestSubmission first = MovementRequestManager.submit(villager,
                spec("contract-owner", "same-target", origin, 0.5F,
                        MovementRequestManager.PRIORITY_WORK));
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        MovementRequestSubmission recovered = MovementRequestManager.maintain(
                villager, first.handle(),
                spec("contract-owner", "same-target", origin, 0.5F,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(recovered.accepted() && recovered.handle().equals(first.handle())
                        && villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
                "unchanged maintenance did not recover a missing WALK_TARGET");
        WalkTarget foreignWalk = new WalkTarget(new BlockPosTracker(origin.offset(0, 0, 1)),
                0.4F, 0);
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, foreignWalk);
        MovementRequestSubmission recoveredForeign = MovementRequestManager.maintain(
                villager, recovered.handle(),
                spec("contract-owner", "same-target", origin, 0.5F,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(recoveredForeign.accepted()
                        && recoveredForeign.handle().equals(first.handle())
                        && villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null)
                        != foreignWalk,
                "unchanged maintenance did not recover a foreign WALK_TARGET drift");
        MovementRequestSubmission speedChange = MovementRequestManager.submit(villager,
                spec("contract-owner", "same-target", origin, 0.8F,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(speedChange.outcome().name().equals("REPLACED")
                        && speedChange.handle().generation() > first.handle().generation(),
                "same-position speed change did not replace the executor request");

        MovementRequestSubmission maintainedChange = MovementRequestManager.maintain(
                villager, speedChange.handle(),
                spec("contract-owner", "same-target", origin, 0.9F,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(maintainedChange.outcome().name().equals("REPLACED")
                        && maintainedChange.handle().generation() > speedChange.handle().generation(),
                "active maintenance did not adopt a meaningful speed change");

        MovementRequestSubmission lookTarget = MovementRequestManager.submit(villager,
                new MovementRequestSpec("contract-owner", "same-target", origin, 0.9F, 0,
                        "test-arrival-v1", "ground-ladder-v1",
                        MovementRequestManager.PRIORITY_WORK, origin));
        MovementRequestSubmission noLookTarget = MovementRequestManager.submit(villager,
                spec("contract-owner", "same-target", origin, 0.9F,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(lookTarget.outcome().name().equals("REPLACED")
                        && noLookTarget.outcome().name().equals("REPLACED")
                        && !villager.getBrain().hasMemoryValue(MemoryModuleType.LOOK_TARGET),
                "look-target ownership was not cleared at a meaningful handoff");

        MovementRequestSubmission nearbyChange = MovementRequestManager.submit(villager,
                spec("contract-owner", "nearby-target", origin.offset(1, 0, 0), 0.8F,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertTrue(nearbyChange.handle().generation() > speedChange.handle().generation(),
                "nearby destination identity change did not advance the request generation");

        MovementRequestSubmission rejected = MovementRequestManager.submit(villager,
                spec("contract-lower", "rejected", origin, 0.4F,
                        MovementRequestManager.PRIORITY_AMBIENT));
        helper.assertTrue(!rejected.accepted() && rejected.handle() == null,
                "rejected request exposed a usable owner handle");
        helper.assertTrue(MovementRequestManager.isActive(villager, nearbyChange.handle()),
                "rejected request displaced the active owner");

        MovementRequestSubmission threat = MovementRequestManager.submit(villager,
                spec("contract-threat", "same-position-threat", origin, 1.3F,
                        MovementRequestManager.PRIORITY_ESCAPE));
        MovementRequestSubmission staleMaintenance = MovementRequestManager.maintain(
                villager, nearbyChange.handle(),
                spec("contract-owner", "nearby-target", origin.offset(1, 0, 0), 0.8F,
                        MovementRequestManager.PRIORITY_WORK));
        helper.assertFalse(staleMaintenance.accepted(),
                "stale maintenance reclaimed a newer threat request");
        helper.assertTrue(!MovementRequestManager.cancel(villager, nearbyChange.handle()),
                "stale task cleanup canceled a newer threat request");
        helper.assertTrue(MovementRequestManager.isActive(villager, threat.handle()),
                "newer threat request was not retained after stale cleanup");
        helper.assertTrue(MovementRequestManager.cancel(villager, threat.handle()),
                "current threat request did not clean up through its owner token");

        MovementRequestSubmission manualLookOwner = MovementRequestManager.submit(villager,
                new MovementRequestSpec("manual-look-owner", "same-position", origin, 0.8F, 0,
                        "test-arrival-v1", "ground-ladder-v1",
                        MovementRequestManager.PRIORITY_WORK, null));
        helper.assertTrue(MovementRequestManager.claimLookTarget(villager, manualLookOwner.handle(),
                        new BlockPosTracker(origin)),
                "ownership-aware manual look claim failed");
        MovementRequestSubmission manualLookHandoff = MovementRequestManager.submit(villager,
                spec("manual-look-handoff", "same-position", origin, 0.8F,
                        MovementRequestManager.PRIORITY_ESCAPE));
        helper.assertTrue(manualLookOwner.accepted() && manualLookHandoff.accepted()
                        && !villager.getBrain().hasMemoryValue(MemoryModuleType.LOOK_TARGET),
                "manual look-target ownership was not cleared during handoff");
        helper.assertTrue(MovementRequestManager.cancel(villager, manualLookHandoff.handle()),
                "manual look handoff owner did not cancel");

        MovementRequestSubmission foreignCleanupOwner = MovementRequestManager.submit(villager,
                spec("foreign-cleanup-owner", "foreign-cleanup", origin, 0.8F,
                        MovementRequestManager.PRIORITY_WORK));
        WalkTarget foreignWalkAfterSubmit = new WalkTarget(
                new BlockPosTracker(origin.offset(0, 0, 1)), 0.4F, 0);
        BlockPosTracker foreignLookAfterSubmit = new BlockPosTracker(origin.offset(0, 0, 1));
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, foreignWalkAfterSubmit);
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, foreignLookAfterSubmit);
        helper.assertTrue(MovementRequestManager.cancel(villager, foreignCleanupOwner.handle()),
                "foreign-state cleanup owner did not cancel");
        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null)
                        == foreignWalkAfterSubmit
                        && villager.getBrain().getMemory(MemoryModuleType.LOOK_TARGET).orElse(null)
                        == foreignLookAfterSubmit,
                "stale cleanup erased foreign brain memories");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3", batch = "ecap_navigation_regressions")
    public static void behaviorRegistryDistinguishesRestartAndCancel(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        String restartId = "emeraldcapitalism:test_restart_policy";
        String restartHighId = "emeraldcapitalism:test_restart_high";
        String cancelId = "emeraldcapitalism:test_cancel_policy";
        String cancelHighId = "emeraldcapitalism:test_cancel_high";
        AtomicInteger restartStarts = new AtomicInteger();
        AtomicInteger restartCancels = new AtomicInteger();
        AtomicInteger cancelStarts = new AtomicInteger();
        AtomicInteger cancelCancels = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean cancelEligible =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            VillagerBehaviorDefinition restart = definition(restartId, 10,
                    VillagerBehaviorDefinition.InterruptionPolicy.RESTART,
                    ignored -> restartStarts.incrementAndGet(),
                    ignored -> restartCancels.incrementAndGet());
            VillagerBehaviorDefinition restartHigh = definition(restartHighId, 100,
                    VillagerBehaviorDefinition.InterruptionPolicy.CANCEL,
                    ignored -> {}, ignored -> {});
            VillagerBehaviorDefinition cancel = new VillagerBehaviorDefinition(
                    cancelId, 20, ignored -> cancelEligible.get(), EnumSet.of(Goal.Flag.LOOK),
                    Set.of(MemoryModuleType.LOOK_TARGET),
                    VillagerBehaviorDefinition.InterruptionPolicy.CANCEL,
                    lifecycle(ignored -> cancelStarts.incrementAndGet(),
                            ignored -> cancelCancels.incrementAndGet()));
            VillagerBehaviorDefinition cancelHigh = definition(cancelHighId, 110,
                    VillagerBehaviorDefinition.InterruptionPolicy.CANCEL,
                    ignored -> {}, ignored -> {});

            VillagerBehaviorRegistry.register(restart);
            VillagerBehaviorRegistry.refresh(villager);
            VillagerBehaviorRegistry.register(restartHigh);
            VillagerBehaviorRegistry.refresh(villager);
            VillagerBehaviorRegistry.remove(restartHighId);
            VillagerBehaviorRegistry.refresh(villager);
            helper.assertTrue(restartStarts.get() == 2 && restartCancels.get() == 1,
                    "RESTART did not create a fresh eligible lifecycle after interruption");

            VillagerBehaviorRegistry.register(cancel);
            VillagerBehaviorRegistry.refresh(villager);
            VillagerBehaviorRegistry.register(cancelHigh);
            VillagerBehaviorRegistry.refresh(villager);
            VillagerBehaviorRegistry.remove(cancelHighId);
            VillagerBehaviorRegistry.refresh(villager);
            helper.assertTrue(cancelStarts.get() == 1 && cancelCancels.get() == 1
                            && !VillagerBehaviorRegistry.isBound(villager, cancelId),
                    "CANCEL unexpectedly resumed while eligibility remained true");
            helper.assertFalse(VillagerBehaviorRegistry.finish(villager, cancelId),
                    "finish unexpectedly completed a canceled tombstone");
            VillagerBehaviorRegistry.refresh(villager);
            helper.assertTrue(cancelStarts.get() == 1,
                    "finish removed the canceled tombstone while eligibility remained true");
            cancelEligible.set(false);
            VillagerBehaviorRegistry.refresh(villager);
            cancelEligible.set(true);
            VillagerBehaviorRegistry.refresh(villager);
            helper.assertTrue(cancelStarts.get() == 2,
                    "CANCEL did not become eligible for a later fresh start");
            helper.succeed();
        } finally {
            VillagerBehaviorRegistry.remove(restartHighId);
            VillagerBehaviorRegistry.remove(restartId);
            VillagerBehaviorRegistry.remove(cancelHighId);
            VillagerBehaviorRegistry.remove(cancelId);
        }
    }

    @GameTest(template = "empty_3x3x3", batch = "ecap_navigation_regressions")
    public static void behaviorRegistryEnforcesPriorityAndLifecycleTransitions(
            GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 1, 1);
        String lowId = "emeraldcapitalism:test_low_conflict";
        String highId = "emeraldcapitalism:test_high_conflict";
        AtomicInteger suspends = new AtomicInteger();
        AtomicInteger resumes = new AtomicInteger();

        VillagerBehaviorRegistry.remove(lowId);
        VillagerBehaviorRegistry.remove(highId);
        VillagerBehaviorDefinition low = new VillagerBehaviorDefinition(
                lowId, MovementRequestManager.PRIORITY_AMBIENT, ignored -> true,
                EnumSet.of(Goal.Flag.MOVE), Set.of(MemoryModuleType.WALK_TARGET),
                VillagerBehaviorDefinition.InterruptionPolicy.RESUME_IF_ELIGIBLE,
                new VillagerBehaviorLifecycle() {
                    @Override
                    public void suspend(Villager ignored) {
                        suspends.incrementAndGet();
                    }

                    @Override
                    public void resume(Villager ignored) {
                        resumes.incrementAndGet();
                    }
                });
        VillagerBehaviorDefinition high = new VillagerBehaviorDefinition(
                highId, MovementRequestManager.PRIORITY_ESCAPE, ignored -> true,
                EnumSet.of(Goal.Flag.MOVE), Set.of(MemoryModuleType.WALK_TARGET),
                VillagerBehaviorDefinition.InterruptionPolicy.CANCEL,
                new VillagerBehaviorLifecycle() {});

        try {
            VillagerBehaviorRegistry.register(low);
            VillagerBehaviorRegistry.refresh(villager);
            helper.assertTrue(VillagerBehaviorRegistry.isBound(villager, lowId),
                    "low-priority behavior did not bind initially");

            VillagerBehaviorRegistry.register(high);
            VillagerBehaviorRegistry.refresh(villager);
            helper.assertTrue(VillagerBehaviorRegistry.isBound(villager, highId)
                            && VillagerBehaviorRegistry.isBound(villager, lowId)
                            && suspends.get() == 1,
                    "higher-priority conflicting behavior did not suspend the lower owner");

            VillagerBehaviorRegistry.remove(highId);
            VillagerBehaviorRegistry.refresh(villager);
            helper.assertTrue(VillagerBehaviorRegistry.isBound(villager, lowId)
                            && resumes.get() == 1,
                    "suspended behavior did not resume after its conflict was removed");
            helper.assertTrue(VillagerBehaviorRegistry.finish(villager, lowId),
                    "behavior finish did not release its binding");
            helper.assertFalse(VillagerBehaviorRegistry.isBound(villager, lowId),
                    "finished behavior left a binding behind");
            helper.succeed();
        } finally {
            VillagerBehaviorRegistry.remove(highId);
            VillagerBehaviorRegistry.remove(lowId);
        }
    }

    private static VillagerBehaviorDefinition definition(
            String id, int priority, VillagerBehaviorDefinition.InterruptionPolicy policy,
            Consumer<Villager> start, Consumer<Villager> cancel) {
        return new VillagerBehaviorDefinition(
                id, priority, ignored -> true, EnumSet.of(Goal.Flag.LOOK),
                Set.of(MemoryModuleType.LOOK_TARGET), policy, lifecycle(start, cancel));
    }

    private static VillagerBehaviorLifecycle lifecycle(Consumer<Villager> start,
                                                        Consumer<Villager> cancel) {
        return new VillagerBehaviorLifecycle() {
            @Override
            public void start(Villager villager) {
                start.accept(villager);
            }

            @Override
            public void cancel(Villager villager) {
                cancel.accept(villager);
            }
        };
    }

    private static void awaitArrival(GameTestHelper helper, Villager villager,
                                     BlockPos target, int remainingTicks) {
        if (villager.blockPosition().distSqr(target) <= 2.25D) {
            MovementRequestHandle handle = HANDLES.get(villager);
            helper.assertTrue(handle != null && MovementRequestManager.isActive(villager, handle),
                    "test journey arrived without retaining its owner: profession="
                            + villager.getVillagerData().getProfession()
                            + " bound=" + VillagerBehaviorRegistry.isBound(villager, TEST_ID)
                            + " active=" + MovementRequestManager.activeHandle(villager));

            villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));
            VillagerBehaviorRegistry.refresh(villager);
            helper.assertFalse(VillagerBehaviorRegistry.isBound(villager, TEST_ID),
                    "profession change left a duplicate or orphaned behavior binding");
            helper.assertTrue(MovementRequestManager.activeHandle(villager) == null,
                    "profession change left the test movement request active");

            villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
            VillagerBehaviorRegistry.refresh(villager);
            helper.assertTrue(VillagerBehaviorRegistry.isBound(villager, TEST_ID),
                    "eligible profession did not restore one test behavior binding");
            VillagerBehaviorRegistry.remove(TEST_ID);
            HANDLES.remove(villager);
            DESTINATIONS.remove(villager);
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            VillagerBehaviorRegistry.remove(TEST_ID);
            HANDLES.remove(villager);
            DESTINATIONS.remove(villager);
            helper.fail("test-only extension behavior did not complete its normal brain journey: "
                    + villager.position());
            return;
        }
        helper.runAfterDelay(1, () -> awaitArrival(helper, villager, target, remainingTicks - 1));
    }

    private static void submit(Villager villager) {
        BlockPos destination = villager.blockPosition().offset(8, 0, 0);
        MovementRequestSpec spec = new MovementRequestSpec(
                TEST_ID,
                "test-destination-v1",
                destination,
                0.6F,
                0,
                "test-arrival-v1",
                "ground-ladder-v1",
                MovementRequestManager.PRIORITY_WORK,
                null);
        MovementRequestSubmission submission = MovementRequestManager.submit(villager, spec);
        if (submission.accepted()) {
            HANDLES.put(villager, submission.handle());
            DESTINATIONS.put(villager, destination);
        }
    }

    private static MovementRequestSpec spec(String owner, String identity, BlockPos destination,
                                            float speed, int priority) {
        return new MovementRequestSpec(owner, identity, destination, speed, 0,
                "test-arrival-v1", "ground-ladder-v1", priority, null);
    }

    private static void installFloor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
    }
}
