package com.orangevillager61.emeraldcapitalism.registry;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.Potion;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registration owner for debug potions used to apply specific Zombkolaps phases. */
public final class ECAPPotions {

    private static final int DEBUG_PHASE_ONE_DURATION_TICKS = 600 * 20;

    private static final DeferredRegister<Potion> POTIONS =
            DeferredRegister.create(Registries.POTION, EmeraldCapitalism.MODID);

    private ECAPPotions() {
    }

    public static void register(IEventBus modEventBus) {
        POTIONS.register(modEventBus);
    }

    public static final DeferredHolder<Potion, Potion> ZOMBIE_VIRUS_PHASE_ONE = POTIONS.register(
            "zombie_virus_phase_one",
            () -> new Potion(
                    "zombie_virus_phase_one",
                    new MobEffectInstance(
                            ECAPEffects.ZOMBIE_VIRUS,
                            DEBUG_PHASE_ONE_DURATION_TICKS,
                            0,
                            false,
                            true,
                            true)));

    public static final DeferredHolder<Potion, Potion> ZOMBIE_VIRUS_PHASE_TWO = POTIONS.register(
            "zombie_virus_phase_two",
            () -> new Potion(
                    "zombie_virus_phase_two",
                    new MobEffectInstance(
                            ECAPEffects.ZOMBIE_VIRUS,
                            MobEffectInstance.INFINITE_DURATION,
                            0,
                            false,
                            false,
                            true)));
}
