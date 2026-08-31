package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.server.MinecraftServer;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Allows one heavy scan or generation slice per server tick and alternates
 * categories when both have work, preventing their spikes from stacking.
 */
public final class SharedScanGenerationBudget {

    public enum WorkType {
        SCAN,
        GENERATION
    }

    private static final Map<MinecraftServer, BudgetState> STATES = new IdentityHashMap<>();

    private SharedScanGenerationBudget() {
    }

    public static boolean tryAcquire(MinecraftServer server, WorkType workType) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(server, ignored -> new BudgetState())
                    .tryAcquire(server.getTickCount(), workType);
        }
    }

    public static void clearAll() {
        synchronized (STATES) {
            STATES.clear();
        }
    }

    static final class BudgetState {
        private long currentTick = Long.MIN_VALUE;
        private WorkType preferred = WorkType.SCAN;
        private WorkType deniedThisTick;
        private WorkType grantedType;
        private boolean granted;

        boolean tryAcquire(long tick, WorkType workType) {
            if (tick != currentTick) {
                if (deniedThisTick != null) {
                    preferred = deniedThisTick;
                }
                currentTick = tick;
                deniedThisTick = null;
                grantedType = null;
                granted = false;
            }
            if (granted) {
                if (workType != grantedType) {
                    deniedThisTick = workType;
                }
                return false;
            }
            if (workType != preferred) {
                deniedThisTick = workType;
                return false;
            }
            granted = true;
            grantedType = workType;
            return true;
        }
    }
}
