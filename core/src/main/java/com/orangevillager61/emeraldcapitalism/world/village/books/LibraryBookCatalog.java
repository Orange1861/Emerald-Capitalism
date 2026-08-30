package com.orangevillager61.emeraldcapitalism.world.village.books;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/** Deterministic authored-book filtering and weighted shelf selection rules. */
public final class LibraryBookCatalog {
    public static final int BOOKS_PER_LIBRARY_SHELF = 6;

    private LibraryBookCatalog() {
    }

    public static List<LibraryBookDefinition> entries(
            Collection<LibraryBookDefinition> definitions, LibraryBookRarity rarity) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(rarity, "rarity");
        return definitions.stream()
                .filter(book -> book.rarity() == rarity)
                .sorted(Comparator.comparing(LibraryBookDefinition::id))
                .toList();
    }

    /** Selects up to six unique random-pool books using the supplied bounded sampler. */
    public static List<LibraryBookDefinition> selectLibraryBooks(
            Collection<LibraryBookDefinition> definitions, IntUnaryOperator nextInt) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(nextInt, "nextInt");

        List<LibraryBookDefinition> availableBooks = definitions.stream()
                .sorted(Comparator.comparing(LibraryBookDefinition::id))
                .toList();
        List<LibraryBookDefinition> selected = new ArrayList<>();
        Set<String> selectedIds = new HashSet<>();
        while (selected.size() < BOOKS_PER_LIBRARY_SHELF) {
            List<LibraryBookRarity> availableRarities = new ArrayList<>();
            int totalWeight = 0;
            for (LibraryBookRarity rarity : LibraryBookRarity.values()) {
                if (!rarity.isRandomLibraryPool() || rarity.libraryPoolWeight() <= 0) {
                    continue;
                }
                boolean available = availableBooks.stream().anyMatch(book ->
                        book.rarity() == rarity && !selectedIds.contains(book.id()));
                if (available) {
                    availableRarities.add(rarity);
                    totalWeight += rarity.libraryPoolWeight();
                }
            }
            if (totalWeight == 0) {
                break;
            }

            int roll = bounded(nextInt, totalWeight);
            LibraryBookRarity chosenRarity = availableRarities.getLast();
            for (LibraryBookRarity rarity : availableRarities) {
                if (roll < rarity.libraryPoolWeight()) {
                    chosenRarity = rarity;
                    break;
                }
                roll -= rarity.libraryPoolWeight();
            }

            List<LibraryBookDefinition> candidates = availableBooks.stream()
                    .filter(book -> book.rarity() == chosenRarity
                            && !selectedIds.contains(book.id()))
                    .toList();
            LibraryBookDefinition chosen = candidates.get(bounded(nextInt, candidates.size()));
            selected.add(chosen);
            selectedIds.add(chosen.id());
        }
        return List.copyOf(selected);
    }

    private static int bounded(IntUnaryOperator nextInt, int bound) {
        int result = nextInt.applyAsInt(bound);
        if (result < 0 || result >= bound) {
            throw new IllegalStateException(
                    "Bounded sampler returned " + result + " for bound " + bound);
        }
        return result;
    }
}
