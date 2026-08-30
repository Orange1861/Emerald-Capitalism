package com.orangevillager61.emeraldcapitalism.world.forestry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharcoalProductionPolicyTest {

    @Test
    void equalPricesSplitProductionInHalf() {
        assertEquals(0.50D, CharcoalProductionPolicy.charcoalShare(16.0D, 16.0D), 0.0001D);
    }

    @Test
    void relativePricesMoveTheShareSymmetrically() {
        assertEquals(0.70D, CharcoalProductionPolicy.charcoalShare(16.0D, 8.0D), 0.0001D);
        assertEquals(0.30D, CharcoalProductionPolicy.charcoalShare(8.0D, 16.0D), 0.0001D);
    }

    @Test
    void shareAndQuotaAreBounded() {
        assertEquals(0.80D, CharcoalProductionPolicy.charcoalShare(1.0D, 0.0001D), 0.0001D);
        assertEquals(0.20D, CharcoalProductionPolicy.charcoalShare(0.0001D, 1.0D), 0.0001D);
        assertEquals(0.50D, CharcoalProductionPolicy.charcoalShare(Double.NaN, 16.0D), 0.0001D);
        assertEquals(0.50D, CharcoalProductionPolicy.charcoalShare(16.0D, 0.0D), 0.0001D);
    }

    @Test
    void rollingQuotaDoesNotRecountRetainedLogs() {
        double quota = CharcoalProductionPolicy.accrueQuota(0.0D, 8, 0.50D);
        assertEquals(4, CharcoalProductionPolicy.wholeConversions(quota, 8));
        quota = CharcoalProductionPolicy.afterConversions(quota, 4);

        quota = CharcoalProductionPolicy.accrueQuota(quota, 6, 0.50D);
        assertEquals(3, CharcoalProductionPolicy.wholeConversions(quota, 10));
        quota = CharcoalProductionPolicy.afterConversions(quota, 3);

        quota = CharcoalProductionPolicy.accrueQuota(quota, 5, 0.70D);
        assertEquals(3, CharcoalProductionPolicy.wholeConversions(quota, 15));
        assertEquals(0.50D, CharcoalProductionPolicy.afterConversions(quota, 3), 0.0001D);
    }

    @Test
    void quotaSchemaRulesRejectAndSanitizeMalformedValues() {
        assertTrue(CharcoalProductionPolicy.isValidQuota(0.0D));
        assertTrue(CharcoalProductionPolicy.isValidQuota(CharcoalProductionPolicy.MAX_PENDING_QUOTA));
        assertFalse(CharcoalProductionPolicy.isValidQuota(-0.01D));
        assertFalse(CharcoalProductionPolicy.isValidQuota(Double.NaN));
        assertFalse(CharcoalProductionPolicy.isValidQuota(Double.POSITIVE_INFINITY));
        assertEquals(0.0D, CharcoalProductionPolicy.sanitizeQuota(Double.NaN));
        assertEquals(0.0D,
                CharcoalProductionPolicy.sanitizeQuota(Double.POSITIVE_INFINITY));
        assertEquals(CharcoalProductionPolicy.MAX_PENDING_QUOTA,
                CharcoalProductionPolicy.sanitizeQuota(CharcoalProductionPolicy.MAX_PENDING_QUOTA + 1.0D));
    }
}
