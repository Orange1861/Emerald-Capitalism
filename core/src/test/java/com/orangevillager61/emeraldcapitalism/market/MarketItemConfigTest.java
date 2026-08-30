package com.orangevillager61.emeraldcapitalism.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketItemConfigTest {

    @Test
    void canonicalEdgesSupportBothMetricsAndFixedDirections() {
        MarketItemConfig days = config("days", 9.0, 13.0, MarketMetric.DAYS, null, 0, 0);
        MarketItemConfig zeroDayFloor = config("zero-day-floor", 0.0, 1.0,
                MarketMetric.DAYS, null, 0, 0);
        MarketItemConfig targetRatio = config("target", 1.0, 1.33, MarketMetric.TARGET_RATIO, 3.0, 0, 0);
        MarketItemConfig fixedBuy = config("fixed-buy", 1.0, 1.0, MarketMetric.DAYS, null, 0, 128);
        MarketItemConfig fixedSell = config("fixed-sell", 1.0, 1.0, MarketMetric.DAYS, null, 4, 0);

        assertEquals(9.0, days.lowEdge());
        assertEquals(13.0, days.highEdge());
        assertEquals(0.0, zeroDayFloor.lowEdge());
        assertEquals(MarketMetric.TARGET_RATIO, targetRatio.metric());
        assertEquals(MarketTradeType.FIXED, fixedBuy.tradeType());
        assertEquals(MarketTradeType.FIXED, fixedSell.tradeType());
        assertEquals(128, fixedBuy.fixedEmeraldAmount(TradeSide.BUY));
        assertEquals(4, fixedSell.fixedEmeraldAmount(TradeSide.SELL));
    }

    @Test
    void rejectsMalformedCanonicalValues() {
        assertThrows(IllegalArgumentException.class,
                () -> config(" ", 1.0, 1.0, MarketMetric.DAYS, null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> config("band", 1.0, 1.0, MarketMetric.DAYS, null, 0, 0,
                        2.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> config("target-band", 1.0, 1.0, MarketMetric.TARGET_RATIO, null, 0, 0,
                        0.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketItemConfig("null-source", 1.0, 1.0, false,
                        1.0, 2.0, null, 1.0, null, 2.0, 0.0, 1,
                        null, MarketMetric.DAYS, null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketItemConfig("null-metric", 1.0, 1.0, false,
                        1.0, 2.0, null, 1.0, null, 2.0, 0.0, 1,
                        MarketDemandSource.POPULATION, null, null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> config("fixed-negative", 1.0, 1.0, MarketMetric.DAYS, null, -1, 0));
    }

    private static MarketItemConfig config(String id, double lowEdge, double highEdge,
                                           MarketMetric metric, Double hardStop,
                                           int fixedOutput, int fixedCost) {
        return config(id, lowEdge, highEdge, metric, hardStop, fixedOutput, fixedCost,
                1.0, 2.0);
    }

    private static MarketItemConfig config(String id, double lowEdge, double highEdge,
                                           MarketMetric metric, Double hardStop,
                                           int fixedOutput, int fixedCost,
                                           double floorRate, double ceilingRate) {
        return new MarketItemConfig(id, 2.0, 1.0, false, lowEdge, highEdge,
                null, floorRate, null, ceilingRate, 0.0, 1,
                MarketDemandSource.POPULATION, metric, hardStop, fixedOutput, fixedCost);
    }
}
