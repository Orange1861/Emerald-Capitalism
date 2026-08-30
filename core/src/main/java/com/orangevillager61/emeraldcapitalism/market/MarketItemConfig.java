package com.orangevillager61.emeraldcapitalism.market;

/** Immutable, platform-free configuration for one tradeable market item. */
public record MarketItemConfig(
        String id,
        double baseRate,
        double dailyConsumptionRate,
        boolean dailyConsumptionScalesWithPopulation,
        double lowEdge,
        double highEdge,
        Double kScarcity,
        double floorRate,
        Double kGlut,
        double ceilingRate,
        double bidAskSpread,
        int minimumTradeSize,
        MarketDemandSource demandSource,
        MarketMetric metric,
        Double hardStopMult,
        int fixedEmeraldOutput,
        int fixedEmeraldCost
) {
    public MarketItemConfig {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Market item id must not be blank");
        }
        if (!(baseRate > 0.0) || !(dailyConsumptionRate > 0.0)
                || !(lowEdge >= 0.0) || !(highEdge >= lowEdge)
                || !(floorRate > 0.0) || !(ceilingRate >= floorRate)
                || !(bidAskSpread >= 0.0) || minimumTradeSize <= 0
                || demandSource == null || metric == null
                || !Double.isFinite(baseRate) || !Double.isFinite(dailyConsumptionRate)
                || !Double.isFinite(lowEdge) || !Double.isFinite(highEdge)
                || !Double.isFinite(floorRate) || !Double.isFinite(ceilingRate)
                || !Double.isFinite(bidAskSpread)
                || (metric == MarketMetric.TARGET_RATIO && !(lowEdge > 0.0))
                || (kScarcity != null && (!(kScarcity >= 0.0) || !Double.isFinite(kScarcity)))
                || (kGlut != null && (!(kGlut >= 0.0) || !Double.isFinite(kGlut)))
                || (hardStopMult != null && (!(hardStopMult > 0.0)
                        || !Double.isFinite(hardStopMult)))
                || fixedEmeraldOutput < 0 || fixedEmeraldCost < 0) {
            throw new IllegalArgumentException("Invalid market pricing configuration for " + id);
        }
    }

    public MarketTradeType tradeType() {
        return fixedEmeraldOutput > 0 || fixedEmeraldCost > 0
                ? MarketTradeType.FIXED : MarketTradeType.DYNAMIC;
    }

    /** True when the bank sells a fixed-price item to the player. */
    public boolean fixedTradeIsBuy() {
        return fixedEmeraldCost > 0;
    }

    /** True when the bank buys this item for a fixed emerald payout. */
    public boolean supportsFixedSell() {
        return fixedEmeraldOutput > 0;
    }

    /** True when the bank sells this item for a fixed emerald cost. */
    public boolean supportsFixedBuy() {
        return fixedEmeraldCost > 0;
    }

    /** Emeralds exchanged by one complete fixed trade batch. */
    public int fixedEmeraldAmount() {
        return fixedTradeIsBuy() ? fixedEmeraldCost : fixedEmeraldOutput;
    }

    /** Emeralds exchanged by one fixed trade batch in the requested direction. */
    public int fixedEmeraldAmount(TradeSide side) {
        return side == TradeSide.BUY ? fixedEmeraldCost : fixedEmeraldOutput;
    }

}
