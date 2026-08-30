package com.orangevillager61.emeraldcapitalism.world.bank;

/** Pure reserve-to-population calculation used by the Minecraft bank adapter. */
public final class EmeraldGolemFormula {
    private EmeraldGolemFormula() {
    }

    public record Parameters(double scale, double reserveOffset, double baseGolems) {
    }

    /**
     * Applies {@code ceil(scale * (sqrt(x) - offset) + base)} to a non-negative
     * emerald count and clamps the resulting population target to zero.
     */
    public static int calculate(int emeraldCount, Parameters parameters) {
        int safeEmeraldCount = Math.max(0, emeraldCount);
        double rawTarget = parameters.scale()
                * (Math.sqrt(safeEmeraldCount) - parameters.reserveOffset())
                + parameters.baseGolems();
        return Math.max(0, (int) Math.ceil(rawTarget));
    }
}
