package com.orangevillager61.emeraldcapitalism.client.renderer;

//? if >=1.21.4 {
/** The 1.21.4 item-model pipeline renders the block item directly. */
public final class EmeraldChestItemRenderer {
}
//?} else {
/*

import com.mojang.blaze3d.vertex.PoseStack;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldChestBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jetbrains.annotations.NotNull;

public class EmeraldChestItemRenderer extends BlockEntityWithoutLevelRenderer {
    private final EmeraldChestBlockEntity chestEntity;

    public EmeraldChestItemRenderer(BlockEntityRenderDispatcher dispatcher, EntityModelSet modelSet) {
        super(dispatcher, modelSet);
        BlockState state = ECAPBlocks.EMERALD_CHEST.get()
                .defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH)
                .setValue(ChestBlock.TYPE, ChestType.SINGLE);
        this.chestEntity = new EmeraldChestBlockEntity(BlockPos.ZERO, state);
    }

    @Override
    public void renderByItem(@NotNull ItemStack stack, @NotNull ItemDisplayContext displayContext, @NotNull PoseStack poseStack,
                             @NotNull MultiBufferSource buffer, int packedLight, int packedOverlay) {
        Minecraft.getInstance()
                .getBlockEntityRenderDispatcher()
                .renderItem(this.chestEntity, poseStack, buffer, packedLight, packedOverlay);
    }
}
*/
//?}
