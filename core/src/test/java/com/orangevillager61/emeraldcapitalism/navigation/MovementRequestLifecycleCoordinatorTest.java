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

        assertTrue(coordinator.register("farmer", 1L));
        assertTrue(coordinator.terminate("farmer", 1L,
                MovementRequestLifecycleCoordinator.TerminalReason.ARRIVED));
        assertFalse(coordinator.terminate("farmer", 1L,
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

        assertTrue(coordinator.register("manager", 1L));
        assertTrue(coordinator.register("foreign", 1L));
        assertTrue(coordinator.register("manager", 2L));
        assertFalse(coordinator.register("manager", 1L));

        assertTrue(coordinator.terminate("manager", 1L,
                MovementRequestLifecycleCoordinator.TerminalReason.REPLACED));
        assertTrue(coordinator.isActive("foreign", 1L));
        assertTrue(coordinator.isActive("manager", 2L));
        assertEquals(1L, coordinator.terminalCount(
                MovementRequestLifecycleCoordinator.TerminalReason.REPLACED));
    }
}
