package com.orangevillager61.emeraldcapitalism.registry;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.BankBlock;
import com.orangevillager61.emeraldcapitalism.block.EmeraldChestBlock;
import com.orangevillager61.emeraldcapitalism.block.EmeraldDoorBlock;
import com.orangevillager61.emeraldcapitalism.block.EmeraldDoorTopBlock;
import com.orangevillager61.emeraldcapitalism.block.EmeraldGreenBedBlock;
import com.orangevillager61.emeraldcapitalism.block.EmeraldOreProcessorBlock;
import com.orangevillager61.emeraldcapitalism.block.RegularEmeraldDoorBlock;
import com.orangevillager61.emeraldcapitalism.block.SawmillBlock;
import com.orangevillager61.emeraldcapitalism.block.VillageManagerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;

public final class ECAPBlocks {

    private static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(EmeraldCapitalism.MODID);

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }

    public static final DeferredBlock<EmeraldChestBlock> EMERALD_CHEST = BLOCKS.registerBlock(
            "emerald_chest",
            properties -> new EmeraldChestBlock(properties,
                    ECAPBlockEntityTypes.EMERALD_CHEST::get),
            BlockBehaviour.Properties.of()
                    .strength(40.0F, 1200.0F)   // Obsidian is 50/1200, this is nearly as slow
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE)
    );

    public static final DeferredBlock<VillageManagerBlock> VILLAGE_MANAGER = BLOCKS.registerBlock(
            "village_manager",
            VillageManagerBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(3.5F, 3.5F)   // Stone-tier hardness (similar to a bell)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE)
    );

    public static final DeferredBlock<EmeraldOreProcessorBlock> EMERALD_ORE_PROCESSOR = BLOCKS.registerBlock(
            "emerald_ore_processor",
            EmeraldOreProcessorBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(3.5F, 3.5F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE)
                    .lightLevel(state -> state.getValue(EmeraldOreProcessorBlock.LIT) ? 13 : 0)
    );

    public static final DeferredBlock<BankBlock> BANK = BLOCKS.registerBlock(
            "bank",
            BankBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(3.5F, 3.5F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE)
    );

    public static final DeferredBlock<SawmillBlock> SAWMILL = BLOCKS.registerBlock(
            "sawmill",
            SawmillBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.STONECUTTER)
    );

    public static final DeferredBlock<EmeraldDoorTopBlock> EMERALD_DOOR_TOP = BLOCKS.registerBlock(
            "emerald_door_top",
            EmeraldDoorTopBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(40.0F, 1200.0F)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()
                    .sound(SoundType.STONE)
    );

    public static final DeferredBlock<EmeraldDoorBlock> EMERALD_DOOR = BLOCKS.registerBlock(
            "emerald_door",
            EmeraldDoorBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(40.0F, 1200.0F)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()
                    .sound(SoundType.STONE)
    );

    public static final DeferredBlock<RegularEmeraldDoorBlock> REGULAR_EMERALD_DOOR = BLOCKS.registerBlock(
            "regular_emerald_door",
            RegularEmeraldDoorBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(40.0F, 1200.0F)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()
                    .sound(SoundType.STONE)
    );

    public static final DeferredBlock<StainedGlassPaneBlock> EMERALD_GREEN_STAINED_GLASS_PANE = BLOCKS.registerBlock(
            "emerald_green_stained_glass_pane",
            properties -> new StainedGlassPaneBlock(DyeColor.GREEN, properties),
            BlockBehaviour.Properties.of()
                    .strength(0.3F)
                    .noOcclusion()
                    .sound(SoundType.GLASS)
    );

    public static final DeferredBlock<StainedGlassBlock> EMERALD_GREEN_STAINED_GLASS = BLOCKS.registerBlock(
            "emerald_green_stained_glass",
            properties -> new StainedGlassBlock(DyeColor.GREEN, properties),
            BlockBehaviour.Properties.of()
                    .strength(0.3F)
                    .noOcclusion()
                    .sound(SoundType.GLASS)
    );

    public static final DeferredBlock<EmeraldGreenBedBlock> EMERALD_GREEN_BED = BLOCKS.registerBlock(
            "emerald_green_bed",
            EmeraldGreenBedBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.GREEN_BED)
    );

    public static final DeferredBlock<Block> GOLEM_CONSTRUCTION_LOCATION = BLOCKS.registerSimpleBlock(
            "golem_construction_location",
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
    );
}
