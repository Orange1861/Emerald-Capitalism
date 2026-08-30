package com.orangevillager61.emeraldcapitalism.registry;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldChestBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldGreenBedBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldOreProcessorBlockEntity;
import com.orangevillager61.emeraldcapitalism.block.entity.VillageManagerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;

import java.util.function.Supplier;

public final class ECAPBlockEntityTypes {

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, EmeraldCapitalism.MODID);

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }

    private static <T extends BlockEntity> BlockEntityType<T> registerType(
            BlockEntityType.BlockEntitySupplier<T> factory,
            Block... validBlocks) {
        return BlockEntityType.Builder.of(factory, validBlocks).build(null);
    }

    public static final Supplier<BlockEntityType<EmeraldChestBlockEntity>> EMERALD_CHEST =
            BLOCK_ENTITY_TYPES.register("emerald_chest",
                    () -> registerType(
                            EmeraldChestBlockEntity::new,
                            ECAPBlocks.EMERALD_CHEST.get()
                    )
            );

    public static final Supplier<BlockEntityType<VillageManagerBlockEntity>> VILLAGE_MANAGER =
            BLOCK_ENTITY_TYPES.register("village_manager",
                    () -> registerType(
                            VillageManagerBlockEntity::new,
                            ECAPBlocks.VILLAGE_MANAGER.get()
                    )
            );

    public static final Supplier<BlockEntityType<EmeraldOreProcessorBlockEntity>> EMERALD_ORE_PROCESSOR =
            BLOCK_ENTITY_TYPES.register("emerald_ore_processor",
                    () -> registerType(
                            EmeraldOreProcessorBlockEntity::new,
                            ECAPBlocks.EMERALD_ORE_PROCESSOR.get()
                    )
            );

    public static final Supplier<BlockEntityType<BankBlockEntity>> BANK =
            BLOCK_ENTITY_TYPES.register("bank",
                    () -> registerType(
                            BankBlockEntity::new,
                            ECAPBlocks.BANK.get()
                    )
            );

    public static final Supplier<BlockEntityType<EmeraldGreenBedBlockEntity>> EMERALD_GREEN_BED =
            BLOCK_ENTITY_TYPES.register("emerald_green_bed",
                    () -> registerType(
                            EmeraldGreenBedBlockEntity::new,
                            ECAPBlocks.EMERALD_GREEN_BED.get()
                    )
            );
}
