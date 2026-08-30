package com.orangevillager61.emeraldcapitalism.world.bank;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmeraldGolemFormulaTest {
    private static final EmeraldGolemFormula.Parameters DEFAULTS =
            new EmeraldGolemFormula.Parameters(0.24D, 6.0D, 1.0D);

    @Test
    void clampsNegativeReservesAndNegativePopulationTargets() {
        assertEquals(0, EmeraldGolemFormula.calculate(-1, DEFAULTS));
        assertEquals(0, EmeraldGolemFormula.calculate(0, DEFAULTS));
    }

    @Test
    void preservesCeilingAtFormulaBoundaries() {
        assertEquals(1, EmeraldGolemFormula.calculate(4, DEFAULTS));
        assertEquals(2, EmeraldGolemFormula.calculate(100, DEFAULTS));
        assertEquals(3, EmeraldGolemFormula.calculate(144, DEFAULTS));
    }

    @Test
    void acceptsExplicitFormulaParameters() {
        EmeraldGolemFormula.Parameters parameters =
                new EmeraldGolemFormula.Parameters(1.0D, 0.0D, 0.0D);

        assertEquals(3, EmeraldGolemFormula.calculate(9, parameters));
    }
}
