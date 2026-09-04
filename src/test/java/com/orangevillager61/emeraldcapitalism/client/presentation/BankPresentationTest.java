package com.orangevillager61.emeraldcapitalism.client.presentation;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankPresentationTest {
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

    @Test
    void usesTieBreakerToKeepPriorityGroupsDeterministic() {
        List<String> sorted = BankPresentation.sortMarketEntriesByPriority(
                List.of("pumpkin", "bread", "emerald_ore"), entry -> 0, Comparator.naturalOrder());

        assertEquals(List.of("bread", "emerald_ore", "pumpkin"), sorted);
    }
}
