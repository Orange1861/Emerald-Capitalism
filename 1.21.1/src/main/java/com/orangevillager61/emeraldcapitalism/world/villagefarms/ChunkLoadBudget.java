package com.orangevillager61.emeraldcapitalism.world.villagefarms;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.server.level.ServerLevel;

/**
 * Tracks how many chunks have been force-loaded by one village pipeline task.
 * Acts as an internal failsafe: if 125 chunks are loaded, something
 * unexpected is happening and further loading is refused with a warning.
 */
public class ChunkLoadBudget {

    private static final int FAILSAFE_LIMIT = 125;

    private int loaded;
    private boolean failsafeTriggered;

    public ChunkLoadBudget() {
        this.loaded = 0;
        this.failsafeTriggered = false;
    }

    /**
     * Ensures the chunk at (cx, cz) is loaded, force-loading if necessary.
     *
     * @return true if the chunk is available, false if the failsafe limit was reached
     */
    public boolean ensureLoaded(ServerLevel level, int cx, int cz) {
        if (level.hasChunk(cx, cz)) {
            return true;
        }
        if (loaded >= FAILSAFE_LIMIT) {
            if (!failsafeTriggered) {
                failsafeTriggered = true;
                EmeraldCapitalism.LOGGER.warn(
                        "[ECAP] Chunk load failsafe triggered: {} chunks force-loaded, refusing further loads. " +
                                "This is unexpected: farm placement should not require this many chunks.",
                        FAILSAFE_LIMIT);
            }
            return false;
        }
        level.getChunk(cx, cz);
        loaded++;
        return true;
    }

    /** Number of chunks force-loaded so far. */
    public int chunksLoaded() {
        return loaded;
    }
}
