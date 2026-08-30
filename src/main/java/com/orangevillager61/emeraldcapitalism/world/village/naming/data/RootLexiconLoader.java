package com.orangevillager61.emeraldcapitalism.world.village.naming.data;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.resources.ResourceLocation;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class RootLexiconLoader {
    private static final String ROOTS_PATH = "village_naming/roots.json";

    private RootLexiconLoader() {
    }

    public static RootLexicon load(ResourceManager resourceManager) {
        ResourceLocation rootsLocation = ModIds.id(ROOTS_PATH);
        Optional<Resource> maybeResource = resourceManager.getResource(rootsLocation);
        if (maybeResource.isEmpty()) {
            throw new IllegalStateException("Missing canonical roots resource: " + rootsLocation);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                maybeResource.get().open(), StandardCharsets.UTF_8))) {
            return RootLexiconParser.parse(reader);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load roots from " + rootsLocation, e);
        }
    }
}
