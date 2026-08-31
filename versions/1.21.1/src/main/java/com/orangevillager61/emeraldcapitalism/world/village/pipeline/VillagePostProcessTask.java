package com.orangevillager61.emeraldcapitalism.world.village.pipeline;

/** A finalization job that can spread expensive work over multiple server ticks. */
@FunctionalInterface
public interface VillagePostProcessTask {
    /** @return true when complete, false when this task needs another pipeline tick */
    boolean processStep();
}
