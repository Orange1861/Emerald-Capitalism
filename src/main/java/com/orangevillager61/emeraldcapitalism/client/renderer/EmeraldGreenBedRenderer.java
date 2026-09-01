package com.orangevillager61.emeraldcapitalism.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldGreenBedBlockEntity;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlockEntityTypes;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BrightnessCombiner;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.util.BlockAtlasCompat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

public class EmeraldGreenBedRenderer implements BlockEntityRenderer<EmeraldGreenBedBlockEntity> {

    private static final Material MATERIAL = new Material(
            BlockAtlasCompat.location(),
            ModIds.id("block/emerald_green_bed")
    );

    private final ModelPart headRoot;
    private final ModelPart footRoot;

    public EmeraldGreenBedRenderer(BlockEntityRendererProvider.Context context) {
        this.headRoot = context.bakeLayer(ModelLayers.BED_HEAD);
        this.footRoot = context.bakeLayer(ModelLayers.BED_FOOT);
    }

    @Override
    public void render(EmeraldGreenBedBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        Level level = blockEntity.getLevel();
        if (level != null) {
            BlockState state = blockEntity.getBlockState();
            DoubleBlockCombiner.NeighborCombineResult<? extends EmeraldGreenBedBlockEntity> neighborResult =
                    DoubleBlockCombiner.combineWithNeigbour(
                            ECAPBlockEntityTypes.EMERALD_GREEN_BED.get(),
                            BedBlock::getBlockType,
                            BedBlock::getConnectedDirection,
                            ChestBlock.FACING,
                            state,
                            level,
                            blockEntity.getBlockPos(),
                            (first, second) -> false
                    );
            int light = neighborResult.apply(new BrightnessCombiner<>()).get(packedLight);
            renderPiece(
                    poseStack,
                    bufferSource,
                    state.getValue(BedBlock.PART) == BedPart.HEAD ? headRoot : footRoot,
                    state.getValue(BedBlock.FACING),
                    light,
                    packedOverlay,
                    false
            );
        } else {
            renderPiece(poseStack, bufferSource, headRoot, Direction.SOUTH, packedLight, packedOverlay, false);
            renderPiece(poseStack, bufferSource, footRoot, Direction.SOUTH, packedLight, packedOverlay, true);
        }
    }

    private void renderPiece(PoseStack poseStack, MultiBufferSource bufferSource, ModelPart modelPart,
                             Direction direction, int packedLight, int packedOverlay, boolean foot) {
        poseStack.pushPose();
        poseStack.translate(0.0F, 0.5625F, foot ? -1.0F : 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F + direction.toYRot()));
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        VertexConsumer vertexConsumer = MATERIAL.buffer(bufferSource, RenderType::entitySolid);
        modelPart.render(poseStack, vertexConsumer, packedLight, packedOverlay);
        poseStack.popPose();
    }
}
