package com.orangevillager61.emeraldcapitalism.client.presentation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Pure preparation for bank account and linked-chest display rows. */
public final class BankPresentation {
    private BankPresentation() {
    }

    public record AccountSnapshot(String name, int balance) {
    }

    public record ChestPosition(int x, int y, int z) {
        @Override
        public String toString() {
            return x + ", " + y + ", " + z;
        }
    }

    /** Keeps player-only affordability failures ahead of bank-blocked offers. */
    public static <T> List<T> sortMarketEntries(List<T> entries,
                                                Predicate<T> unavailableForBank) {
        List<T> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(entry -> unavailableForBank.test(entry) ? 1 : 0));
        return List.copyOf(sorted);
    }

    /** Sorts market entries by priority while preserving the original order within each priority. */
    public static <T> List<T> sortMarketEntriesByPriority(List<T> entries,
                                                          ToIntFunction<T> priority) {
        return sortMarketEntriesByPriority(entries, priority, (left, right) -> 0);
    }

    /** Sorts market entries by priority with a deterministic tie-breaker. */
    public static <T> List<T> sortMarketEntriesByPriority(List<T> entries,
                                                          ToIntFunction<T> priority,
                                                          Comparator<T> tieBreaker) {
        List<T> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(priority).thenComparing(tieBreaker));
        return List.copyOf(sorted);
    }

    public static List<String> chestLines(List<ChestPosition> positions, int shownLimit, int totalCount) {
        List<String> lines = positions.stream().map(ChestPosition::toString).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (positions.size() < totalCount) {
            lines.add("... (showing " + shownLimit + " of " + totalCount + ")");
        }
        return List.copyOf(lines);
    }
}
