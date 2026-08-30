package com.orangevillager61.emeraldcapitalism.registry;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.worldgen.BankVaultRuinsProcessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;

import java.util.function.Supplier;

public final class ECAPStructureProcessorTypes {

    private static final DeferredRegister<StructureProcessorType<?>> STRUCTURE_PROCESSORS =
            DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, EmeraldCapitalism.MODID);

    public static void register(IEventBus modEventBus) {
        STRUCTURE_PROCESSORS.register(modEventBus);
    }

    public static final Supplier<StructureProcessorType<BankVaultRuinsProcessor>> BANK_VAULT_RUINS =
            STRUCTURE_PROCESSORS.register(
                    "bank_vault_ruins",
                    () -> () -> BankVaultRuinsProcessor.CODEC
            );

    private ECAPStructureProcessorTypes() {
    }
}
