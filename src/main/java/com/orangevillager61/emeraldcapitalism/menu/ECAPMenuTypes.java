package com.orangevillager61.emeraldcapitalism.menu;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;

import java.util.function.Supplier;

public final class ECAPMenuTypes {

    private static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, EmeraldCapitalism.MODID);

    public static void register(IEventBus modEventBus) {
        MENU_TYPES.register(modEventBus);
    }

    public static final Supplier<MenuType<VillagerStatsMenu>> VILLAGER_STATS_MENU =
            MENU_TYPES.register("villager_stats", () ->
                    IMenuTypeExtension.create(VillagerStatsMenu::new)
            );

    public static final Supplier<MenuType<EmeraldSkrimisherMenu>> EMERALD_SKRIMISHER_MENU =
            MENU_TYPES.register("emerald_skrimisher", () ->
                    IMenuTypeExtension.create(EmeraldSkrimisherMenu::new)
            );

    public static final Supplier<MenuType<VillageManagerMenu>> VILLAGE_MANAGER_MENU =
            MENU_TYPES.register("village_manager", () ->
                    IMenuTypeExtension.create(VillageManagerMenu::new)
            );

    public static final Supplier<MenuType<EmeraldOreProcessorMenu>> EMERALD_ORE_PROCESSOR_MENU =
            MENU_TYPES.register("emerald_ore_processor", () ->
                    IMenuTypeExtension.create(EmeraldOreProcessorMenu::new)
            );

    public static final Supplier<MenuType<BankMenu>> BANK_MENU =
            MENU_TYPES.register("bank", () ->
                    IMenuTypeExtension.create(BankMenu::new)
            );

    public static final Supplier<MenuType<SawmillMenu>> SAWMILL_MENU =
            MENU_TYPES.register("sawmill", () ->
                    IMenuTypeExtension.create(SawmillMenu::new)
            );
}
