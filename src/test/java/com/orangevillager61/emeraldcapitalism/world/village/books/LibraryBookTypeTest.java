package com.orangevillager61.emeraldcapitalism.world.village.books;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LibraryBookTypeTest {

    @Test
    void graveLocationTypeReplacesItsCoordinateToken() {
        assertEquals(List.of("The grave is at [20000, ~, -30000]."),
                LibraryBookType.STEVE_GRAVE_LOCATION.resolvePages(
                        List.of("The grave is at {{steve_grave_coordinates}}."),
                        Optional.of(new BlockPos(20_000, 80, -30_000))));
    }

    @Test
    void unresolvedGraveUsesAnExplicitFallback() {
        assertEquals(List.of("The grave is at [coordinates unavailable]."),
                LibraryBookType.STEVE_GRAVE_LOCATION.resolvePages(
                        List.of("The grave is at {{steve_grave_coordinates}}."), Optional.empty()));
    }
}
