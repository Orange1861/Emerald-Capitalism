package com.orangevillager61.emeraldcapitalism.world.village;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageRelationshipTest {

    @Test
    void resolvesRequestedRelationshipThresholds() {
        assertEquals(VillageRelationship.HOSTILE,
                VillageRelationship.resolve(-100, false, false));
        assertEquals(VillageRelationship.HOSTILE,
                VillageRelationship.resolve(-101, true, true));
        assertEquals(VillageRelationship.NEUTRAL,
                VillageRelationship.resolve(0, false, false));
        assertEquals(VillageRelationship.FRIENDLY,
                VillageRelationship.resolve(100, false, false));
        assertEquals("Friendly", VillageRelationship.FRIENDLY.displayName());
        assertEquals(VillageRelationship.NEUTRAL,
                VillageRelationship.resolve(99, false, false));
        assertEquals(VillageRelationship.GOVERNOR_CANDIDATE,
                VillageRelationship.resolve(100, false, true));
        assertEquals(VillageRelationship.GOVERNOR,
                VillageRelationship.resolve(100, true, false));
        assertFalse(VillageRelationship.canBecomeGovernorCandidate(99));
        assertTrue(VillageRelationship.canBecomeGovernorCandidate(100));
        assertEquals(VillageRelationship.HOSTILE,
                VillageRelationship.resolve(-25, -25, false, false));
        assertEquals(VillageRelationship.FRIENDLY,
                VillageRelationship.resolve(81, -25, 80, false, false));
        assertTrue(VillageRelationship.canBecomeGovernorCandidate(81, 80));
        assertFalse(VillageRelationship.canBecomeGovernorCandidate(80, 80));
    }

    @Test
    void invalidNetworkIdsDefaultToNeutral() {
        assertEquals(VillageRelationship.NEUTRAL, VillageRelationship.fromNetworkId(-1));
        assertEquals(VillageRelationship.NEUTRAL, VillageRelationship.fromNetworkId(99));
    }
}
