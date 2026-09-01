package com.orangevillager61.emeraldcapitalism.client.renderer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankOwnershipOverlayRendererTest {

    @AfterEach
    void clearOverlayState() {
        BankOwnershipOverlayRenderer.clear();
    }

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

    @Test
    void rememberedOwnedBankRemainsAvailableUntilOwnershipIsRemoved() {
        BlockPos bankPos = new BlockPos(4, 64, 9);
        BlockPos otherBankPos = new BlockPos(-12, 68, 21);

        BankOwnershipOverlayRenderer.updateBank(bankPos, true);
        BankOwnershipOverlayRenderer.updateBank(otherBankPos, true);
        assertTrue(BankOwnershipOverlayRenderer.hasOwnedBanks());
        assertTrue(BankOwnershipOverlayRenderer.hasEnabledOverlays());
        assertTrue(BankOwnershipOverlayRenderer.isOverlayEnabled(bankPos));
        assertTrue(BankOwnershipOverlayRenderer.isOverlayEnabled(otherBankPos));

        assertFalse(BankOwnershipOverlayRenderer.toggleOverlay(bankPos));
        assertFalse(BankOwnershipOverlayRenderer.isOverlayEnabled(bankPos));
        assertTrue(BankOwnershipOverlayRenderer.isOverlayEnabled(otherBankPos));
        assertTrue(BankOwnershipOverlayRenderer.hasEnabledOverlays());

        BankOwnershipOverlayRenderer.updateBank(bankPos, true);
        assertFalse(BankOwnershipOverlayRenderer.isOverlayEnabled(bankPos));

        assertTrue(BankOwnershipOverlayRenderer.toggleOverlay(bankPos));
        assertTrue(BankOwnershipOverlayRenderer.isOverlayEnabled(bankPos));
        assertTrue(BankOwnershipOverlayRenderer.hasEnabledOverlays());

        BankOwnershipOverlayRenderer.updateBank(bankPos, false);
        BankOwnershipOverlayRenderer.updateBank(otherBankPos, false);
        assertFalse(BankOwnershipOverlayRenderer.hasOwnedBanks());
        assertFalse(BankOwnershipOverlayRenderer.hasEnabledOverlays());
    }
}
