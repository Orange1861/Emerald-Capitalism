package com.orangevillager61.emeraldcapitalism.navigation;

/**
 * Why a task stopped before completing, and what that does to its claims.
 *
 * <p>Whether a claim is released depends on its kind. A <em>durable</em> claim
 * holds physical progress that would be lost or stranded if dropped (a furnace
 * with a batch inside). An ordinary claim only reserves a target.</p>
 */
public enum TaskInterruption {
    PANIC(Effect.SUSPEND),
    SLEEP(Effect.SUSPEND),
    TRADE(Effect.SUSPEND),
    PREEMPTED(Effect.SUSPEND),
    FULL_INVENTORY(Effect.SUSPEND),
    NO_FUEL(Effect.SUSPEND),
    TARGET_INVALIDATED(Effect.FAIL),
    UNREACHABLE(Effect.FAIL),
    TIMEOUT(Effect.FAIL),
    /** The holder unloads, dies, or changes profession: it will not come back as itself. */
    UNLOAD(Effect.ABANDON),
    DEATH(Effect.ABANDON),
    PROFESSION_CHANGE(Effect.ABANDON);

    /** What the task does. */
    public enum Effect { SUSPEND, FAIL, ABANDON }

    /** What happens to one claim. */
    public enum ClaimDisposition {
        /** Give the claim up; the task reacquires after it resumes. */
        RELEASE,
        /** Keep the claim; the holder is expected back. */
        KEEP,
        /** The holder will not be back: the village takes the claim over. */
        HAND_TO_VILLAGE
    }

    private final Effect effect;

    TaskInterruption(Effect effect) {
        this.effect = effect;
    }

    public Effect effect() {
        return effect;
    }

    public TaskStage resultingStage() {
        return switch (effect) {
            case SUSPEND -> TaskStage.SUSPEND;
            case FAIL -> TaskStage.FAIL;
            case ABANDON -> TaskStage.ABANDON;
        };
    }

    /**
     * What happens to a claim. A durable claim is never released by an
     * interruption: it is kept, or handed to the village when the holder is gone.
     * An invalidated or unreachable durable claim is kept too, because only
     * verifying the furnace empty (or the furnace disappearing) releases it.
     */
    public ClaimDisposition dispositionFor(boolean durableClaim) {
        if (durableClaim) {
            return effect == Effect.ABANDON ? ClaimDisposition.HAND_TO_VILLAGE : ClaimDisposition.KEEP;
        }
        return ClaimDisposition.RELEASE;
    }
}
