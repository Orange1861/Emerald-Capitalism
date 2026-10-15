package com.orangevillager61.emeraldcapitalism.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementRequestLifecycleCoordinatorTest {

    @Test
    void delayedCallbacksCannotCreateASecondTerminalTransition() {
        MovementRequestLifecycleCoordinator coordinator =
                new MovementRequestLifecycleCoordinator();

        assertTrue(coordinator.register(1L, "farmer", 1L));
        assertTrue(coordinator.terminate(1L, "farmer", 1L,
                MovementRequestLifecycleCoordinator.TerminalReason.ARRIVED));
        assertFalse(coordinator.terminate(1L, "farmer", 1L,
                MovementRequestLifecycleCoordinator.TerminalReason.ABANDONED));

        assertEquals(0, coordinator.activeCount());
        assertEquals(1, coordinator.terminalRequestCount());
        assertEquals(1L, coordinator.terminalCount(
                MovementRequestLifecycleCoordinator.TerminalReason.ARRIVED));
        assertEquals(0L, coordinator.terminalCount(
                MovementRequestLifecycleCoordinator.TerminalReason.ABANDONED));
    }

    @Test
    void generationsAndEqualTargetsAreIndependentKeys() {
        MovementRequestLifecycleCoordinator coordinator =
                new MovementRequestLifecycleCoordinator();

        assertTrue(coordinator.register(7L, "manager", 1L));
        assertTrue(coordinator.register(7L, "foreign", 1L));
        assertTrue(coordinator.register(7L, "manager", 2L));
        assertFalse(coordinator.register(7L, "manager", 1L));

        assertTrue(coordinator.terminate(7L, "manager", 1L,
                MovementRequestLifecycleCoordinator.TerminalReason.REPLACED));
        assertTrue(coordinator.isActive(7L, "foreign", 1L));
        assertTrue(coordinator.isActive(7L, "manager", 2L));
        assertEquals(1L, coordinator.terminalCount(
                MovementRequestLifecycleCoordinator.TerminalReason.REPLACED));
    }

    @Test
    void sameUuidNewEntitySessionCanStartGenerationOne() {
        MovementRequestLifecycleCoordinator coordinator =
                new MovementRequestLifecycleCoordinator();
        assertTrue(coordinator.register(101L, "same-uuid|work", 1L));
        assertTrue(coordinator.terminate(101L, "same-uuid|work", 1L,
                MovementRequestLifecycleCoordinator.TerminalReason.UNLOADED));

        assertTrue(coordinator.register(202L, "same-uuid|work", 1L));
        assertTrue(coordinator.isActive(202L, "same-uuid|work", 1L));
        assertFalse(coordinator.terminate(101L, "same-uuid|work", 1L,
                MovementRequestLifecycleCoordinator.TerminalReason.ABANDONED));
        assertEquals(1L, coordinator.terminalCount(
                MovementRequestLifecycleCoordinator.TerminalReason.UNLOADED));
    }

    @Test
    void unloadReloadAndDuplicateCleanupRemainExactlyOnce() {
        MovementRequestLifecycleCoordinator coordinator =
                new MovementRequestLifecycleCoordinator(2);
        assertTrue(coordinator.register(11L, "villager|work", 1L));
        assertTrue(coordinator.terminate(11L, "villager|work", 1L,
                MovementRequestLifecycleCoordinator.TerminalReason.UNLOADED));
        assertFalse(coordinator.terminate(11L, "villager|work", 1L,
                MovementRequestLifecycleCoordinator.TerminalReason.UNLOADED));
        assertFalse(coordinator.terminate(11L, "villager|work", 1L,
                MovementRequestLifecycleCoordinator.TerminalReason.SERVER_SHUTDOWN));

        assertTrue(coordinator.register(22L, "villager|work", 1L));
        assertTrue(coordinator.terminate(22L, "villager|work", 1L,
                MovementRequestLifecycleCoordinator.TerminalReason.SERVER_SHUTDOWN));
        assertFalse(coordinator.terminate(22L, "villager|work", 1L,
                MovementRequestLifecycleCoordinator.TerminalReason.SERVER_SHUTDOWN));
        assertEquals(2, coordinator.terminalRequestCount());
    }

    @Test
    void terminalHistoryIsBoundedWhileCumulativeCountsRemainExact() {
        MovementRequestLifecycleCoordinator coordinator =
                new MovementRequestLifecycleCoordinator(3);
        for (long generation = 1L; generation <= 20L; generation++) {
            assertTrue(coordinator.register(99L, "bounded", generation));
            assertTrue(coordinator.terminate(99L, "bounded", generation,
                    MovementRequestLifecycleCoordinator.TerminalReason.FAILED));
        }
        assertEquals(3, coordinator.terminalRequestCount());
        assertEquals(20L, coordinator.terminalCount(
                MovementRequestLifecycleCoordinator.TerminalReason.FAILED));
    }
}
