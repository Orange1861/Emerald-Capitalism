package com.orangevillager61.emeraldcapitalism.world.village;

import java.util.Comparator;

/**
 * Deterministic ordering rules for village building planning.
 *
 * <p>The mod adapter converts Minecraft-facing providers and plans into these
 * plain keys, keeping priority and tie-breaking in one place.</p>
 */
public final class VillageBuildingOrder {
    private static final Comparator<ProviderKey> PROVIDER_COMPARATOR = Comparator
            .comparingInt((ProviderKey key) -> key.importance().weight()).reversed()
            .thenComparing(Comparator.comparingLong(ProviderKey::planningSizeHint).reversed())
            .thenComparing(ProviderKey::providerId);

    private static final Comparator<PlanKey> PLAN_COMPARATOR = Comparator
            .comparingInt((PlanKey key) -> key.importance().weight()).reversed()
            .thenComparing(Comparator.comparingLong(PlanKey::footprintArea).reversed())
            .thenComparing(PlanKey::providerId)
            .thenComparingInt(PlanKey::minX)
            .thenComparingInt(PlanKey::minZ);

    private VillageBuildingOrder() {
    }

    /** Provider-level ordering used before individual plans exist. */
    public static Comparator<ProviderKey> providerComparator() {
        return PROVIDER_COMPARATOR;
    }

    /** Plan-level ordering used for reservation and placement order. */
    public static Comparator<PlanKey> planComparator() {
        return PLAN_COMPARATOR;
    }

    public record ProviderKey(VillageBuildingImportance importance,
                              long planningSizeHint, String providerId) {
    }

    public record PlanKey(VillageBuildingImportance importance, long footprintArea,
                          String providerId, int minX, int minZ) {
    }
}
