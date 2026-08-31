package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.registry.ECAPPotions;
import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent;

/** Registers the in-game brewing progression for Zombie Plague debug potions. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class ZombieVirusBrewingEvents {

    private ZombieVirusBrewingEvents() {
    }

    @SubscribeEvent
    public static void registerBrewingRecipes(RegisterBrewingRecipesEvent event) {
        PotionBrewing.Builder builder = event.getBuilder();

        // Thick potion + rotten flesh starts the infection in the rotting phase.
        builder.addMix(Potions.THICK, Items.ROTTEN_FLESH, ECAPPotions.ZOMBIE_VIRUS_PHASE_ONE);

        // Compacted rotten flesh advances the rotting phase to the terminal turning phase.
        builder.addMix(ECAPPotions.ZOMBIE_VIRUS_PHASE_ONE,
                ECAPItems.COMPACTED_ROTTEN_FLESH.get(),
                ECAPPotions.ZOMBIE_VIRUS_PHASE_TWO);
    }
}
