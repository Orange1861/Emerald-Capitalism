package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

import com.orangevillager61.emeraldcapitalism.world.village.VillageBuildingOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime registry used by the single village-generation pipeline. */
public final class VillageBuildingRegistry {
    private static final Map<String, VillageBuildingProvider> PROVIDERS = new LinkedHashMap<>();

    private VillageBuildingRegistry() {
    }

    public static synchronized void register(VillageBuildingProvider provider) {
        String key = provider.id().toString();
        if (PROVIDERS.putIfAbsent(key, provider) != null) {
            throw new IllegalArgumentException("Duplicate village building provider: " + key);
        }
    }

    public static synchronized List<VillageBuildingProvider> orderedProviders() {
        List<VillageBuildingProvider> result = new ArrayList<>(PROVIDERS.values());
        result.sort(Comparator.comparing(
                provider -> new VillageBuildingOrder.ProviderKey(
                        provider.importance(), provider.planningSizeHint(), provider.id().toString()),
                VillageBuildingOrder.providerComparator()));
        return List.copyOf(result);
    }
}
