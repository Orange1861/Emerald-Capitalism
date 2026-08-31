package com.orangevillager61.emeraldcapitalism.util;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Low-overhead cumulative timings for expensive server operations. */
public final class PerformanceTimingCounters {

    public enum Operation {
        VILLAGE_FULL_SCAN,
        VILLAGE_GENERATION,
        LUMBERJACK_SEARCH,
        PUMPKIN_SEARCH,
        POI_DYNAMIC_REFRESH,
        BANK_CHEST_CACHE_REBUILD
    }

    public record Snapshot(long calls, long totalNanos, long maximumNanos) {
        public double averageMillis() {
            return calls == 0 ? 0.0D : totalNanos / (double) calls / 1_000_000.0D;
        }

        public double maximumMillis() {
            return maximumNanos / 1_000_000.0D;
        }
    }

    private static final Map<Operation, Counter> COUNTERS = new EnumMap<>(Operation.class);

    static {
        for (Operation operation : Operation.values()) {
            COUNTERS.put(operation, new Counter());
        }
    }

    private PerformanceTimingCounters() {
    }

    public static <T> T measure(Operation operation, Supplier<T> action) {
        long started = System.nanoTime();
        try {
            return action.get();
        } finally {
            record(operation, System.nanoTime() - started);
        }
    }

    public static void measure(Operation operation, Runnable action) {
        measure(operation, () -> {
            action.run();
            return null;
        });
    }

    public static Map<Operation, Snapshot> snapshot() {
        Map<Operation, Snapshot> result = new EnumMap<>(Operation.class);
        COUNTERS.forEach((operation, counter) -> result.put(operation, counter.snapshot()));
        return Map.copyOf(result);
    }

    public static void clear() {
        COUNTERS.values().forEach(Counter::clear);
    }

    private static void record(Operation operation, long elapsedNanos) {
        COUNTERS.get(operation).record(Math.max(0L, elapsedNanos));
    }

    private static final class Counter {
        private final LongAdder calls = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maximumNanos = new AtomicLong();

        private void record(long elapsedNanos) {
            calls.increment();
            totalNanos.add(elapsedNanos);
            maximumNanos.accumulateAndGet(elapsedNanos, Math::max);
        }

        private Snapshot snapshot() {
            return new Snapshot(calls.sum(), totalNanos.sum(), maximumNanos.get());
        }

        private void clear() {
            calls.reset();
            totalNanos.reset();
            maximumNanos.set(0L);
        }
    }
}
