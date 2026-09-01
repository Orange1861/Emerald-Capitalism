package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.world.level.chunk.LevelChunk;

/** Clears a test chunk's dirty flag across the chunk persistence APIs. */
public final class ChunkSaveCompat {
    private ChunkSaveCompat() {
    }

    public static void markClean(LevelChunk chunk) {
//? if >=1.21.4 {
        chunk.tryMarkSaved();
//?} else {
/*        chunk.setUnsaved(false);
 *///?}
    }
}
