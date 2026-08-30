package com.orangevillager61.emeraldcapitalism.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketPricingEngineTest {
    private static final MarketItemConfig BREAD = new MarketItemConfig(
            "bread", 6.0, 3.0, true, 9.0, 13.0,
            0.13, 1.8, 0.10, 36.0, 0.10, 1,
            MarketDemandSource.POPULATION, MarketMetric.DAYS, null, 0, 0);

    private static final MarketItemConfig PUMPKIN = new MarketItemConfig(
            "pumpkin", 4.0, 1.0, false, 1.0, 1.33,
            null, 1.0, null, 32.0, 0.10, 1,
            MarketDemandSource.BANK_PUMPKIN_TARGET, MarketMetric.TARGET_RATIO, 3.0, 0, 0);

    private static final MarketItemConfig EMERALD_ORE_FIXED = new MarketItemConfig(
            "emerald_ore", 0.25, 1.0, false, 1.0, 1.0,
            null, 0.25, null, 0.25, 0.0, 1,
            MarketDemandSource.POPULATION, MarketMetric.DAYS, null, 4, 0);

    private static final MarketItemConfig VAULT_MAP_FIXED_BUY = new MarketItemConfig(
            "abandoned_vault_map", 1.0, 1.0, false, 1.0, 1.0,
            null, 1.0, null, 1.0, 0.0, 1,
            MarketDemandSource.POPULATION, MarketMetric.DAYS, null, 0, 128);

    private static final MarketItemConfig EMERALD_CHEST_BOTH_DIRECTIONS = new MarketItemConfig(
            "emerald_chest", 0.125, 1.0, false, 1.0, 1.0,
            null, 0.125, null, 0.125, 0.0, 1,
            MarketDemandSource.POPULATION, MarketMetric.DAYS, null, 6, 9);

    private static final MarketDemandContext POPULATION_TEN = new MarketDemandContext(10, 1);

    @Test
    void breadMatchesReferenceCurveAtPopulationTen() {
        assertEquals(1.86, MarketPricingEngine.midRate(BREAD, 0, POPULATION_TEN), 0.01);
        assertEquals(6.0, MarketPricingEngine.midRate(BREAD, 270, POPULATION_TEN), 0.0001);
        assertEquals(6.0, MarketPricingEngine.midRate(BREAD, 390, POPULATION_TEN), 0.0001);
        assertEquals(12.08, MarketPricingEngine.midRate(BREAD, 600, POPULATION_TEN), 0.02);
        assertEquals(36.0, MarketPricingEngine.midRate(BREAD, 930, POPULATION_TEN), 0.0001);
        assertEquals(36.0, MarketPricingEngine.midRate(BREAD, 1200, POPULATION_TEN), 0.0001);
    }

    @Test
    void marginalQuoteMovesTheProjectedRate() {
        MarketTradeQuote quote = MarketPricingEngine.quote(BREAD, 390, POPULATION_TEN, 30, TradeSide.BUY);
        assertTrue(quote.valid());
        assertEquals(6.0, quote.currentMidRate(), 0.0001);
        assertEquals(6.0, quote.projectedMidRate(), 0.0001);
        assertTrue(quote.rawEmeraldAmount() > 30.0 / 6.0);
    }

    @Test
    void minimumSizeAndStockAreEnforced() {
        assertFalse(MarketPricingEngine.quote(BREAD, 10, POPULATION_TEN, 0, TradeSide.BUY).valid());
        assertFalse(MarketPricingEngine.quote(BREAD, 10, POPULATION_TEN, 11, TradeSide.BUY).valid());
    }

    @Test
    void fixedBuyCosts128EmeraldsAndConsumesOneStockedMap() {
        MarketTradeQuote quote = MarketPricingEngine.quote(
                VAULT_MAP_FIXED_BUY, 1, POPULATION_TEN, 1, TradeSide.BUY);

        assertTrue(quote.valid());
        assertEquals(128, quote.emeraldAmount());
        assertEquals(0.0, quote.projectedStock(), 0.0001);
        assertFalse(MarketPricingEngine.quote(
                VAULT_MAP_FIXED_BUY, 1, POPULATION_TEN, 1, TradeSide.SELL).valid());
    }

    @Test
    void buyThenSellAtTheSameStockCannotCreateEmeralds() {
        int stock = 390;
        MarketTradeQuote buy = MarketPricingEngine.quote(BREAD, stock, POPULATION_TEN, 40, TradeSide.BUY);
        MarketTradeQuote sell = MarketPricingEngine.quote(BREAD, buy.projectedStock(), POPULATION_TEN,
                40, TradeSide.SELL);
        assertTrue(buy.valid());
        assertTrue(sell.valid());
        assertTrue(sell.emeraldAmount() <= buy.emeraldAmount(),
                "round trip must not return more emeralds than it spent");
    }

    @Test
    void pumpkinRoundsTargetBandOutwardAndDerivesExponents() {
        MarketDemandContext context = new MarketDemandContext(10, 7);
        assertEquals(1.0, MarketPricingEngine.greenBandLow(PUMPKIN, context), 0.0001);
        assertEquals(10.0 / 7.0, MarketPricingEngine.greenBandHigh(PUMPKIN, context), 0.0001);
        assertEquals(1.00, MarketPricingEngine.midRate(PUMPKIN, 0, context), 0.0001);
        assertEquals(3.28, MarketPricingEngine.midRate(PUMPKIN, 6, context), 0.01);
        assertEquals(4.0, MarketPricingEngine.midRate(PUMPKIN, 7, context), 0.0001);
        assertEquals(4.0, MarketPricingEngine.midRate(PUMPKIN, 10, context), 0.0001);
        assertEquals(4.83, MarketPricingEngine.midRate(PUMPKIN, 11, context), 0.02);
        assertEquals(26.5, MarketPricingEngine.midRate(PUMPKIN, 20, context), 0.1);
        assertEquals(32.0, MarketPricingEngine.midRate(PUMPKIN, 21, context), 0.0001);
    }

    @Test
    void pumpkinRefusesBuyAtHardStopButStillAllowsSell() {
        MarketDemandContext context = new MarketDemandContext(10, 7);

        MarketTradeQuote lastBankBuy = MarketPricingEngine.quote(
                PUMPKIN, 20, context, 26, TradeSide.SELL);
        MarketTradeQuote refusedBankBuy = MarketPricingEngine.quote(
                PUMPKIN, 21, context, 32, TradeSide.SELL);
        MarketTradeQuote bankSell = MarketPricingEngine.quote(
                PUMPKIN, 40, context, 32, TradeSide.BUY);

        assertTrue(lastBankBuy.valid());
        assertEquals(1, lastBankBuy.emeraldAmount());
        assertFalse(refusedBankBuy.valid());
        assertEquals("market_refuses_buying", refusedBankBuy.invalidReason());
        assertEquals(0, refusedBankBuy.emeraldAmount());
        assertTrue(bankSell.valid());

        MarketTradeQuote fractionalStock = MarketPricingEngine.quote(
                PUMPKIN, 20.75, context, 26, TradeSide.SELL);
        assertEquals(46.0, fractionalStock.projectedStock(), 0.0001);
    }

    @Test
    void targetRatioEdgesRoundOutwardAtSmallTargets() {
        for (int target : new int[]{3, 5, 7, 9, 11}) {
            MarketDemandContext context = new MarketDemandContext(10, target);
            assertEquals(1.0, MarketPricingEngine.greenBandLow(PUMPKIN, context), 0.0001);
            assertEquals(Math.ceil(1.33 * target) / (double) target,
                    MarketPricingEngine.greenBandHigh(PUMPKIN, context), 0.0001);
        }
    }

    @Test
    void subUnitRatesEncodeAsWholeEmeraldTotals() {
        MarketItemConfig subUnit = new MarketItemConfig(
                "sub-unit", 1.0, 1.0, false, 1.0, 2.0,
                2.0, 0.25, 1.0, 4.0, 0.10, 1,
                MarketDemandSource.POPULATION, MarketMetric.DAYS, null, 0, 0);

        MarketTradeQuote quote = MarketPricingEngine.quote(
                subUnit, 0, POPULATION_TEN, 1, TradeSide.SELL);

        assertTrue(quote.valid());
        assertEquals(3, quote.emeraldAmount());
    }

    @Test
    void targetRatioTradesUseWholeRateBatchesInBothDirections() {
        MarketDemandContext context = new MarketDemandContext(10, 7);

        MarketTradeQuote buyFour = MarketPricingEngine.quote(
                PUMPKIN, 7, context, 4, TradeSide.BUY);
        MarketTradeQuote buyOne = MarketPricingEngine.quote(
                PUMPKIN, 7, context, 1, TradeSide.BUY);
        MarketTradeQuote sellFour = MarketPricingEngine.quote(
                PUMPKIN, 7, context, 4, TradeSide.SELL);
        MarketTradeQuote sellThree = MarketPricingEngine.quote(
                PUMPKIN, 7, context, 3, TradeSide.SELL);

        assertTrue(buyFour.valid());
        assertEquals(1, buyFour.emeraldAmount());
        assertFalse(buyOne.valid());
        assertEquals("trade_batch_size", buyOne.invalidReason());
        assertTrue(sellFour.valid());
        assertEquals(1, sellFour.emeraldAmount());
        assertFalse(sellThree.valid());
        assertEquals("trade_batch_size", sellThree.invalidReason());
    }

    @Test
    void targetRatioBulkSellRepricesEveryCompletedBatch() {
        MarketDemandContext context = new MarketDemandContext(10, 7);

        MarketTradeQuote sellNine = MarketPricingEngine.quote(
                PUMPKIN, 7, context, 9, TradeSide.SELL);
        MarketTradeQuote sellEight = MarketPricingEngine.quote(
                PUMPKIN, 7, context, 8, TradeSide.SELL);

        assertTrue(sellNine.valid(), "4 pumpkins followed by the next 5-pump batch is valid");
        assertEquals(2, sellNine.emeraldAmount());
        assertEquals(16.0, sellNine.projectedStock(), 0.0001);
        assertTrue(sellEight.valid(), "the completed 4-pumpkin batch can settle early");
        assertEquals(4, sellEight.quantity());
        assertEquals(1, sellEight.emeraldAmount());
        assertEquals(11.0, sellEight.projectedStock(), 0.0001);
        assertEquals(5, MarketPricingEngine.nextTradeBatchSize(
                PUMPKIN, 7, context, 8, TradeSide.SELL));
    }

    @Test
    void targetRatioFractionalEmeraldsRoundTowardTheBank() {
        MarketDemandContext context = new MarketDemandContext(10, 7);

        MarketTradeQuote sellFive = MarketPricingEngine.quote(
                PUMPKIN, 11, context, 5, TradeSide.SELL);
        MarketTradeQuote buyFive = MarketPricingEngine.quote(
                PUMPKIN, 11, context, 5, TradeSide.BUY);

        assertTrue(sellFive.valid());
        assertEquals(1, sellFive.emeraldAmount(),
                "a fractional payout rounds down for the bank");
        assertTrue(buyFive.valid());
        assertEquals(2, buyFive.emeraldAmount(),
                "a fractional cost rounds up for the bank");
    }

    @Test
    void dynamicQuantityControlsAdvanceByTheNextBatch() {
        MarketDemandContext context = new MarketDemandContext(10, 7);

        assertEquals(4, MarketPricingEngine.nextValidTradeQuantity(
                PUMPKIN, 7, context, 0, TradeSide.SELL, 1));
        assertEquals(9, MarketPricingEngine.nextValidTradeQuantity(
                PUMPKIN, 7, context, 4, TradeSide.SELL, 1));
        assertEquals(4, MarketPricingEngine.nextValidTradeQuantity(
                PUMPKIN, 7, context, 9, TradeSide.SELL, -1));
        assertEquals(4, MarketPricingEngine.maxValidBatchTradeQuantity(
                PUMPKIN, 7, context, 8, TradeSide.SELL, 0));
        assertEquals(9, MarketPricingEngine.maxValidBatchTradeQuantity(
                PUMPKIN, 7, context, 9, TradeSide.SELL, 0));
    }

    @Test
    void fixedTradeKeepsTheSamePriceForBulkQuantities() {
        MarketTradeQuote one = MarketPricingEngine.quote(
                EMERALD_ORE_FIXED, 0, POPULATION_TEN, 1, TradeSide.SELL);
        MarketTradeQuote three = MarketPricingEngine.quote(
                EMERALD_ORE_FIXED, 0, POPULATION_TEN, 3, TradeSide.SELL);

        assertTrue(one.valid());
        assertEquals(4, one.emeraldAmount());
        assertEquals(0.25, one.effectiveRate(), 0.0001);
        assertTrue(three.valid());
        assertEquals(12, three.emeraldAmount());
        assertEquals(one.effectiveRate(), three.effectiveRate(), 0.0001);
        assertEquals(0.25, MarketPricingEngine.midRate(EMERALD_ORE_FIXED, 999, POPULATION_TEN), 0.0001);
    }

    @Test
    void fixedTradeIsSellOnly() {
        MarketTradeQuote buy = MarketPricingEngine.quote(
                EMERALD_ORE_FIXED, 10, POPULATION_TEN, 1, TradeSide.BUY);

        assertFalse(buy.valid());
        assertEquals("fixed_trade_direction", buy.invalidReason());
    }

    @Test
    void fixedTradeCanHaveDifferentBuyAndSellPrices() {
        MarketTradeQuote bankBuy = MarketPricingEngine.quote(
                EMERALD_CHEST_BOTH_DIRECTIONS, 0, POPULATION_TEN, 1, TradeSide.SELL);
        MarketTradeQuote bankSell = MarketPricingEngine.quote(
                EMERALD_CHEST_BOTH_DIRECTIONS, 1, POPULATION_TEN, 1, TradeSide.BUY);

        assertTrue(bankBuy.valid());
        assertEquals(6, bankBuy.emeraldAmount());
        assertEquals(1.0, bankBuy.projectedStock(), 0.0001);
        assertTrue(bankSell.valid());
        assertEquals(9, bankSell.emeraldAmount());
        assertEquals(0.0, bankSell.projectedStock(), 0.0001);
    }
}
