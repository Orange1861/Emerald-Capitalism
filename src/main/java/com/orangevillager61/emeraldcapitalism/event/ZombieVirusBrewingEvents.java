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

/** Registers the in-game brewing progression for Zombkolaps debug potions. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class ZombieVirusBrewingEvents {

    private ZombieVirusBrewingEvents() {
    }

    @SubscribeEvent
    public static void registerBrewingRecipes(RegisterBrewingRecipesEvent event) {
        PotionBrewing.Builder builder = event.getBuilder();

        // Thick potion + rotten flesh starts the infection in the turning phase.
        builder.addMix(Potions.THICK, Items.ROTTEN_FLESH, ECAPPotions.ZOMBIE_VIRUS_PHASE_ONE);

        // Compacted rotten flesh advances the turning phase to the terminal rotting phase.
        builder.addMix(ECAPPotions.ZOMBIE_VIRUS_PHASE_ONE,
                ECAPItems.COMPACTED_ROTTEN_FLESH.get(),
                ECAPPotions.ZOMBIE_VIRUS_PHASE_TWO);
    }
}
