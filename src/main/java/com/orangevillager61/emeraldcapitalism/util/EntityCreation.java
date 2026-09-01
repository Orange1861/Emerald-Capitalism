package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.Objects;

/** Version bridge for creating entities from server gameplay code and tests. */
public final class EntityCreation {
    private EntityCreation() {
    }

    public static <T extends Entity> T create(EntityType<T> type, ServerLevel level) {
        T entity;
//? if >=1.21.4 {
        entity = type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
//?} else {
/*        entity = type.create(level);
 *///?}
        return Objects.requireNonNull(entity, "Entity type cannot create an instance: " + type);
    }
}
