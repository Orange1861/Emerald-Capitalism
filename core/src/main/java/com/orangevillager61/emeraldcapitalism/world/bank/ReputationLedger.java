package com.orangevillager61.emeraldcapitalism.world.bank;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Platform-free saturated player reputation values for the banking system. */
public final class ReputationLedger {
    private final Map<UUID, Integer> reputations = new HashMap<>();

    public ReputationLedger() {
    }

    public ReputationLedger(Map<UUID, Integer> reputations) {
        Objects.requireNonNull(reputations, "reputations").forEach((uuid, reputation) -> {
            Objects.requireNonNull(uuid, "reputation player id");
            Objects.requireNonNull(reputation, "reputation");
            if (reputation != 0) {
                this.reputations.put(uuid, reputation);
            }
        });
    }

    public int get(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return reputations.getOrDefault(playerId, 0);
    }

    /** Applies a delta with saturation at the signed integer bounds. */
    public int adjust(UUID playerId, int delta) {
        Objects.requireNonNull(playerId, "playerId");
        if (delta == 0) {
            return get(playerId);
        }
        long updated = (long) get(playerId) + delta;
        int clamped = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, updated));
        if (clamped == 0) {
            reputations.remove(playerId);
        } else {
            reputations.put(playerId, clamped);
        }
        return clamped;
    }

    public Map<UUID, Integer> reputations() {
        return Collections.unmodifiableMap(reputations);
    }
}
