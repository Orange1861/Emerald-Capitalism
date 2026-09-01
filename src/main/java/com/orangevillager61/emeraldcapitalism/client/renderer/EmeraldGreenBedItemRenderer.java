package com.orangevillager61.emeraldcapitalism.client.renderer;

//? if >=1.21.4 {
/** The 1.21.4 item-model pipeline renders the block item directly. */
public final class EmeraldGreenBedItemRenderer {
}
//?} else {
/*

import com.mojang.blaze3d.vertex.PoseStack;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldGreenBedBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;

public class EmeraldGreenBedItemRenderer extends BlockEntityWithoutLevelRenderer {

    private final EmeraldGreenBedBlockEntity bedEntity;

    public EmeraldGreenBedItemRenderer(BlockEntityRenderDispatcher dispatcher, EntityModelSet modelSet) {
        super(dispatcher, modelSet);
        BlockState state = ECAPBlocks.EMERALD_GREEN_BED.get()
                .defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH);
        this.bedEntity = new EmeraldGreenBedBlockEntity(BlockPos.ZERO, state);
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                             MultiBufferSource buffer, int packedLight, int packedOverlay) {
        Minecraft.getInstance()
                .getBlockEntityRenderDispatcher()
                .renderItem(this.bedEntity, poseStack, buffer, packedLight, packedOverlay);
    }
}
*/
//?}
