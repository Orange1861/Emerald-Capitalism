package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Loads the templates of the generated village buildings while the server
 * starts. The first village would otherwise read and parse them during a
 * gameplay tick. The server's own template manager keeps them, so this adds
 * no cache of its own and nothing outlives the server.
 */
public final class VillageTemplateWarmup {
    private VillageTemplateWarmup() {
    }

    public static void loadGeneratedBuildingTemplates(MinecraftServer server) {
        Set<ResourceLocation> locations = new LinkedHashSet<>();
        locations.addAll(VillageLumbermillStructurePlacer.templateLocations());
        locations.addAll(VillageLibraryStructurePlacer.templateLocations());
        locations.addAll(VillageBankStructurePlacer.templateLocations());
        StructureTemplateManager templates = server.getStructureManager();
        long started = System.nanoTime();
        int missing = 0;
        for (ResourceLocation location : locations) {
            if (templates.get(location).isEmpty()) {
                missing++;
            }
        }
        EmeraldCapitalism.LOGGER.debug("[ECAP] Loaded {} village building templates in {} ms ({} missing)",
                locations.size() - missing, (System.nanoTime() - started) / 1_000_000L, missing);
    }
}
