package com.orangevillager61.emeraldcapitalism.util;

import com.mojang.datafixers.DataFixer;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.nio.file.Path;

/** Bridges the saved-data storage constructor and flush method changed in 1.21.4. */
public final class DimensionDataStorageCompat {
    private DimensionDataStorageCompat() {
    }

    public static DimensionDataStorage create(Path directory, DataFixer fixer,
                                               HolderLookup.Provider registries) {
//? if >=1.21.4 {
        return new DimensionDataStorage(directory, fixer, registries);
//?} else {
/*        return new DimensionDataStorage(directory.toFile(), fixer, registries);
 *///?}
    }

    public static void save(DimensionDataStorage storage) {
//? if >=1.21.4 {
        storage.saveAndJoin();
//?} else {
/*        storage.save();
 *///?}
    }
}
