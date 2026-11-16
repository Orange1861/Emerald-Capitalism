package com.orangevillager61.emeraldcapitalism.world.forestry;

/**
 * What one claim on a lumbermill furnace has put into it and what it may take
 * out. The claim reserves a furnace and holds no items: the holder physically
 * loads logs and fuel and takes charcoal, and this record only tracks the three
 * furnace slots against what the claim placed.
 *
 * <p>A claim moves through loading, smelting, and collecting. It never takes an
 * item it did not place, and it compares the slots with its record on every
 * check so a player, hopper, or other mod changing the furnace is noticed.</p>
 */
public final class FurnaceClaim {

    public enum Stage { LOADING, SMELTING, COLLECTING }

    /**
     * The furnace slots as seen now. The foreign flags are true when a slot holds
     * an item the pool did not place (another player's log, fuel, or product).
     */
    public record Observation(int inputLogs, int outputCharcoal,
                              boolean foreignInput, boolean foreignOutput, boolean foreignFuel) {
        public Observation {
            if (inputLogs < 0 || outputCharcoal < 0) {
                throw new IllegalArgumentException("slot counts must not be negative");
            }
        }

        public boolean foreign() {
            return foreignInput || foreignOutput || foreignFuel;
        }

        public boolean empty() {
            return inputLogs == 0 && outputCharcoal == 0 && !foreign();
        }
    }

    /** What a check found and what the holder should do. */
    public record Reconciliation(int refundedConversions, int removedCharcoalCounted,
                                 int takeCharcoal, boolean foreignRemains, Verdict verdict) {
    }

    public enum Verdict {
        /** Keep the claim and keep working. */
        CONTINUE,
        /** Both slots verified empty: release the claim. */
        RELEASE_EMPTY,
        /** Only items the claim did not place remain: release and mark the furnace occupied. */
        RELEASE_OCCUPIED
    }

    private Stage stage = Stage.LOADING;
    private int logsLoaded;
    private int fuelPlaced;
    private int refunded;
    private int charcoalCollected;
    private int removedCharcoalCounted;
    private int lastInputLogs;
    private int lastOutputCharcoal;

    public Stage stage() {
        return stage;
    }

    public int logsLoaded() {
        return logsLoaded;
    }

    public int fuelPlaced() {
        return fuelPlaced;
    }

    public int charcoalCollected() {
        return charcoalCollected;
    }

    public int refundedConversions() {
        return refunded;
    }

    /** Charcoal the claim still owes the holder: loaded, minus refunded, collected, and taken by others. */
    public int charcoalOutstanding() {
        return Math.max(0, logsLoaded - refunded - charcoalCollected - removedCharcoalCounted);
    }

    /** Records logs the holder put into the input slot. */
    public void recordLoaded(int logs) {
        if (logs <= 0) {
            throw new IllegalArgumentException("logs must be positive");
        }
        logsLoaded += logs;
        lastInputLogs += logs;
        if (stage == Stage.COLLECTING) {
            stage = Stage.SMELTING;
        }
    }

    /** Records fuel the holder put into the fuel slot. */
    public void recordFuel(int items) {
        if (items <= 0) {
            throw new IllegalArgumentException("items must be positive");
        }
        fuelPlaced += items;
    }

    /** Loading is finished; the furnace is working. */
    public void beginSmelting() {
        if (logsLoaded <= 0) {
            throw new IllegalStateException("nothing was loaded");
        }
        if (stage == Stage.LOADING) {
            stage = Stage.SMELTING;
        }
    }

    /** Records charcoal the holder took out. */
    public void recordCollected(int charcoal) {
        if (charcoal <= 0 || charcoal > lastOutputCharcoal) {
            throw new IllegalArgumentException("cannot collect " + charcoal + " from " + lastOutputCharcoal);
        }
        charcoalCollected += charcoal;
        lastOutputCharcoal -= charcoal;
    }

    /**
     * Rebuilds a claim after a reload from a saved furnace position alone: what
     * the furnace holds now is taken to be this claim's own batch.
     */
    public static FurnaceClaim adopt(Observation observation) {
        FurnaceClaim claim = new FurnaceClaim();
        claim.logsLoaded = observation.inputLogs() + observation.outputCharcoal();
        claim.lastInputLogs = observation.inputLogs();
        claim.lastOutputCharcoal = observation.outputCharcoal();
        claim.stage = Stage.COLLECTING;
        return claim;
    }

    /**
     * Compares the slots with the last check. Smelting moves a log from input to
     * output, so input and output change together by the same amount.
     * <ul>
     *   <li>Input fell without output rising: a log was removed before it
     *       smelted. One conversion is refunded, because the charcoal is still
     *       owed.</li>
     *   <li>Output fell without collection: charcoal was removed. It counts as a
     *       finished conversion; nothing is refunded.</li>
     * </ul>
     * If both happen between two checks the pair cannot be told apart from a
     * removed log; it is treated as a removed log, which keeps the charcoal owed
     * rather than losing it. Output that rose by more than the input fell is
     * foreign charcoal; it is never taken because the take is bounded by the
     * recorded count.
     */
    public Reconciliation reconcile(Observation observed) {
        int dIn = lastInputLogs - observed.inputLogs();
        int dOut = observed.outputCharcoal() - lastOutputCharcoal;
        int converted = 0;
        int removedInput = 0;
        int removedOutput = 0;
        if (dIn > 0) {
            converted = Math.min(dIn, Math.max(dOut, 0));
            removedInput = dIn - converted;
        }
        if (dOut < 0) {
            removedOutput = -dOut;
        }
        refunded += removedInput;
        removedCharcoalCounted += removedOutput;
        lastInputLogs = observed.inputLogs();
        lastOutputCharcoal = observed.outputCharcoal();

        if (stage == Stage.SMELTING && observed.inputLogs() == 0) {
            stage = Stage.COLLECTING;
        }
        int take = Math.min(observed.outputCharcoal(), charcoalOutstanding());

        Verdict verdict = Verdict.CONTINUE;
        if (observed.empty()) {
            verdict = Verdict.RELEASE_EMPTY;
        } else if (observed.foreign() && observed.inputLogs() == 0 && observed.outputCharcoal() == 0) {
            verdict = Verdict.RELEASE_OCCUPIED;
        }
        return new Reconciliation(removedInput, removedOutput, take, observed.foreign(), verdict);
    }
}
