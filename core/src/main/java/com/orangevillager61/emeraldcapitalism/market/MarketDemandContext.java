package com.orangevillager61.emeraldcapitalism.market;

/** Plain snapshot of bank context needed to evaluate a market's demand. */
public record MarketDemandContext(int population, int bankTarget) {
    public MarketDemandContext {
        population = Math.max(1, population);
        bankTarget = Math.max(1, bankTarget);
    }

}
