package com.orangevillager61.emeraldcapitalism.client.renderer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankOwnershipOverlayRendererTest {

    @Test
    void ownershipBoundsAreExactlySixteenBlocksOnEachAxis() {
        AABB bounds = BankOwnershipOverlayRenderer.boundsFor(new BlockPos(24, 72, -11));

        assertEquals(16.0, bounds.getXsize());
        assertEquals(16.0, bounds.getYsize());
        assertEquals(16.0, bounds.getZsize());
        assertEquals(16.0, bounds.minX);
        assertEquals(32.0, bounds.maxX);
        assertEquals(-19.0, bounds.minZ);
        assertEquals(-3.0, bounds.maxZ);
    }
}
