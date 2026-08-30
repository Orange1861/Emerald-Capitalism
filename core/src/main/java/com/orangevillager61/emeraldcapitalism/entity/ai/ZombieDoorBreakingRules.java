package com.orangevillager61.emeraldcapitalism.entity.ai;

/** Platform-free probability rules for assigning zombie door-breaking ability. */
public final class ZombieDoorBreakingRules {

    private ZombieDoorBreakingRules() {
    }

    public static boolean getsAbility(float randomRoll, int chancePercent) {
        if (chancePercent <= 0) {
            return false;
        }
        if (chancePercent >= 100) {
            return true;
        }
        return randomRoll < chancePercent / 100.0F;
    }
}
