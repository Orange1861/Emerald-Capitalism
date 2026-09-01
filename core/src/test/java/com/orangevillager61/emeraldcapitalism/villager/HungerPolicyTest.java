package com.orangevillager61.emeraldcapitalism.villager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HungerPolicyTest {

    @Test
    void eatingThresholdDependsOnWoundedStateAndBreedingWins() {
        assertTrue(HungerPolicy.shouldEat(17, true, false));
        assertTrue(HungerPolicy.shouldEat(14, false, false));
        assertFalse(HungerPolicy.shouldEat(17, true, true));
        assertFalse(HungerPolicy.shouldHeal(17, true));
        assertTrue(HungerPolicy.shouldHeal(18, true));
    }

    @Test
    void splitCadencePreservesAbsoluteHungerTiming() {
        assertEquals(40, HungerPolicy.RESPONSIVE_UPDATE_INTERVAL);
        assertEquals(80, HungerPolicy.HUNGER_DECREASE_UPDATE_INTERVAL);
        assertEquals(0, HungerPolicy.TICKS_PER_HEAL % HungerPolicy.RESPONSIVE_UPDATE_INTERVAL);
        assertEquals(0, HungerPolicy.TICKS_PER_HUNGER_DECREASE
                % HungerPolicy.HUNGER_DECREASE_UPDATE_INTERVAL);
        assertEquals(0, HungerPolicy.TICKS_PER_STARVATION_DAMAGE
                % HungerPolicy.HUNGER_DECREASE_UPDATE_INTERVAL);
    }
}
