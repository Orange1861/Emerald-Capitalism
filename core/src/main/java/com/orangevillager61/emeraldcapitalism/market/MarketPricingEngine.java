package com.orangevillager61.emeraldcapitalism.market;

/**
 * Platform-free market pricing and marginal trade execution rules.
 * <p>
 * Rates are measured in item units per emerald. A BUY therefore costs
 * {@code 1 / sellRate} emeralds per item and a SELL pays {@code 1 / buyRate}.
 */
public final class MarketPricingEngine {
    private MarketPricingEngine() {
    }

    public static double daysOfSupply(MarketItemConfig config, double stock,
                                      MarketDemandContext context) {
        double demandBasis = switch (config.demandSource()) {
            case POPULATION -> config.dailyConsumptionScalesWithPopulation()
                    ? context.population() : 1.0;
            case BANK_PUMPKIN_TARGET, BANK_TARGET, BANK_PLANK_TARGET, BANK_COAL_TARGET
                    -> context.bankTarget();
        };
        double demand = config.dailyConsumptionRate() * demandBasis;
        return Math.max(0.0, stock) / demand;
    }

    /** Returns the normalized position used to evaluate an item's green band. */
    public static double normalizedSupply(MarketItemConfig config, double stock,
                                          MarketDemandContext context) {
        return switch (config.metric()) {
            case DAYS -> daysOfSupply(config, stock, context);
            case TARGET_RATIO -> Math.max(0.0, stock) / context.bankTarget();
        };
    }

    public static double greenBandLow(MarketItemConfig config, MarketDemandContext context) {
        return normalizedBand(config, context).lowEdge();
    }

    public static double greenBandHigh(MarketItemConfig config, MarketDemandContext context) {
        return normalizedBand(config, context).highEdge();
    }

    private static NormalizedBand normalizedBand(MarketItemConfig config,
                                                 MarketDemandContext context) {
        if (config.metric() != MarketMetric.TARGET_RATIO) {
            return new NormalizedBand(config.lowEdge(), config.highEdge());
        }
        int target = context.bankTarget();
        double lowStock = Math.floor(config.lowEdge() * target);
        double highStock = Math.ceil(config.highEdge() * target);
        return new NormalizedBand(lowStock / (double) target,
                highStock / (double) target);
    }

    private static double scarcityExponent(MarketItemConfig config, NormalizedBand band) {
        if (config.kScarcity() != null) {
            return config.kScarcity();
        }
        return Math.log(config.baseRate() / config.floorRate()) / band.lowEdge();
    }

    private static double glutExponent(MarketItemConfig config, NormalizedBand band) {
        if (config.kGlut() != null) {
            return config.kGlut();
        }
        if (config.hardStopMult() == null) {
            throw new IllegalArgumentException(
                    "Missing hardStopMult for market item " + config.id());
        }
        return Math.log(config.ceilingRate() / config.baseRate())
                / (config.hardStopMult() - band.highEdge());
    }

    /**
     * Shared normalized-supply pricing curve. The caller supplies a unitless
     * position and the two edges in that same unitless metric.
     */
    public static double rateFromNormalizedSupply(double x, double lowEdge, double highEdge,
                                                  double baseRate, double floorRate,
                                                  double ceilingRate, double kScarcity,
                                                  double kGlut) {
        if (x >= lowEdge && x <= highEdge) {
            return baseRate;
        }
        if (x < lowEdge) {
            return Math.max(floorRate,
                    baseRate * Math.exp(-kScarcity * (lowEdge - x)));
        }
        return Math.min(ceilingRate,
                baseRate * Math.exp(kGlut * (x - highEdge)));
    }

    /** Returns the unclipped mid-rate curve value after applying its configured clamps. */
    public static double midRate(MarketItemConfig config, double stock,
                                 MarketDemandContext context) {
        if (config.tradeType() == MarketTradeType.FIXED) {
            return config.minimumTradeSize() / (double) config.fixedEmeraldAmount();
        }
        NormalizedBand band = normalizedBand(config, context);
        return rateFromNormalizedSupply(
                normalizedSupply(config, stock, context),
                band.lowEdge(), band.highEdge(),
                config.baseRate(), config.floorRate(), config.ceilingRate(),
                scarcityExponent(config, band), glutExponent(config, band));
    }

    /** Returns the whole-item batch that best represents one emerald at the current rate. */
    public static int tradeBatchSize(MarketItemConfig config, double stock,
                                     MarketDemandContext context) {
        if (config.tradeType() == MarketTradeType.FIXED) {
            return config.minimumTradeSize();
        }
        if (config.metric() != MarketMetric.TARGET_RATIO) {
            return config.minimumTradeSize();
        }
        double rate = midRate(config, stock, context);
        return rate < 1.0 ? 1 : Math.max(1, (int) Math.round(rate));
    }

    /**
     * Returns the next batch size after consuming the complete batches before
     * {@code quantity}. This is used by the client to explain a dynamic-batch
     * rejection and by the quantity controls to skip impossible quantities.
     */
    public static int nextTradeBatchSize(MarketItemConfig config, double stock,
                                         MarketDemandContext context, int quantity,
                                         TradeSide side) {
        if (config.tradeType() == MarketTradeType.FIXED) {
            return config.minimumTradeSize();
        }
        if (config.metric() != MarketMetric.TARGET_RATIO) {
            return config.minimumTradeSize();
        }
        double runningStock = Math.floor(Math.max(0.0, stock));
        int consumed = 0;
        while (consumed < Math.max(0, quantity)) {
            int batch = tradeBatchSize(config, runningStock, context);
            if (consumed + batch > quantity) {
                return batch;
            }
            consumed += batch;
            runningStock += side == TradeSide.BUY ? -batch : batch;
        }
        return tradeBatchSize(config, runningStock, context);
    }

    /**
     * Returns the adjacent valid cumulative quantity in the dynamic batch
     * sequence. A positive direction advances to the next complete batch; a
     * negative direction moves to the previous one.
     */
    public static int nextValidTradeQuantity(MarketItemConfig config, double stock,
                                              MarketDemandContext context, int quantity,
                                              TradeSide side, int direction) {
        if (config.tradeType() == MarketTradeType.FIXED) {
            int current = Math.max(0, quantity);
            int step = config.minimumTradeSize();
            return direction < 0 ? Math.max(0, current - step) : current + step;
        }
        if (direction == 0 || config.metric() != MarketMetric.TARGET_RATIO) {
            return Math.max(0, quantity);
        }
        int current = Math.max(0, quantity);
        if (direction < 0 && current == 0) {
            return 0;
        }

        double runningStock = Math.floor(Math.max(0.0, stock));
        int previous = 0;
        int cumulative = 0;
        int limit = Math.max(current + 1, 4096);
        while (cumulative <= limit) {
            int batch = tradeBatchSize(config, runningStock, context);
            int next = cumulative + batch;
            if (direction < 0) {
                if (next >= current) {
                    return previous;
                }
                previous = next;
            } else if (next > current || (current == 0 && cumulative == 0)) {
                return next;
            }
            cumulative = next;
            runningStock += side == TradeSide.BUY ? -batch : batch;
        }
        return current;
    }

    /** Returns the largest complete dynamic-batch quantity within a limit. */
    public static int maxValidBatchTradeQuantity(MarketItemConfig config, double stock,
                                                 MarketDemandContext context, int limit,
                                                 TradeSide side, int maxEmeralds) {
        if (config.tradeType() == MarketTradeType.FIXED) {
            int byQuantity = Math.max(0, limit) / config.minimumTradeSize()
                    * config.minimumTradeSize();
            if (side == TradeSide.BUY) {
                if (!config.supportsFixedBuy()) {
                    return 0;
                }
                int byEmeralds = Math.max(0, maxEmeralds) / config.fixedEmeraldAmount(side)
                        * config.minimumTradeSize();
                return Math.min(byQuantity, byEmeralds);
            }
            return config.supportsFixedSell() ? byQuantity : 0;
        }
        if (config.metric() != MarketMetric.TARGET_RATIO) {
            return 0;
        }
        double runningStock = Math.floor(Math.max(0.0, stock));
        int valid = 0;
        int emeralds = 0;
        int safeLimit = Math.max(0, limit);
        while (valid < safeLimit) {
            if (side == TradeSide.SELL && buyRefusedAtHardStop(config, runningStock, context)) {
                break;
            }
            int batch = tradeBatchSize(config, runningStock, context);
            if (valid + batch > safeLimit || (side == TradeSide.BUY && batch > runningStock)) {
                break;
            }
            double mid = midRate(config, runningStock, context);
            int batchEmeralds = settleBatchEmeralds(batch / mid, side);
            if (side == TradeSide.BUY && emeralds + batchEmeralds > maxEmeralds) {
                break;
            }
            emeralds += batchEmeralds;
            valid += batch;
            runningStock += side == TradeSide.BUY ? -batch : batch;
        }
        return valid;
    }

    private record NormalizedBand(double lowEdge, double highEdge) {
    }

    /**
     * Prices a trade against the stock level immediately before each unit or
     * dynamic batch is exchanged. Target-ratio batches settle independently;
     * an incomplete trailing batch is left untraded after earlier batches settle.
     */
    public static MarketTradeQuote quote(MarketItemConfig config, double stock,
                                         MarketDemandContext context, int quantity, TradeSide side) {
        if (config.tradeType() == MarketTradeType.FIXED) {
            return fixedQuote(config, stock, quantity, side);
        }
        double safeStock = Math.max(0.0, stock);
        if (config.metric() == MarketMetric.TARGET_RATIO) {
            safeStock = Math.floor(safeStock);
        }
        double currentMid = midRate(config, safeStock, context);
        if (quantity < config.minimumTradeSize()) {
            return MarketTradeQuote.invalid("quantity_below_minimum", quantity, side,
                    currentMid, safeStock);
        }
        if (side == TradeSide.SELL && buyRefusedAtHardStop(config, safeStock, context)) {
            return MarketTradeQuote.invalid("market_refuses_buying", quantity, side,
                    currentMid, safeStock);
        }
        if (config.metric() != MarketMetric.TARGET_RATIO
                && side == TradeSide.BUY && quantity > safeStock) {
            return MarketTradeQuote.invalid("insufficient_market_stock", quantity, side,
                    currentMid, safeStock);
        }
        double runningStock = safeStock;
        double rawEmeralds;
        int emeralds;
        int executedQuantity = quantity;
        if (config.metric() == MarketMetric.TARGET_RATIO) {
            rawEmeralds = 0.0;
            emeralds = 0;
            int remaining = quantity;
            executedQuantity = 0;
            while (remaining > 0) {
                if (side == TradeSide.SELL && buyRefusedAtHardStop(config, runningStock, context)) {
                    if (executedQuantity == 0) {
                        return MarketTradeQuote.invalid("market_refuses_buying", quantity, side,
                                currentMid, runningStock);
                    }
                    break;
                }
                int batch = tradeBatchSize(config, runningStock, context);
                if (remaining < batch) {
                    if (executedQuantity == 0) {
                        return MarketTradeQuote.invalid("trade_batch_size", quantity, side,
                                currentMid, runningStock);
                    }
                    break;
                }
                if (side == TradeSide.BUY && batch > runningStock) {
                    if (executedQuantity == 0) {
                        return MarketTradeQuote.invalid("insufficient_market_stock", quantity, side,
                                currentMid, runningStock);
                    }
                    break;
                }
                double batchMid = midRate(config, runningStock, context);
                // A target-ratio batch is already a discrete one-emerald offer.
                // Do not apply the spread a second time or the baseline 4-for-1
                // offer would become 2 emeralds to buy.
                double batchRawEmeralds = batch / batchMid;
                rawEmeralds += batchRawEmeralds;
                emeralds += settleBatchEmeralds(batchRawEmeralds, side);
                remaining -= batch;
                executedQuantity += batch;
                runningStock += side == TradeSide.BUY ? -batch : batch;
            }
        } else {
            rawEmeralds = 0.0;
            for (int unit = 0; unit < quantity; unit++) {
                double mid = midRate(config, runningStock, context);
                double unitsPerEmerald = side == TradeSide.BUY
                        ? mid / (1.0 + config.bidAskSpread())
                        : mid * (1.0 + config.bidAskSpread());
                rawEmeralds += 1.0 / unitsPerEmerald;
                runningStock += side == TradeSide.BUY ? -1.0 : 1.0;
            }
            emeralds = side == TradeSide.BUY
                    ? (int) Math.ceil(rawEmeralds - 1.0e-9)
                    : (int) Math.floor(rawEmeralds + 1.0e-9);
        }

        if (config.metric() == MarketMetric.TARGET_RATIO && emeralds <= 0) {
            emeralds = 1;
        }
        double effectiveRate = emeralds == 0 ? 0.0 : executedQuantity / (double) emeralds;
        return new MarketTradeQuote(executedQuantity, side, currentMid,
                midRate(config, runningStock, context), rawEmeralds, emeralds,
                effectiveRate, runningStock, true, "");
    }

    private static MarketTradeQuote fixedQuote(MarketItemConfig config, double stock,
                                               int quantity, TradeSide side) {
        double safeStock = Math.max(0.0, stock);
        boolean bankSells = side == TradeSide.BUY;
        int fixedEmeraldAmount = config.fixedEmeraldAmount(side);
        double rate = fixedEmeraldAmount <= 0
                ? 0.0 : config.minimumTradeSize() / (double) fixedEmeraldAmount;
        if (fixedEmeraldAmount <= 0) {
            return MarketTradeQuote.invalid("fixed_trade_direction", quantity, side,
                    rate, safeStock);
        }
        if (quantity < config.minimumTradeSize()) {
            return MarketTradeQuote.invalid("quantity_below_minimum", quantity, side,
                    rate, safeStock);
        }
        if (quantity % config.minimumTradeSize() != 0) {
            return MarketTradeQuote.invalid("fixed_trade_size", quantity, side,
                    rate, safeStock);
        }
        if (bankSells && quantity > safeStock) {
            return MarketTradeQuote.invalid("insufficient_market_stock", quantity, side,
                    rate, safeStock);
        }
        int emeralds = Math.multiplyExact(quantity / config.minimumTradeSize(),
                fixedEmeraldAmount);
        double projectedStock = bankSells ? safeStock - quantity : safeStock + quantity;
        return new MarketTradeQuote(quantity, side, rate, rate, emeralds, emeralds,
                quantity / (double) emeralds, projectedStock, true, "");
    }

    private static boolean buyRefusedAtHardStop(MarketItemConfig config, double stock,
                                                MarketDemandContext context) {
        return config.hardStopMult() != null
                && config.metric() == MarketMetric.TARGET_RATIO
                && stock >= config.hardStopMult() * context.bankTarget();
    }

    private static int settleBatchEmeralds(double rawEmeralds, TradeSide side) {
        return side == TradeSide.BUY
                ? Math.max(1, (int) Math.ceil(rawEmeralds - 1.0e-9))
                : Math.max(1, (int) Math.floor(rawEmeralds + 1.0e-9));
    }
}
