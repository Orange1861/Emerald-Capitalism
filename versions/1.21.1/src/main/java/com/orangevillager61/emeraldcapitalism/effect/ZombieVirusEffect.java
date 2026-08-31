package com.orangevillager61.emeraldcapitalism.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/** The visible effect marker for the two-stage Zombie Plague illness. */
public final class ZombieVirusEffect extends MobEffect {

    public ZombieVirusEffect() {
        // Match vanilla poison's bottle tint until Zombie Plague has custom potion art.
        super(MobEffectCategory.HARMFUL, 0x87A363);
    }

}
