package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;

import javax.annotation.Nullable;

/** Version bridge for MobSpawnType becoming EntitySpawnReason. */
public final class SpawnReasonCompat {
    private SpawnReasonCompat() {
    }

    public static boolean isStructure(Object reason) {
//? if >=1.21.4 {
        return reason == net.minecraft.world.entity.EntitySpawnReason.STRUCTURE;
//?} else {
/*        return reason == net.minecraft.world.entity.MobSpawnType.STRUCTURE;
 *///?}
    }

    public static boolean isSpawnEgg(Object reason) {
//? if >=1.21.4 {
        return reason == net.minecraft.world.entity.EntitySpawnReason.SPAWN_ITEM_USE;
//?} else {
/*        return reason == net.minecraft.world.entity.MobSpawnType.SPAWN_EGG;
 *///?}
    }

    public static boolean isBreeding(Object reason) {
//? if >=1.21.4 {
        return reason == net.minecraft.world.entity.EntitySpawnReason.BREEDING;
//?} else {
/*        return reason == net.minecraft.world.entity.MobSpawnType.BREEDING;
 *///?}
    }

    @Nullable
    public static SpawnGroupData finalizeStructure(Mob mob, ServerLevel level,
                                                    DifficultyInstance difficulty,
                                                    @Nullable SpawnGroupData data) {
//? if >=1.21.4 {
        return net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(
                mob, level, difficulty, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE, data);
//?} else {
/*        return net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(
                mob, level, difficulty, net.minecraft.world.entity.MobSpawnType.STRUCTURE, data);
 *///?}
    }

    @Nullable
    public static SpawnGroupData finalizeSummoned(Mob mob, ServerLevel level,
                                                   DifficultyInstance difficulty,
                                                   @Nullable SpawnGroupData data) {
//? if >=1.21.4 {
        return net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(
                mob, level, difficulty, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED, data);
//?} else {
/*        return net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(
                mob, level, difficulty, net.minecraft.world.entity.MobSpawnType.MOB_SUMMONED, data);
 *///?}
    }

    @Nullable
    public static SpawnGroupData finalizeCommand(Mob mob, ServerLevel level,
                                                  DifficultyInstance difficulty,
                                                  @Nullable SpawnGroupData data) {
//? if >=1.21.4 {
        return net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(
                mob, level, difficulty, net.minecraft.world.entity.EntitySpawnReason.COMMAND, data);
//?} else {
/*        return net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(
                mob, level, difficulty, net.minecraft.world.entity.MobSpawnType.COMMAND, data);
 *///?}
    }
}
