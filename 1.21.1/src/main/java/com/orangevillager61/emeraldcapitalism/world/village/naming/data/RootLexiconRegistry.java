package com.orangevillager61.emeraldcapitalism.world.village.naming.data;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Optional;

public final class RootLexiconRegistry {
    private static volatile RootLexicon loadedLexicon;

    private RootLexiconRegistry() {
    }

    public static void load(ResourceManager resourceManager) {
        loadedLexicon = RootLexiconLoader.load(resourceManager);
        EmeraldCapitalism.LOGGER.info(
                "Loaded {} canonical village naming roots ({} enabled)",
                loadedLexicon.roots().size(),
                loadedLexicon.enabledRoots().size()
        );
    }

    public static Optional<RootLexicon> get() {
        return Optional.ofNullable(loadedLexicon);
    }
}
