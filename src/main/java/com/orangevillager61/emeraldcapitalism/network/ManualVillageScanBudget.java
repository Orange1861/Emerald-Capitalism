package com.orangevillager61.emeraldcapitalism.network;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Shared scan budget limiter for heavy manual village scans. */
public final class ManualVillageScanBudget {

    private static final long WINDOW_TICKS = 20L;
    private static final int MAX_REQUESTS_PER_WINDOW = 2;

    private record BudgetWindow(long windowStartTick, int used) {}
    public record AcquireResult(boolean granted, long retryAfterTicks) {}

    private static final Map<ResourceKey<Level>, BudgetWindow> WINDOWS = new ConcurrentHashMap<>();

    private ManualVillageScanBudget() {}

    public static AcquireResult tryAcquire(ServerLevel level) {
        long now = level.getGameTime();
        ResourceKey<Level> key = level.dimension();
        BudgetWindow window = WINDOWS.get(key);

        if (window == null || now - window.windowStartTick() >= WINDOW_TICKS) {
            WINDOWS.put(key, new BudgetWindow(now, 1));
            return new AcquireResult(true, 0);
        }

        if (window.used() >= MAX_REQUESTS_PER_WINDOW) {
            long retryAfter = Math.max(1L, WINDOW_TICKS - (now - window.windowStartTick()));
            return new AcquireResult(false, retryAfter);
        }

        WINDOWS.put(key, new BudgetWindow(window.windowStartTick(), window.used() + 1));
        return new AcquireResult(true, 0);
    }

    public static void clearAll() {
        WINDOWS.clear();
    }
}
