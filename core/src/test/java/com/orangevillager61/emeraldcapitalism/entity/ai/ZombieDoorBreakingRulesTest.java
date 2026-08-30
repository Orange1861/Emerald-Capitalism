package com.orangevillager61.emeraldcapitalism.entity.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZombieDoorBreakingRulesTest {

    @Test
    void chanceUsesConfiguredPercentageBoundaries() {
        assertFalse(ZombieDoorBreakingRules.getsAbility(0.0F, 0));
        assertTrue(ZombieDoorBreakingRules.getsAbility(0.0F, 50));
        assertFalse(ZombieDoorBreakingRules.getsAbility(0.5F, 50));
        assertTrue(ZombieDoorBreakingRules.getsAbility(0.999F, 100));
    }
}
