package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZombieDoorBreakingPolicyTest {

    @Test
    void anyDifficultyAllowsEveryDifficulty() {
        assertTrue(ZombieDoorBreakingPolicy.allowsDifficulty(Difficulty.PEACEFUL, true));
        assertTrue(ZombieDoorBreakingPolicy.allowsDifficulty(Difficulty.EASY, true));
        assertTrue(ZombieDoorBreakingPolicy.allowsDifficulty(Difficulty.NORMAL, true));
        assertTrue(ZombieDoorBreakingPolicy.allowsDifficulty(Difficulty.HARD, true));
    }

    @Test
    void disabledAnyDifficultyRetainsVanillaHardOnlyRule() {
        assertFalse(ZombieDoorBreakingPolicy.allowsDifficulty(Difficulty.PEACEFUL, false));
        assertFalse(ZombieDoorBreakingPolicy.allowsDifficulty(Difficulty.EASY, false));
        assertFalse(ZombieDoorBreakingPolicy.allowsDifficulty(Difficulty.NORMAL, false));
        assertTrue(ZombieDoorBreakingPolicy.allowsDifficulty(Difficulty.HARD, false));
    }

}
