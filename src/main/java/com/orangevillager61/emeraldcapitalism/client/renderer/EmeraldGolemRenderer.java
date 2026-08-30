package com.orangevillager61.emeraldcapitalism.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.client.model.EmeraldGolemModel;
import com.orangevillager61.emeraldcapitalism.client.renderer.layer.EmeraldGolemCrackLayer;
import com.orangevillager61.emeraldcapitalism.client.renderer.layer.EmeraldGolemFlowerLayer;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import org.jetbrains.annotations.NotNull;

public class EmeraldGolemRenderer extends MobRenderer<EmeraldGolem, EmeraldGolemModel<EmeraldGolem>> {

    // The authored mesh is 16 px wide by 33 px tall. Keep its cuboids intact,
    // but render it at exactly 1 block wide by 2 blocks tall.
    private static final float MODEL_HEIGHT_SCALE = 32.0F / 33.0F;

    public static final ModelLayerLocation EMERALD_GOLEM_LAYER =
            new ModelLayerLocation(ModIds.id("emerald_golem"), "main");

    private static final ResourceLocation EMERALD_GOLEM_TEXTURE =
            ModIds.id("textures/entity/emerald_golem/emerald_golem.png");

    public EmeraldGolemRenderer(EntityRendererProvider.Context context) {
        super(context, new EmeraldGolemModel<>(context.bakeLayer(EMERALD_GOLEM_LAYER)), 0.5F);
        this.addLayer(new EmeraldGolemCrackLayer(this));
        this.addLayer(new EmeraldGolemFlowerLayer(this, context.getBlockRenderDispatcher()));
    }

    @Override
    protected void scale(@NotNull EmeraldGolem entity, @NotNull PoseStack poseStack, float partialTick) {
        poseStack.scale(1.0F, MODEL_HEIGHT_SCALE, 1.0F);
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull EmeraldGolem entity) {
        return EMERALD_GOLEM_TEXTURE;
    }

    @Override
    protected void setupRotations(@NotNull EmeraldGolem entity, @NotNull PoseStack poseStack, float bob, float yBodyRot, float partialTick, float scale) {
        super.setupRotations(entity, poseStack, bob, yBodyRot, partialTick, scale);
        if (!(entity.walkAnimation.speed() < 0.01F)) {
            float walkPos = entity.walkAnimation.position(partialTick) + 6.0F;
            float walkSpeed = Math.min(entity.walkAnimation.speed() / 0.375F, 1.0F); // adjusted for faster base speed
            poseStack.mulPose(Axis.ZP.rotationDegrees(6.5F * walkSpeed * (float) Math.cos(walkPos)));
        }
    }
}
