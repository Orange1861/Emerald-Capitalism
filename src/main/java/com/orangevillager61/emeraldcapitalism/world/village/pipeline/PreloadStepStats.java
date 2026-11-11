package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.util.PerformanceTimingCounters;

/**
 * Per-step breakdown of one BUILD_CACHES tick, reset at the start of each step.
 * Nanosecond fields are only filled while detailed timing is enabled.
 */
final class PreloadStepStats {
    int ticketsAdded;
    int futuresStarted;
    int alreadyLoaded;
    long pollNanos;
    long ticketNanos;
    long futureNanos;
    long roadPrepareNanos;
    long spatialCacheNanos;

    void reset() {
        ticketsAdded = 0;
        futuresStarted = 0;
        alreadyLoaded = 0;
        pollNanos = 0L;
        ticketNanos = 0L;
        futureNanos = 0L;
        roadPrepareNanos = 0L;
        spatialCacheNanos = 0L;
    }

    /** Records the timing and returns the elapsed nanoseconds, or zero when timing was off. */
    static long finish(PerformanceTimingCounters.Operation operation, long started) {
        PerformanceTimingCounters.finishTiming(operation, started);
        return started == Long.MIN_VALUE ? 0L : Math.max(0L, System.nanoTime() - started);
    }

    String describe() {
        return String.format(java.util.Locale.ROOT,
                "tickets=%d futures=%d alreadyLoaded=%d pollMs=%.1f ticketMs=%.1f futureMs=%.1f "
                        + "roadPrepareMs=%.1f spatialCacheMs=%.1f",
                ticketsAdded, futuresStarted, alreadyLoaded,
                pollNanos / 1_000_000.0D, ticketNanos / 1_000_000.0D, futureNanos / 1_000_000.0D,
                roadPrepareNanos / 1_000_000.0D, spatialCacheNanos / 1_000_000.0D);
    }
}
