package com.orangevillager61.emeraldcapitalism.world.village.scan;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitialVillageScanChunkLoadPoolTest {

    @Test
    void enforcesLevelWideConcurrentLimitAcrossVillages() {
        InitialVillageScanChunkLoadPool.PermitTracker permits =
                new InitialVillageScanChunkLoadPool.PermitTracker();
        UUID firstVillage = UUID.randomUUID();
        UUID secondVillage = UUID.randomUUID();

        assertEquals(InitialVillageScanChunkLoadPool.PermitResult.ACQUIRED,
                permits.tryAcquire(firstVillage, 2, 4));
        assertEquals(InitialVillageScanChunkLoadPool.PermitResult.ACQUIRED,
                permits.tryAcquire(secondVillage, 2, 4));
        assertEquals(InitialVillageScanChunkLoadPool.PermitResult.GLOBAL_LIMIT,
                permits.tryAcquire(firstVillage, 2, 4));

        permits.releaseActive(firstVillage);
        assertEquals(InitialVillageScanChunkLoadPool.PermitResult.ACQUIRED,
                permits.tryAcquire(firstVillage, 2, 4));
        assertEquals(2, permits.active());
    }

    @Test
    void perVillageCapLimitsOneProgressiveBatch() {
        InitialVillageScanChunkLoadPool.PermitTracker permits =
                new InitialVillageScanChunkLoadPool.PermitTracker();
        UUID village = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            assertEquals(InitialVillageScanChunkLoadPool.PermitResult.ACQUIRED,
                    permits.tryAcquire(village, 3, 3));
            permits.releaseActive(village);
        }

        assertEquals(InitialVillageScanChunkLoadPool.PermitResult.SCAN_LIMIT,
                permits.tryAcquire(village, 3, 3));
        assertEquals(3, permits.started(village));
        assertEquals(0, permits.active());
        assertTrue(permits.beginNextBatch(village));
        assertEquals(InitialVillageScanChunkLoadPool.PermitResult.ACQUIRED,
                permits.tryAcquire(village, 3, 3));
    }

    @Test
    void activeLoadsPreventStartingTheNextBatch() {
        InitialVillageScanChunkLoadPool.PermitTracker permits =
                new InitialVillageScanChunkLoadPool.PermitTracker();
        UUID village = UUID.randomUUID();

        assertEquals(InitialVillageScanChunkLoadPool.PermitResult.ACQUIRED,
                permits.tryAcquire(village, 3, 1));
        assertEquals(InitialVillageScanChunkLoadPool.PermitResult.SCAN_LIMIT,
                permits.tryAcquire(village, 3, 1));
        assertFalse(permits.beginNextBatch(village));

        permits.releaseActive(village);
        assertTrue(permits.beginNextBatch(village));
        assertEquals(InitialVillageScanChunkLoadPool.PermitResult.ACQUIRED,
                permits.tryAcquire(village, 3, 1));
    }
}
