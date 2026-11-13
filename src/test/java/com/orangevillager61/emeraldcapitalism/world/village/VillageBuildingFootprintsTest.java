package com.orangevillager61.emeraldcapitalism.world.village;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageBuildingFootprintsTest {

    // A house standing at its real height.
    private static final List<BoundingBox> HOUSE = List.of(new BoundingBox(10, 64, 10, 18, 74, 18));

    @Test
    void candidateAtHeightZeroStillOverlapsAHouseItsColumnsCover() {
        // Site candidates are evaluated with a y=0 anchor, before their height is known.
        assertTrue(VillageBuildingFootprints.overlapsHorizontally(new BoundingBox(15, 0, 15, 30, 12, 30), HOUSE));
    }

    @Test
    void touchingColumnsOverlapAndAGapDoesNot() {
        assertTrue(VillageBuildingFootprints.overlapsHorizontally(new BoundingBox(18, 0, 0, 25, 12, 9), List.of(
                new BoundingBox(10, 64, 9, 18, 74, 18))));
        assertFalse(VillageBuildingFootprints.overlapsHorizontally(new BoundingBox(19, 0, 10, 25, 12, 18), HOUSE));
        assertFalse(VillageBuildingFootprints.overlapsHorizontally(new BoundingBox(10, 0, 19, 18, 12, 25), HOUSE));
    }
}
