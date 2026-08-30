package com.orangevillager61.emeraldcapitalism.villager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HungerStateTest {

    @Test
    void hungerIsBoundedAndEatingCompletesAfterItsConfiguredDuration() {
        HungerState state = new HungerState();
        state.setHungerLevel(100);
        assertEquals(HungerPolicy.MAX_HUNGER, state.hungerLevel());

        state.startEating(3, 4, 2);
        assertTrue(state.isEating());
        assertFalse(state.tickEating());
        assertTrue(state.tickEating());
        assertEquals(4, state.finishEating());
        assertFalse(state.isEating());
    }
}
