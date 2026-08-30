package com.orangevillager61.emeraldcapitalism.client.renderer.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.orangevillager61.emeraldcapitalism.client.model.EmeraldGolemModel;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.NotNull;

/**
 * Renders a poppy in the Emerald Golem's hand when it is offering a flower.
 */
public class EmeraldGolemFlowerLayer extends RenderLayer<EmeraldGolem, EmeraldGolemModel<EmeraldGolem>> {

    private final BlockRenderDispatcher blockRenderer;

    public EmeraldGolemFlowerLayer(RenderLayerParent<EmeraldGolem, EmeraldGolemModel<EmeraldGolem>> parent,
                                  BlockRenderDispatcher blockRenderer) {
        super(parent);
        this.blockRenderer = blockRenderer;
    }

    @Override
    public void render(@NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource, int packedLight,
                       EmeraldGolem entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (entity.getOfferFlowerTick() != 0) {
            poseStack.pushPose();
            ModelPart flowerArm = this.getParentModel().getFlowerHoldingArm();
            flowerArm.translateAndRotate(poseStack);
            poseStack.translate(-1.1875F, 1.0625F, -0.9375F);
            poseStack.translate(0.5F, 0.5F, 0.5F);
            float scale = 0.5F;
            poseStack.scale(scale, scale, scale);
            poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
            poseStack.translate(-0.5F, -0.5F, -0.5F);
            this.blockRenderer.renderSingleBlock(
                    Blocks.POPPY.defaultBlockState(), poseStack, bufferSource, packedLight,
                    OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            poseStack.popPose();
        }
    }
}
