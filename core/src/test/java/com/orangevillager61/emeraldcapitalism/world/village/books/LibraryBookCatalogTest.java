package com.orangevillager61.emeraldcapitalism.world.village.books;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LibraryBookCatalogTest {

    @Test
    void selectionExcludesSpecialBooksAndDoesNotDuplicateEntries() {
        List<LibraryBookDefinition> definitions = List.of(
                book("legendary", LibraryBookRarity.LEGENDARY),
                book("common-a", LibraryBookRarity.COMMON),
                book("common-b", LibraryBookRarity.COMMON),
                book("bank", LibraryBookRarity.BANK_RULE));
        AtomicInteger calls = new AtomicInteger();

        List<LibraryBookDefinition> selected = LibraryBookCatalog.selectLibraryBooks(
                definitions, bound -> {
                    calls.incrementAndGet();
                    return 0;
                });

        assertEquals(List.of("common-a", "common-b", "legendary"),
                selected.stream().map(LibraryBookDefinition::id).toList());
        assertEquals(selected.size() * 2, calls.get());
    }

    private static LibraryBookDefinition book(String id, LibraryBookRarity rarity) {
        return new LibraryBookDefinition(id, "Title", "Author", rarity,
                LibraryBookType.STATIC, List.of("Page"));
    }
}
