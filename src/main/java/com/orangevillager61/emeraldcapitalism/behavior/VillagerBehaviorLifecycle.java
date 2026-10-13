package com.orangevillager61.emeraldcapitalism.behavior;

import net.minecraft.world.entity.npc.Villager;

/**
 * Lifecycle callbacks for a registered behavior definition.
 *
 * <p>Callbacks are deliberately small. The registry owns ordering,
 * eligibility, and declared control/memory conflicts; a behavior owns its task
 * effects and must release any request or reservation in cancel/finish.</p>
 */
public interface VillagerBehaviorLifecycle {

    /** Called once per server tick while the definition remains bound. */
    default void tick(Villager villager) {
    }

    default void start(Villager villager) {
    }

    /** Called when a higher-priority conflicting behavior takes ownership. */
    default void suspend(Villager villager) {
    }

    /** Called when a previously suspended behavior can safely resume. */
    default void resume(Villager villager) {
    }

    default void cancel(Villager villager) {
    }

    /** Called by the behavior through {@link VillagerBehaviorRegistry#finish}. */
    default void finish(Villager villager) {
    }
}
