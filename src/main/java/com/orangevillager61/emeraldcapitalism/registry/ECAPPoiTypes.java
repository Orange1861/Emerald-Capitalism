package com.orangevillager61.emeraldcapitalism.registry;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
//? if >=1.21.4 {
//?} else {
/*import net.neoforged.neoforge.common.world.poi.ExtendPoiTypesEvent;
 *///?}
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;

import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Points of interest used by this mod's villager professions. */
public final class ECAPPoiTypes {

    private static final DeferredRegister<PoiType> POI_TYPES =
            DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, EmeraldCapitalism.MODID);

    public static void register(IEventBus modEventBus) {
        POI_TYPES.register(modEventBus);
    }

    public static final DeferredHolder<PoiType, PoiType> MAYOR = POI_TYPES.register(
            "mayor",
            () -> createJobSite(ECAPBlocks.VILLAGE_MANAGER.get())
    );

    public static final DeferredHolder<PoiType, PoiType> BANKER = POI_TYPES.register(
            "banker",
            () -> createJobSite(ECAPBlocks.BANK.get(), state ->
                    state.getValue(BankBlock.BANKER_AVAILABLE))
    );

    public static final DeferredHolder<PoiType, PoiType> EMERALDSMITH = POI_TYPES.register(
            "emeraldsmith",
            () -> createJobSite(ECAPBlocks.EMERALD_ORE_PROCESSOR.get())
    );

    public static final DeferredHolder<PoiType, PoiType> SAWMILL = POI_TYPES.register(
            "sawmill",
            () -> createJobSite(ECAPBlocks.SAWMILL.get())
    );

    private ECAPPoiTypes() {}

//? if >=1.21.4 {
//?} else {
/*
    // Adds the custom bed to the vanilla home POI used by villager housing and breeding.
    public static void extendVanillaPoiTypes(ExtendPoiTypesEvent event) {
        Set<BlockState> headStates =
                ECAPBlocks.EMERALD_GREEN_BED.get().getStateDefinition().getPossibleStates().stream()
                        .filter(state -> state.getValue(BedBlock.PART) == BedPart.HEAD)
                        .collect(Collectors.toUnmodifiableSet());
        event.addStatesToPoi(PoiTypes.HOME, headStates);
    }
 *///?}

    private static PoiType createJobSite(Block block) {
        return createJobSite(block, ignored -> true);
    }

    private static PoiType createJobSite(Block block, Predicate<BlockState> stateFilter) {
        return new PoiType(block.getStateDefinition().getPossibleStates().stream()
                .filter(stateFilter)
                .collect(Collectors.toUnmodifiableSet()), 1, 1);
    }
}
