package com.orangevillager61.emeraldcapitalism.api;

import net.minecraft.world.damagesource.DamageSource;

public interface VillagerDamageTracker {
    void emeraldcapitalism$recordDamage(DamageSource source, float amount);

    /** Suppresses the next player-damage gossip update for this villager. */
    void emeraldcapitalism$suppressNextPlayerDamageReputation();
}
