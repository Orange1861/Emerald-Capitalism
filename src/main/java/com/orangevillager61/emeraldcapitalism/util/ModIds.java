package com.orangevillager61.emeraldcapitalism.util;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.resources.ResourceLocation;

/** Centralizes construction of identifiers owned by this mod. */
public final class ModIds {

    private ModIds() {}

    /**
     * Builds a mod-owned identifier without accepting a namespace or silently
     * normalizing an invalid path.
     */
    public static ResourceLocation id(String path) {
        if (path == null || path.isEmpty() || !ResourceLocation.isValidPath(path)) {
            throw new IllegalArgumentException("Invalid " + EmeraldCapitalism.MODID + " resource path: " + path);
        }
        return ResourceLocation.fromNamespaceAndPath(EmeraldCapitalism.MODID, path);
    }
}
