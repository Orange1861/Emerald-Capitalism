package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.world.level.LevelHeightAccessor;

/** Version bridge for the renamed world-height accessors. */
public final class WorldHeightCompat {
    private WorldHeightCompat() {
    }

    public static int min(LevelHeightAccessor level) {
//? if >=1.21.4 {
        return level.getMinY();
//?} else {
/*        return level.getMinBuildHeight();
 *///?}
    }

    public static int max(LevelHeightAccessor level) {
//? if >=1.21.4 {
        return level.getMaxY();
//?} else {
/*        return level.getMaxBuildHeight();
 *///?}
    }
}
