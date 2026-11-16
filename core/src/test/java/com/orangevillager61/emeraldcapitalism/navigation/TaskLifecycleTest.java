package com.orangevillager61.emeraldcapitalism.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskLifecycleTest {

    private static TaskLifecycle started() {
        TaskLifecycle task = new TaskLifecycle();
        task.start();
        return task;
    }

    @Test
    void routeArrivalMovesOnToInteractionAndNeverCompletesTheTask() {
        TaskLifecycle task = started();
        assertTrue(task.advance(TaskStage.CLAIM));
        assertTrue(task.advance(TaskStage.MOVE));

        assertTrue(task.routeArrived());

        assertEquals(TaskStage.INTERACT, task.stage());
        assertFalse(task.stage().terminal());
    }

    @Test
    void routeArrivalOutsideTheMoveStageDoesNothing() {
        TaskLifecycle task = started();
        assertFalse(task.routeArrived());
        assertEquals(TaskStage.ACQUIRE, task.stage());
    }

    @Test
    void completeIsOnlyLegalAfterInteraction() {
        TaskLifecycle task = started();
        assertFalse(task.complete());
        task.advance(TaskStage.CLAIM);
        assertFalse(task.complete());
        task.advance(TaskStage.MOVE);
        assertFalse(task.complete(), "a task that is still walking has not finished the job");
        task.routeArrived();
        assertTrue(task.complete());
        assertEquals(TaskStage.COMPLETE, task.stage());
    }

    @Test
    void stagesCannotBeSkipped() {
        TaskLifecycle task = started();
        assertFalse(task.advance(TaskStage.INTERACT));
        assertFalse(task.advance(TaskStage.COMPLETE));
        assertEquals(TaskStage.ACQUIRE, task.stage());
    }

    @Test
    void aTaskThatWasNeverStartedAdvancesNowhere() {
        TaskLifecycle task = new TaskLifecycle();
        assertFalse(task.advance(TaskStage.CLAIM));
        assertFalse(task.interrupt(TaskInterruption.PANIC));
    }

    @Test
    void terminalStagesAcceptNothing() {
        TaskLifecycle task = started();
        task.advance(TaskStage.CLAIM);
        task.advance(TaskStage.INTERACT);
        task.complete();
        assertFalse(task.advance(TaskStage.MOVE));
        assertFalse(task.interrupt(TaskInterruption.PANIC));
        assertEquals(TaskStage.COMPLETE, task.stage());
    }

    @Test
    void everyInterruptionHasTheDocumentedEffect() {
        for (TaskInterruption reason : TaskInterruption.values()) {
            TaskLifecycle task = started();
            task.advance(TaskStage.CLAIM);
            assertTrue(task.interrupt(reason));
            assertEquals(reason.resultingStage(), task.stage(), reason.name());
            assertEquals(reason, task.lastInterruption());
        }
        assertEquals(TaskStage.SUSPEND, TaskInterruption.PANIC.resultingStage());
        assertEquals(TaskStage.SUSPEND, TaskInterruption.SLEEP.resultingStage());
        assertEquals(TaskStage.SUSPEND, TaskInterruption.TRADE.resultingStage());
        assertEquals(TaskStage.FAIL, TaskInterruption.UNREACHABLE.resultingStage());
        assertEquals(TaskStage.FAIL, TaskInterruption.TARGET_INVALIDATED.resultingStage());
        assertEquals(TaskStage.FAIL, TaskInterruption.TIMEOUT.resultingStage());
        assertEquals(TaskStage.ABANDON, TaskInterruption.UNLOAD.resultingStage());
        assertEquals(TaskStage.ABANDON, TaskInterruption.PROFESSION_CHANGE.resultingStage());
        assertEquals(TaskStage.ABANDON, TaskInterruption.DEATH.resultingStage());
    }

    @Test
    void resumingReacquiresUnderANewGeneration() {
        TaskLifecycle task = started();
        long first = task.generation();
        task.advance(TaskStage.CLAIM);
        task.interrupt(TaskInterruption.PANIC);

        long second = task.resume();

        assertNotEquals(first, second);
        assertEquals(TaskStage.ACQUIRE, task.stage());
        assertEquals(second, task.generation());
    }

    @Test
    void onlyASuspendedTaskResumes() {
        TaskLifecycle task = started();
        assertThrows(IllegalStateException.class, task::resume);
        task.advance(TaskStage.CLAIM);
        task.interrupt(TaskInterruption.UNLOAD);
        assertThrows(IllegalStateException.class, task::resume);
    }

    @Test
    void anInterruptedSuspendedTaskStaysSuspended() {
        TaskLifecycle task = started();
        task.interrupt(TaskInterruption.PANIC);
        assertTrue(task.interrupt(TaskInterruption.SLEEP));
        assertEquals(TaskStage.SUSPEND, task.stage());
        assertEquals(TaskInterruption.SLEEP, task.lastInterruption());
    }

    @Test
    void ordinaryClaimsAreReleasedByEveryInterruption() {
        for (TaskInterruption reason : TaskInterruption.values()) {
            assertEquals(TaskInterruption.ClaimDisposition.RELEASE, reason.dispositionFor(false),
                    reason.name());
        }
    }

    @Test
    void durableClaimsAreNeverReleasedByAnInterruption() {
        for (TaskInterruption reason : TaskInterruption.values()) {
            assertNotEquals(TaskInterruption.ClaimDisposition.RELEASE, reason.dispositionFor(true),
                    reason.name());
        }
        assertEquals(TaskInterruption.ClaimDisposition.KEEP,
                TaskInterruption.PANIC.dispositionFor(true));
        assertEquals(TaskInterruption.ClaimDisposition.KEEP,
                TaskInterruption.FULL_INVENTORY.dispositionFor(true));
        assertEquals(TaskInterruption.ClaimDisposition.KEEP,
                TaskInterruption.NO_FUEL.dispositionFor(true));
        assertEquals(TaskInterruption.ClaimDisposition.HAND_TO_VILLAGE,
                TaskInterruption.UNLOAD.dispositionFor(true));
        assertEquals(TaskInterruption.ClaimDisposition.HAND_TO_VILLAGE,
                TaskInterruption.DEATH.dispositionFor(true));
        assertEquals(TaskInterruption.ClaimDisposition.HAND_TO_VILLAGE,
                TaskInterruption.PROFESSION_CHANGE.dispositionFor(true));
    }
}
