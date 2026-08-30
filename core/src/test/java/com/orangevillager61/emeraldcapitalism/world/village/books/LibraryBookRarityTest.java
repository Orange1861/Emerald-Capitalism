package com.orangevillager61.emeraldcapitalism.world.village.books;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryBookRarityTest {

    @Test
    void libraryPoolUsesTheConfiguredFourRarityWeights() {
        assertEquals(70, LibraryBookRarity.COMMON.libraryPoolWeight());
        assertEquals(23, LibraryBookRarity.UNCOMMON.libraryPoolWeight());
        assertEquals(6, LibraryBookRarity.RARE.libraryPoolWeight());
        assertEquals(1, LibraryBookRarity.LEGENDARY.libraryPoolWeight());
        assertEquals(100, LibraryBookRarity.COMMON.libraryPoolWeight()
                + LibraryBookRarity.UNCOMMON.libraryPoolWeight()
                + LibraryBookRarity.RARE.libraryPoolWeight()
                + LibraryBookRarity.LEGENDARY.libraryPoolWeight());
    }

    @Test
    void specialBookTypesAreNeverPartOfTheRandomLibraryPool() {
        assertFalse(LibraryBookRarity.BANK_RULE.isRandomLibraryPool());
        assertFalse(LibraryBookRarity.VILLAGE_MANAGER.isRandomLibraryPool());
        assertEquals(LibraryBookRarity.BANK_RULE,
                LibraryBookRarity.fromId("Bank Rules").orElseThrow());
        assertEquals(LibraryBookRarity.VILLAGE_MANAGER,
                LibraryBookRarity.fromId("village_manger").orElseThrow());
        assertTrue(LibraryBookRarity.fromId("Legendary").orElseThrow().isRandomLibraryPool());
    }
}
