package com.orangevillager61.emeraldcapitalism.util;

import java.util.EnumMap;
import java.util.Locale;
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
        LUMBERJACK_LOCAL_TREE_PROBE,
        LUMBERJACK_FURNACE_SEARCH,
        PUMPKIN_SEARCH,
        MOVEMENT_REQUEST_SUBMISSION,
        MOVEMENT_REQUEST_REPLAN,
        MOVEMENT_REQUEST_PREEMPTION,
        MOVEMENT_REQUEST_REJECTION,
        MOVEMENT_MANAGER_TIME,
        BEHAVIOR_REGISTRY_TIME,
        MOVEMENT_PATH_PLAN_ATTEMPT,
        MOVEMENT_ARRIVAL,
        MOVEMENT_RETRY,
        MOVEMENT_ABANDONED,
        POI_DYNAMIC_REFRESH,
        BANK_CHEST_CACHE_REBUILD,
        BANK_INVENTORY_TOTAL_REFRESH
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

    /** Returns a compact, log-friendly snapshot for server shutdown diagnostics. */
    public static String formatSnapshot() {
        StringBuilder result = new StringBuilder();
        for (Operation operation : Operation.values()) {
            Snapshot snapshot = COUNTERS.get(operation).snapshot();
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(operation.name().toLowerCase(Locale.ROOT))
                    .append("{calls=").append(snapshot.calls())
                    .append(",totalMs=")
                    .append(String.format(Locale.ROOT, "%.3f", snapshot.totalNanos() / 1_000_000.0D))
                    .append('}');
        }
        return result.toString();
    }

    public static void clear() {
        COUNTERS.values().forEach(Counter::clear);
    }

    /** Records a bounded state transition that has no useful duration. */
    public static void recordEvent(Operation operation) {
        COUNTERS.get(operation).record(0L);
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
