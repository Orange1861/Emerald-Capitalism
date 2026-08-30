package com.orangevillager61.emeraldcapitalism.villager;

import org.junit.jupiter.api.Test;

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
}
