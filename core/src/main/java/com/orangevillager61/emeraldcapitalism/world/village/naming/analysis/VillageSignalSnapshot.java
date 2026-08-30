package com.orangevillager61.emeraldcapitalism.world.village.naming.analysis;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class VillageSignalSnapshot {
    private final EnumMap<VillageSignal, Double> values;

    private VillageSignalSnapshot(EnumMap<VillageSignal, Double> values) {
        this.values = values;
    }

    public double value(VillageSignal signal) {
        return values.getOrDefault(signal, 0.0);
    }

    public Map<VillageSignal, Double> all() {
        return Collections.unmodifiableMap(values);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final EnumMap<VillageSignal, Double> values = new EnumMap<>(VillageSignal.class);

        public Builder with(VillageSignal signal, double value) {
            values.put(signal, value);
            return this;
        }

        public VillageSignalSnapshot build() {
            return new VillageSignalSnapshot(new EnumMap<>(values));
        }
    }
}
