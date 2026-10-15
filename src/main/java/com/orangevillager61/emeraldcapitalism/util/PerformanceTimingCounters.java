package com.orangevillager61.emeraldcapitalism.util;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
        FARM_TEMPLATE_PLACEMENT,
        MOVEMENT_REQUEST_SUBMISSION,
        MOVEMENT_REQUEST_REPLAN,
        MOVEMENT_REQUEST_PREEMPTION,
        MOVEMENT_REQUEST_REJECTION,
        MOVEMENT_MANAGER_TIME,
        BEHAVIOR_REGISTRY_TIME,
        MOVEMENT_PATH_PLAN_ATTEMPT,
        MOVEMENT_PATH_OBJECT_CREATED,
        MOVEMENT_HELPER_PATH_PLAN_ATTEMPT,
        MOVEMENT_HELPER_PATH_OBJECT_CREATED,
        MOVEMENT_VANILLA_PATH_PLAN_ATTEMPT,
        MOVEMENT_VANILLA_PATH_OBJECT_CREATED,
        MOVEMENT_DESTINATION_REACHABLE,
        MOVEMENT_ARRIVAL,
        MOVEMENT_RETRY,
        MOVEMENT_ABANDONED,
        MOVEMENT_FAILURE,
        MOVEMENT_PREEMPTED,
        MOVEMENT_REPLACED,
        MOVEMENT_UNLOADED,
        MOVEMENT_SHUTDOWN,
        POI_DYNAMIC_REFRESH,
        BANK_CHEST_CACHE_REBUILD,
        BANK_INVENTORY_TOTAL_REFRESH,
        LADDER_SEGMENT_SCAN,
        LADDER_BLOCK_SCAN,
        LADDER_PURGE_VISIT
    }

    public record Snapshot(long calls, long totalNanos, long maximumNanos,
                           long p50Nanos, long p95Nanos, long p99Nanos) {
        public double averageMillis() {
            return calls == 0 ? 0.0D : totalNanos / (double) calls / 1_000_000.0D;
        }

        public double maximumMillis() {
            return maximumNanos / 1_000_000.0D;
        }

        public double p50Millis() {
            return p50Nanos / 1_000_000.0D;
        }

        public double p95Millis() {
            return p95Nanos / 1_000_000.0D;
        }

        public double p99Millis() {
            return p99Nanos / 1_000_000.0D;
        }
    }

    private static final Map<Operation, Counter> COUNTERS = new EnumMap<>(Operation.class);
    private static final AtomicInteger EVENT_COUNTING_SCOPES = new AtomicInteger();
    private static volatile boolean detailedTimingEnabled =
            Boolean.getBoolean("emeraldcapitalism.performanceTiming");
    private static volatile boolean eventCountingEnabled =
            Boolean.getBoolean("emeraldcapitalism.performanceEvents");

    static {
        for (Operation operation : Operation.values()) {
            COUNTERS.put(operation, new Counter());
        }
    }

    private PerformanceTimingCounters() {
    }

    public static <T> T measure(Operation operation, Supplier<T> action) {
        if (!detailedTimingEnabled) {
            return action.get();
        }
        long started = System.nanoTime();
        try {
            return action.get();
        } finally {
            record(operation, System.nanoTime() - started);
        }
    }

    /**
     * Times a path computation and, when event counting is enabled without
     * detailed timing, still counts the invocation. This keeps helper and
     * vanilla createPath calls visible in the same opt-in run.
     */
    public static <T> T measurePathPlan(Operation operation, Supplier<T> action) {
        if (detailedTimingEnabled) {
            return measure(operation, action);
        }
        recordEvent(operation);
        return action.get();
    }

    public static void measure(Operation operation, Runnable action) {
        measure(operation, () -> {
            action.run();
            return null;
        });
    }

    public static void setDetailedTimingEnabled(boolean enabled) {
        detailedTimingEnabled = enabled;
    }

    public static boolean isDetailedTimingEnabled() {
        return detailedTimingEnabled;
    }

    /**
     * Starts a timing scope without allocating a capturing lambda. The hot
     * movement manager uses this form so the disabled path does no clock read.
     */
    public static long startTiming(Operation operation) {
        return detailedTimingEnabled ? System.nanoTime() : Long.MIN_VALUE;
    }

    public static void finishTiming(Operation operation, long started) {
        if (started != Long.MIN_VALUE && detailedTimingEnabled) {
            record(operation, Math.max(0L, System.nanoTime() - started));
        }
    }

    public static void setEventCountingEnabled(boolean enabled) {
        eventCountingEnabled = enabled;
    }

    public static boolean isEventCountingEnabled() {
        return eventCountingEnabled || EVENT_COUNTING_SCOPES.get() > 0;
    }

    /** Enables event counting until the returned scope is closed. Scopes compose safely. */
    public static EventCountingScope beginEventCountingScope() {
        EVENT_COUNTING_SCOPES.incrementAndGet();
        return new EventCountingScope();
    }

    public static final class EventCountingScope implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        private EventCountingScope() {
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                EVENT_COUNTING_SCOPES.decrementAndGet();
            }
        }
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
                    .append(",avgMs=")
                    .append(String.format(Locale.ROOT, "%.3f", snapshot.averageMillis()))
                    .append(",p50Ms=")
                    .append(String.format(Locale.ROOT, "%.3f", snapshot.p50Millis()))
                    .append(",p95Ms=")
                    .append(String.format(Locale.ROOT, "%.3f", snapshot.p95Millis()))
                    .append(",p99Ms=")
                    .append(String.format(Locale.ROOT, "%.3f", snapshot.p99Millis()))
                    .append(",maxMs=")
                    .append(String.format(Locale.ROOT, "%.3f", snapshot.maximumMillis()))
                    .append('}');
        }
        return result.toString();
    }

    public static void clear() {
        COUNTERS.values().forEach(Counter::clear);
    }

    /** Records a bounded state transition that has no useful duration. */
    public static void recordEvent(Operation operation) {
        if (isEventCountingEnabled()) {
            COUNTERS.get(operation).record(0L);
        }
    }

    private static void record(Operation operation, long elapsedNanos) {
        COUNTERS.get(operation).record(Math.max(0L, elapsedNanos));
    }

    private static final class Counter {
        private static final int SAMPLE_CAPACITY = 8_192;
        private final LongAdder calls = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maximumNanos = new AtomicLong();
        private final long[] samples = new long[SAMPLE_CAPACITY];
        private long sampleCount;
        private long sampleCursor;

        private void record(long elapsedNanos) {
            calls.increment();
            totalNanos.add(elapsedNanos);
            maximumNanos.accumulateAndGet(elapsedNanos, Math::max);
            synchronized (samples) {
                samples[(int) (sampleCursor++ % SAMPLE_CAPACITY)] = elapsedNanos;
                if (sampleCount < SAMPLE_CAPACITY) {
                    sampleCount++;
                }
            }
        }

        private Snapshot snapshot() {
            long[] sampleCopy;
            synchronized (samples) {
                sampleCopy = new long[(int) sampleCount];
                System.arraycopy(samples, 0, sampleCopy, 0, sampleCopy.length);
            }
            java.util.Arrays.sort(sampleCopy);
            return new Snapshot(calls.sum(), totalNanos.sum(), maximumNanos.get(),
                    percentile(sampleCopy, 0.50D), percentile(sampleCopy, 0.95D),
                    percentile(sampleCopy, 0.99D));
        }

        private void clear() {
            calls.reset();
            totalNanos.reset();
            maximumNanos.set(0L);
            synchronized (samples) {
                java.util.Arrays.fill(samples, 0L);
                sampleCount = 0L;
                sampleCursor = 0L;
            }
        }

        private static long percentile(long[] values, double percentile) {
            if (values.length == 0) {
                return 0L;
            }
            int index = (int) Math.ceil(percentile * values.length) - 1;
            return values[Math.max(0, Math.min(index, values.length - 1))];
        }
    }
}
