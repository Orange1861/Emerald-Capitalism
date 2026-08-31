package com.orangevillager61.emeraldcapitalism.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModIdsTest {

    @Test
    void preservesValidPathsAndRejectsEmptyOrInvalidPaths() {
        assertEquals("emeraldcapitalism:textures/gui/villager_stats.png",
                ModIds.id("textures/gui/villager_stats.png").toString());

        assertThrows(IllegalArgumentException.class, () -> ModIds.id(null));
        assertThrows(IllegalArgumentException.class, () -> ModIds.id(""));
        assertThrows(IllegalArgumentException.class, () -> ModIds.id("BadPath"));
        assertThrows(IllegalArgumentException.class, () -> ModIds.id("minecraft:stone"));
    }
}
