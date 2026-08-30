package com.orangevillager61.emeraldcapitalism.registry;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookRegistry;
import com.orangevillager61.emeraldcapitalism.world.village.books.LibraryBookStackFactory;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;

import java.util.function.Supplier;

public final class ECAPCreativeModTabs {

    private static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, EmeraldCapitalism.MODID);

    public static void register(IEventBus modEventBus) {
        CREATIVE_MODE_TABS.register(modEventBus);
    }

    public static final Supplier<CreativeModeTab> ECAP_TAB = CREATIVE_MODE_TABS.register(
            "ecap_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + EmeraldCapitalism.MODID))
                    .icon(() -> new ItemStack(ECAPItems.EMERALD_CHEST.get()))
                    .displayItems((params, output) -> {
                        output.accept(ECAPItems.EMERALD_CHEST.get());
                        output.accept(ECAPItems.EMERALD_GOLEM_SPAWN_EGG.get());
                        output.accept(ECAPItems.EMERALD_SKRIMISHER_SPAWN_EGG.get());
                        output.accept(ECAPItems.VILLAGE_MANAGER.get());
                        output.accept(ECAPItems.EMERALD_ORE_PROCESSOR.get());
                        output.accept(ECAPItems.EMERALD_GREEN_DYE.get());
                        output.accept(ECAPItems.EMERALD_GREEN_STAINED_GLASS.get());
                        output.accept(ECAPItems.EMERALD_GREEN_STAINED_GLASS_PANE.get());
                        output.accept(ECAPItems.EMERALD_GREEN_WOOL.get());
                        output.accept(ECAPItems.EMERALD_GREEN_BED.get());
                        output.accept(ECAPItems.BANK.get());
                        output.accept(ECAPItems.SAWMILL.get());
                        output.accept(ECAPItems.GOLEM_CONSTRUCTION_LOCATION.get());
                        output.accept(ECAPItems.EMERALD_DOOR.get());
                        output.accept(ECAPItems.REGULAR_EMERALD_DOOR.get());
                        output.accept(ECAPItems.EMERALD_LEAD.get());
                        output.accept(ECAPItems.ABANDONED_VAULT_MAP.get());
                        output.accept(ECAPItems.SECOND_ABANDONED_VAULT_MAP.get());
                        output.accept(ECAPItems.VILLAGE_MAP.get());
                        output.accept(ECAPItems.COMPACTED_ROTTEN_FLESH.get());
                        output.accept(ECAPItems.ROTTEN_FLESH_COVER.get());
                        if (Config.enableBooksInCreativeTab) {
                            LibraryBookRegistry.entries().forEach(book ->
                                    output.accept(LibraryBookStackFactory.createItemStack(book)));
                        }
                        output.accept(PotionContents.createItemStack(
                                Items.POTION, ECAPPotions.ZOMBIE_VIRUS_PHASE_ONE));
                        output.accept(PotionContents.createItemStack(
                                Items.POTION, ECAPPotions.ZOMBIE_VIRUS_PHASE_TWO));
                        output.accept(PotionContents.createItemStack(
                                Items.SPLASH_POTION, ECAPPotions.ZOMBIE_VIRUS_PHASE_ONE));
                        output.accept(PotionContents.createItemStack(
                                Items.SPLASH_POTION, ECAPPotions.ZOMBIE_VIRUS_PHASE_TWO));
                    })
                    .build()
    );
}
