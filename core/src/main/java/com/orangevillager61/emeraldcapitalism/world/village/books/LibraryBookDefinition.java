package com.orangevillager61.emeraldcapitalism.world.village.books;

import java.util.List;
import java.util.Objects;

/** Immutable, resource-backed authored-book content independent of Minecraft items. */
public record LibraryBookDefinition(
        String id,
        String title,
        String author,
        LibraryBookRarity rarity,
        LibraryBookType type,
        List<String> pages
) {
    public LibraryBookDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(rarity, "rarity");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(pages, "pages");
        pages = List.copyOf(pages);
    }
}
