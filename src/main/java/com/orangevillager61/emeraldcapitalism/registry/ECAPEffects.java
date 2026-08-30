package com.orangevillager61.emeraldcapitalism.registry;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.effect.ZombieSmellEffect;
import com.orangevillager61.emeraldcapitalism.effect.ZombieVirusEffect;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

/** Registration owner for mod mob effects. */
public final class ECAPEffects {

    private static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, EmeraldCapitalism.MODID);

    private ECAPEffects() {
    }

    public static void register(IEventBus modEventBus) {
        EFFECTS.register(modEventBus);
    }

    public static final DeferredHolder<MobEffect, MobEffect> ZOMBIE_VIRUS = EFFECTS.register(
            "zombie_virus", ZombieVirusEffect::new);

    public static final DeferredHolder<MobEffect, MobEffect> ZOMBIE_SMELL = EFFECTS.register(
            "zombie_smell", ZombieSmellEffect::new);
}
