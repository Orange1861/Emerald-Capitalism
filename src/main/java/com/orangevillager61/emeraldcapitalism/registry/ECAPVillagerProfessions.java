package com.orangevillager61.emeraldcapitalism.registry;

import com.google.common.collect.ImmutableSet;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;

/** Villager professions assigned from the corresponding mod job-site POIs. */
public final class ECAPVillagerProfessions {

    private static final DeferredRegister<VillagerProfession> PROFESSIONS =
            DeferredRegister.create(Registries.VILLAGER_PROFESSION, EmeraldCapitalism.MODID);

    public static void register(IEventBus modEventBus) {
        PROFESSIONS.register(modEventBus);
    }

    public static final DeferredHolder<VillagerProfession, VillagerProfession> MAYOR = register("mayor", ECAPPoiTypes.MAYOR);
    public static final DeferredHolder<VillagerProfession, VillagerProfession> BANKER = register("banker", ECAPPoiTypes.BANKER);
    public static final DeferredHolder<VillagerProfession, VillagerProfession> EMERALDSMITH = register("emeraldsmith", ECAPPoiTypes.EMERALDSMITH);
    public static final DeferredHolder<VillagerProfession, VillagerProfession> LUMBERJACK = register("lumberjack", ECAPPoiTypes.SAWMILL);

    private ECAPVillagerProfessions() {}

    private static DeferredHolder<VillagerProfession, VillagerProfession> register(
            String name, DeferredHolder<PoiType, PoiType> jobSite) {
        return PROFESSIONS.register(name, () -> new VillagerProfession(
                name,
                poi -> poi.is(jobSite.getKey()),
                poi -> poi.is(jobSite.getKey()),
                ImmutableSet.of(),
                ImmutableSet.of(),
                null
        ));
    }
}
