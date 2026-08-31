package com.orangevillager61.emeraldcapitalism.client.presentation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankPresentationTest {
    @Test
    void queuesAccountsFirstAndSeparatesRegularAccounts() {
        List<BankPresentation.AccountRow> rows = BankPresentation.accountRows(List.of(
                new BankPresentation.AccountSnapshot("Regular", 4, false, -1),
                new BankPresentation.AccountSnapshot("Second", 0, true, 1),
                new BankPresentation.AccountSnapshot("First", -2, true, 0)
        ));

        assertEquals("First", rows.getFirst().account().name());
        assertEquals("Second", rows.get(1).account().name());
        assertTrue(rows.get(2).separator());
        assertEquals("Regular", rows.get(3).account().name());
    }

    @Test
    void formatsChestPositionsAndTruncationMarker() {
        assertEquals(List.of("1, 2, 3", "... (showing 1 of 2)"),
                BankPresentation.chestLines(List.of(new BankPresentation.ChestPosition(1, 2, 3)), 1, 2));
    }

    @Test
    void movesBankBlockedMarketEntriesAfterPlayerAffordabilityEntries() {
        List<String> sorted = BankPresentation.sortMarketEntries(
                List.of("affordable", "too-expensive", "no-stock", "low-opinion"),
                entry -> entry.equals("no-stock") || entry.equals("low-opinion"));

        assertEquals(List.of("affordable", "too-expensive", "no-stock", "low-opinion"), sorted);
    }

    @Test
    void putsLowOpinionMapAfterEveryOtherMarketEntry() {
        List<String> sorted = BankPresentation.sortMarketEntriesByPriority(
                List.of("affordable", "too-expensive", "no-stock", "low-opinion-map"),
                entry -> entry.equals("low-opinion-map") ? 2 : entry.equals("no-stock") ? 1 : 0);

        assertEquals(List.of("affordable", "too-expensive", "no-stock", "low-opinion-map"), sorted);
    }
}
