package com.orangevillager61.emeraldcapitalism.market;

/** Result of pricing one complete marginally-priced trade. */
public record MarketTradeQuote(
        int quantity,
        TradeSide side,
        double currentMidRate,
        double projectedMidRate,
        double rawEmeraldAmount,
        int emeraldAmount,
        double effectiveRate,
        double projectedStock,
        boolean valid,
        String invalidReason
) {
    public static MarketTradeQuote invalid(String reason, int quantity, TradeSide side,
                                            double currentMidRate, double projectedStock) {
        return new MarketTradeQuote(quantity, side, currentMidRate, currentMidRate,
                0.0, 0, 0.0, projectedStock, false, reason);
    }
}
