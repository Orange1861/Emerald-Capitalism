package com.orangevillager61.emeraldcapitalism.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceSchedulingTest {

    @Test
    void timingCountersRecordCallsAndDurations() {
        boolean previous = PerformanceTimingCounters.isDetailedTimingEnabled();
        PerformanceTimingCounters.Snapshot before = PerformanceTimingCounters.snapshot()
                .get(PerformanceTimingCounters.Operation.PUMPKIN_SEARCH);
        PerformanceTimingCounters.setDetailedTimingEnabled(true);
        try {
            assertEquals(7, PerformanceTimingCounters.measure(
                    PerformanceTimingCounters.Operation.PUMPKIN_SEARCH, () -> 7));

            PerformanceTimingCounters.Snapshot snapshot = PerformanceTimingCounters.snapshot()
                    .get(PerformanceTimingCounters.Operation.PUMPKIN_SEARCH);
            assertEquals(1L, snapshot.calls() - before.calls());
            assertTrue(snapshot.totalNanos() - before.totalNanos() >= 0L);
            assertTrue(snapshot.maximumNanos() >= before.maximumNanos());
        } finally {
            PerformanceTimingCounters.setDetailedTimingEnabled(previous);
        }
    }

    @Test
    void sharedBudgetAllowsOnlyOneCategoryPerTickAndAlternates() {
        SharedScanGenerationBudget.BudgetState budget =
                new SharedScanGenerationBudget.BudgetState();

        assertTrue(budget.tryAcquire(10L, SharedScanGenerationBudget.WorkType.SCAN));
        assertFalse(budget.tryAcquire(10L, SharedScanGenerationBudget.WorkType.GENERATION));
        assertFalse(budget.tryAcquire(11L, SharedScanGenerationBudget.WorkType.SCAN));
        assertTrue(budget.tryAcquire(11L, SharedScanGenerationBudget.WorkType.GENERATION));
    }

    @Test
    void loneCategoryIsNotStarvedByAlternation() {
        SharedScanGenerationBudget.BudgetState budget =
                new SharedScanGenerationBudget.BudgetState();

        assertTrue(budget.tryAcquire(20L, SharedScanGenerationBudget.WorkType.SCAN));
        assertTrue(budget.tryAcquire(21L, SharedScanGenerationBudget.WorkType.SCAN));
        assertTrue(budget.tryAcquire(22L, SharedScanGenerationBudget.WorkType.SCAN));
    }

    @Test
    void observedContentionHandsTheNextTickToTheDeniedCategory() {
        SharedScanGenerationBudget.BudgetState budget =
                new SharedScanGenerationBudget.BudgetState();

        assertTrue(budget.tryAcquire(30L, SharedScanGenerationBudget.WorkType.SCAN));
        assertFalse(budget.tryAcquire(30L, SharedScanGenerationBudget.WorkType.GENERATION));
        assertFalse(budget.tryAcquire(31L, SharedScanGenerationBudget.WorkType.SCAN));
        assertTrue(budget.tryAcquire(31L, SharedScanGenerationBudget.WorkType.GENERATION));
        assertTrue(budget.tryAcquire(32L, SharedScanGenerationBudget.WorkType.SCAN));
    }
}
