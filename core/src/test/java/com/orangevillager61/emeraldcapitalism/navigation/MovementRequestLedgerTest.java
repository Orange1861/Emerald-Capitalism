package com.orangevillager61.emeraldcapitalism.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementRequestLedgerTest {

    @Test
    void unchangedIntentIsIdempotentButSpeedAndNearbyIdentityChangesAreMeaningful() {
        MovementRequestLedger ledger = new MovementRequestLedger();
        MovementIntent initial = intent("pumpkin-a", 0L, 0.6F, "work");

        MovementRequestLedger.Submission first = ledger.submit(initial);
        MovementRequestLedger.Submission unchanged = ledger.submit(initial);
        MovementRequestLedger.Submission speedChanged = ledger.submit(
                intent("pumpkin-a", 0L, 0.7F, "work"));
        MovementRequestLedger.Submission nearbyChanged = ledger.submit(
                intent("pumpkin-b", 1L, 0.7F, "work"));

        assertEquals(MovementRequestLedger.Outcome.ACCEPTED, first.outcome());
        assertEquals(MovementRequestLedger.Outcome.UNCHANGED, unchanged.outcome());
        assertEquals(first.handle(), unchanged.handle());
        assertEquals(MovementRequestLedger.Outcome.REPLACED, speedChanged.outcome());
        assertNotEquals(first.handle(), speedChanged.handle());
        assertEquals(MovementRequestLedger.Outcome.REPLACED, nearbyChanged.outcome());
        assertNotEquals(speedChanged.handle(), nearbyChanged.handle());
    }

    @Test
    void staleCleanupCannotClearNewerRequestAtTheSamePosition() {
        MovementRequestLedger ledger = new MovementRequestLedger();
        MovementRequestLedger.Handle oldHandle = ledger.submit(
                intent("pumpkin-a", 42L, 0.6F, "work")).handle();
        MovementRequestLedger.Handle newHandle = ledger.submit(
                intent("pumpkin-b", 42L, 0.6F, "work")).handle();

        assertFalse(ledger.clear(oldHandle));
        assertTrue(ledger.owns(newHandle));
        assertTrue(ledger.clear(newHandle));
    }

    @Test
    void lowerPriorityProducerCannotStealAnActiveJourney() {
        MovementRequestLedger ledger = new MovementRequestLedger();
        MovementRequestLedger.Handle work = ledger.submit(
                new MovementIntent("work", "target", 1L, Float.floatToIntBits(0.6F),
                        0, "work", "ground", 40)).handle();

        MovementRequestLedger.Submission ambient = ledger.submit(
                new MovementIntent("ambient", "target", 1L, Float.floatToIntBits(0.4F),
                        1, "ambient", "ground", 10));

        assertEquals(MovementRequestLedger.Outcome.REJECTED_LOWER_PRIORITY, ambient.outcome());
        assertEquals(null, ambient.handle());
        assertTrue(ledger.owns(work));
        assertThrows(NullPointerException.class, () -> ledger.clear(ambient.handle()));
        assertTrue(ledger.owns(work));
    }

    @Test
    void nullHandlesAndOwnersAreInvariantViolations() {
        MovementRequestLedger ledger = new MovementRequestLedger();

        assertThrows(NullPointerException.class,
                () -> ledger.clear((MovementRequestLedger.Handle) null));
        assertThrows(NullPointerException.class,
                () -> ledger.owns((MovementRequestLedger.Handle) null));
        assertThrows(NullPointerException.class, () -> ledger.clear((String) null, 1L));
        assertThrows(NullPointerException.class, () -> ledger.owns((String) null, 1L));
    }

    @Test
    void equalPriorityProducerCannotStealAnActiveJourney() {
        MovementRequestLedger ledger = new MovementRequestLedger();
        MovementRequestLedger.Handle work = ledger.submit(
                new MovementIntent("work", "target", 1L, Float.floatToIntBits(0.6F),
                        0, "work", "ground", 40)).handle();

        MovementRequestLedger.Submission equalPriority = ledger.submit(
                new MovementIntent("other", "target", 1L, Float.floatToIntBits(0.6F),
                        0, "other", "ground", 40));

        assertEquals(MovementRequestLedger.Outcome.REJECTED_NOT_HIGHER_PRIORITY,
                equalPriority.outcome());
        assertEquals(null, equalPriority.handle());
        assertTrue(ledger.owns(work));
    }

    private static MovementIntent intent(String identity, long destination, float speed,
                                         String policy) {
        return new MovementIntent("pumpkin", identity, destination,
                Float.floatToIntBits(speed), 0, policy, "ground", 40);
    }
}
