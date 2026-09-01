package com.orangevillager61.emeraldcapitalism.world.village.books;

import java.util.Locale;
import java.util.Optional;

/** Supported authored-book categories and their library-generation policy. */
public enum LibraryBookRarity {
    COMMON("common", 60, true),
    UNCOMMON("uncommon", 30, true),
    RARE("rare", 9, true),
    LEGENDARY("legendary", 1, true),
    BANK_RULE("bank_rule", 0, false),
    VILLAGE_MANAGER("village_manager", 0, false);

    private final String id;
    private final int libraryPoolWeight;
    private final boolean randomLibraryPool;

    LibraryBookRarity(String id, int libraryPoolWeight, boolean randomLibraryPool) {
        this.id = id;
        this.libraryPoolWeight = libraryPoolWeight;
        this.randomLibraryPool = randomLibraryPool;
    }

    public String id() {
        return id;
    }

    public int libraryPoolWeight() {
        return libraryPoolWeight;
    }

    public boolean isRandomLibraryPool() {
        return randomLibraryPool;
    }

    public static Optional<LibraryBookRarity> fromId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        if (normalized.equals("bank_rules")) {
            normalized = "bank_rule";
        } else if (normalized.equals("village_manger")) {
            normalized = "village_manager";
        }
        for (LibraryBookRarity rarity : values()) {
            if (rarity.id.equals(normalized)) {
                return Optional.of(rarity);
            }
        }
        return Optional.empty();
    }
}
