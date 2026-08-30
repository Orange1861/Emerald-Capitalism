package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.world.Difficulty;

/** Small, Minecraft-facing policy boundary for configurable zombie door breaking. */
public final class ZombieDoorBreakingPolicy {

    private ZombieDoorBreakingPolicy() {
    }

    public static boolean allowsDifficulty(Difficulty difficulty, boolean anyDifficulty) {
        return anyDifficulty || difficulty == Difficulty.HARD;
    }

    public static boolean getsAbility(float randomRoll, int chancePercent) {
        return ZombieDoorBreakingRules.getsAbility(randomRoll, chancePercent);
    }
}
