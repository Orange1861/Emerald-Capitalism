package com.orangevillager61.emeraldcapitalism.market;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.datafixers.util.Pair;

import java.util.Locale;
import java.util.Optional;

/** JSON-facing codec schema for a market entry. */
record MarketDefinition(
        String id,
        double baseRate,
        double dailyConsumptionRate,
        boolean dailyConsumptionScalesWithPopulation,
        double lowEdge,
        double highEdge,
        Optional<Double> kScarcity,
        double floorRate,
        Optional<Double> kGlut,
        double ceilingRate,
        double bidAskSpread,
        int minimumTradeSize,
        MarketDemandSource demandSource,
        MarketMetric metric,
        Optional<Double> hardStopMult,
        int fixedEmeraldOutput,
        int fixedEmeraldCost
) {
    private static final Codec<Double> NON_NEGATIVE_DOUBLE = Codec.DOUBLE.validate(value ->
            Double.isFinite(value) && value >= 0.0
                    ? DataResult.success(value)
                    : DataResult.error(() -> "expected a finite non-negative number"));
    private static final Codec<Double> POSITIVE_DOUBLE = Codec.DOUBLE.validate(value ->
            Double.isFinite(value) && value > 0.0
                    ? DataResult.success(value)
                    : DataResult.error(() -> "expected a finite positive number"));

    private static final Codec<Integer> POSITIVE_INT = Codec.INT.validate(value ->
            value > 0
                    ? DataResult.success(value)
                    : DataResult.error(() -> "expected a positive integer"));

    // The service accepts at most 4096 items per request; keep fixed-price
    // multiplication inside the signed integer range for every valid request.
    private static final Codec<Integer> FIXED_EMERALD_AMOUNT =
            Codec.intRange(0, Integer.MAX_VALUE / MarketTradeService.MAX_TRADE_QUANTITY);

    private static final MapCodec<Integer> FIXED_EMERALD_COST_CODEC =
            FIXED_EMERALD_AMOUNT.optionalFieldOf("fixedEmeraldCost", 0);

    private static final Codec<MarketDefinition> BASE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(MarketDefinition::id),
            POSITIVE_DOUBLE.fieldOf("baseRate").forGetter(MarketDefinition::baseRate),
            POSITIVE_DOUBLE.fieldOf("dailyConsumptionRate").forGetter(MarketDefinition::dailyConsumptionRate),
            Codec.BOOL.optionalFieldOf("dailyConsumptionScalesWithPopulation", true)
                    .forGetter(MarketDefinition::dailyConsumptionScalesWithPopulation),
            NON_NEGATIVE_DOUBLE.fieldOf("lowMult").forGetter(MarketDefinition::lowEdge),
            NON_NEGATIVE_DOUBLE.fieldOf("highMult").forGetter(MarketDefinition::highEdge),
            NON_NEGATIVE_DOUBLE.optionalFieldOf("kScarcity")
                    .forGetter(MarketDefinition::kScarcity),
            POSITIVE_DOUBLE.fieldOf("floorRate").forGetter(MarketDefinition::floorRate),
            NON_NEGATIVE_DOUBLE.optionalFieldOf("kGlut")
                    .forGetter(MarketDefinition::kGlut),
            POSITIVE_DOUBLE.fieldOf("ceilingRate").forGetter(MarketDefinition::ceilingRate),
            NON_NEGATIVE_DOUBLE.optionalFieldOf("bidAskSpread", 0.10)
                    .forGetter(MarketDefinition::bidAskSpread),
            POSITIVE_INT.optionalFieldOf("minimumTradeSize", 1)
                    .forGetter(MarketDefinition::minimumTradeSize),
            Codec.STRING.optionalFieldOf("demandSource", "population")
                    .xmap(value -> MarketDemandSource.valueOf(value.toUpperCase(Locale.ROOT)),
                            MarketDemandSource::name)
                    .forGetter(MarketDefinition::demandSource),
            Codec.STRING.optionalFieldOf("metric", "DAYS")
                    .xmap(value -> MarketMetric.valueOf(value.toUpperCase(Locale.ROOT)),
                            MarketMetric::name)
                    .forGetter(MarketDefinition::metric),
            POSITIVE_DOUBLE.optionalFieldOf("hardStopMult")
                    .forGetter(MarketDefinition::hardStopMult),
            FIXED_EMERALD_AMOUNT.optionalFieldOf("fixedEmeraldOutput", 0)
                    .forGetter(MarketDefinition::fixedEmeraldOutput)
    ).apply(instance, (id, baseRate, dailyConsumptionRate,
                       dailyConsumptionScalesWithPopulation, lowEdge, highEdge,
                       kScarcity, floorRate, kGlut, ceilingRate, bidAskSpread,
                       minimumTradeSize, demandSource, metric, hardStopMult,
                       fixedEmeraldOutput) -> new MarketDefinition(id, baseRate,
                       dailyConsumptionRate, dailyConsumptionScalesWithPopulation,
                       lowEdge, highEdge, kScarcity, floorRate, kGlut, ceilingRate,
                       bidAskSpread, minimumTradeSize, demandSource, metric,
                       hardStopMult, fixedEmeraldOutput, 0)));

    static final Codec<MarketDefinition> CODEC = MapCodec.assumeMapUnsafe(BASE_CODEC).dependent(
            FIXED_EMERALD_COST_CODEC,
            definition -> Pair.of(definition.fixedEmeraldCost(), FIXED_EMERALD_COST_CODEC),
            (definition, fixedEmeraldCost) -> new MarketDefinition(definition.id(),
                    definition.baseRate(), definition.dailyConsumptionRate(),
                    definition.dailyConsumptionScalesWithPopulation(), definition.lowEdge(),
                    definition.highEdge(), definition.kScarcity(), definition.floorRate(),
                    definition.kGlut(), definition.ceilingRate(), definition.bidAskSpread(),
                    definition.minimumTradeSize(), definition.demandSource(), definition.metric(),
                    definition.hardStopMult(), definition.fixedEmeraldOutput(), fixedEmeraldCost)
    ).codec();

    MarketItemConfig toCoreConfig() {
        return new MarketItemConfig(id, baseRate, dailyConsumptionRate,
                dailyConsumptionScalesWithPopulation, lowEdge, highEdge,
                kScarcity.orElse(null), floorRate, kGlut.orElse(null), ceilingRate,
                bidAskSpread, minimumTradeSize, demandSource, metric,
                hardStopMult.orElse(null), fixedEmeraldOutput, fixedEmeraldCost);
    }
}
