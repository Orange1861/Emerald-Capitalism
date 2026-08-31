package com.orangevillager61.emeraldcapitalism.world.bank;

import com.orangevillager61.emeraldcapitalism.Config;

/** Calculates the emerald-golem population supported by physical bank reserves. */
public final class EmeraldGolemCalculator {

    private EmeraldGolemCalculator() {}

    /**
     * Applies {@code ceil(scale * (sqrt(x) - offset) + base)} to a non-negative
     * emerald count, using the server's configured formula values.
     * A negative result is clamped to zero because a village cannot have a negative
     * target population.
     */
    public static int calculate(int emeraldCount) {
        return EmeraldGolemFormula.calculate(
                emeraldCount,
                new EmeraldGolemFormula.Parameters(
                        Config.emeraldGolemFormulaScale,
                        Config.emeraldGolemReserveOffset,
                        Config.emeraldGolemBaseGolems));
    }
}
