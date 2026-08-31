package com.orangevillager61.emeraldcapitalism.worldgen;

import com.mojang.serialization.MapCodec;
import com.orangevillager61.emeraldcapitalism.registry.ECAPStructureProcessorTypes;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import javax.annotation.Nullable;

import java.util.Comparator;

public final class BankVaultRuinsProcessor extends StructureProcessor {

    public static final MapCodec<BankVaultRuinsProcessor> CODEC = MapCodec.unit(BankVaultRuinsProcessor::new);

    private static final float BLOCK_DECAY_CHANCE = 0.20F;
    private static final ResourceLocation CHEST_LOOT_TABLE = ModIds.id("chests/bank_vault_ruins");
    private static final ResourceLocation MAP_CHEST_LOOT_TABLE =
            ModIds.id("chests/bank_vault_ruins_map");

    @Override
    public StructureTemplate.StructureBlockInfo process(
            LevelReader level,
            BlockPos offset,
            BlockPos pos,
            StructureTemplate.StructureBlockInfo blockInfo,
            StructureTemplate.StructureBlockInfo relativeBlockInfo,
            StructurePlaceSettings settings,
            @Nullable StructureTemplate template) {
        BlockState inputState = relativeBlockInfo.state();
        BlockPos targetPos = relativeBlockInfo.pos();
        BlockState terrainState = terrainState(level, targetPos);
        boolean designatedMapChest = inputState.is(ECAPBlocks.EMERALD_CHEST.get())
                && isDesignatedMapChest(offset, targetPos, settings, template);

        if (inputState.isAir()) {
            return new StructureTemplate.StructureBlockInfo(targetPos, terrainState, null);
        }

        RandomSource random = RandomSource.create(PositionalSeed.of(targetPos));
        if (!designatedMapChest && random.nextFloat() < BLOCK_DECAY_CHANCE) {
            return new StructureTemplate.StructureBlockInfo(targetPos, terrainState, null);
        }

        if (inputState.is(ECAPBlocks.EMERALD_CHEST.get())) {
            return withChestLoot(relativeBlockInfo, designatedMapChest);
        }

        return relativeBlockInfo;
    }

    private StructureTemplate.StructureBlockInfo withChestLoot(
            StructureTemplate.StructureBlockInfo blockInfo, boolean designatedMapChest) {
        CompoundTag nbt = blockInfo.nbt() == null ? new CompoundTag() : blockInfo.nbt().copy();
        nbt.remove("Items");
        nbt.remove("LootTable");
        nbt.remove("LootTableSeed");
        nbt.putString("LootTable", (designatedMapChest
                ? MAP_CHEST_LOOT_TABLE : CHEST_LOOT_TABLE).toString());
        return new StructureTemplate.StructureBlockInfo(blockInfo.pos(), blockInfo.state(), nbt);
    }

    private boolean isDesignatedMapChest(BlockPos offset, BlockPos targetPos,
                                         StructurePlaceSettings settings,
                                         @Nullable StructureTemplate template) {
        if (template == null) {
            return false;
        }
        return template.filterBlocks(offset, settings, ECAPBlocks.EMERALD_CHEST.get()).stream()
                .map(StructureTemplate.StructureBlockInfo::pos)
                .min(Comparator.<BlockPos>comparingInt(pos -> pos.getX())
                        .thenComparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getZ))
                .map(targetPos::equals)
                .orElse(false);
    }

    private BlockState terrainState(LevelReader level, BlockPos targetPos) {
        BlockState above = level.getBlockState(targetPos.above());
        if (above.is(Blocks.RED_SAND)) {
            return Blocks.RED_SAND.defaultBlockState();
        }
        if (above.is(Blocks.SAND)) {
            return Blocks.SAND.defaultBlockState();
        }
        if (above.is(Blocks.GRASS_BLOCK) || above.is(BlockTags.DIRT)) {
            return Blocks.DIRT.defaultBlockState();
        }
        return Blocks.GRAVEL.defaultBlockState();
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return ECAPStructureProcessorTypes.BANK_VAULT_RUINS.get();
    }
}
