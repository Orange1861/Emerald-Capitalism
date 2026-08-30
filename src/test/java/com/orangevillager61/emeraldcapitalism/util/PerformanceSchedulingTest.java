package com.orangevillager61.emeraldcapitalism.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceSchedulingTest {

    @AfterEach
    void clearCounters() {
        PerformanceTimingCounters.clear();
    }

    @Test
    void timingCountersRecordCallsAndDurations() {
        assertEquals(7, PerformanceTimingCounters.measure(
                PerformanceTimingCounters.Operation.PUMPKIN_SEARCH, () -> 7));

        PerformanceTimingCounters.Snapshot snapshot = PerformanceTimingCounters.snapshot()
                .get(PerformanceTimingCounters.Operation.PUMPKIN_SEARCH);
        assertEquals(1L, snapshot.calls());
        assertTrue(snapshot.totalNanos() >= 0L);
        assertTrue(snapshot.maximumNanos() >= 0L);
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
