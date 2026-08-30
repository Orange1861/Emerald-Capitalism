package com.orangevillager61.emeraldcapitalism.block;

import com.mojang.serialization.MapCodec;
import com.orangevillager61.emeraldcapitalism.menu.SawmillMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** A stonecutter-shaped workstation that exposes only sawmill recipes. */
public class SawmillBlock extends StonecutterBlock {
    public static final MapCodec<StonecutterBlock> CODEC = simpleCodec(SawmillBlock::new);
    private static final Component CONTAINER_TITLE = Component.translatable(
            "container.emeraldcapitalism.sawmill");

    public SawmillBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<StonecutterBlock> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                                BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        player.openMenu(state.getMenuProvider(level, pos));
        player.awardStat(Stats.INTERACT_WITH_STONECUTTER);
        return InteractionResult.CONSUME;
    }

    @Override
    @Nullable
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider(
                (containerId, inventory, player) -> new SawmillMenu(
                        containerId, inventory, ContainerLevelAccess.create(level, pos)),
                CONTAINER_TITLE
        );
    }
}
