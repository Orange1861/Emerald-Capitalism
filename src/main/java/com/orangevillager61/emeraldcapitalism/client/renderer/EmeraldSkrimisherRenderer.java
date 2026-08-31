package com.orangevillager61.emeraldcapitalism.client.renderer;

import com.orangevillager61.emeraldcapitalism.client.model.EmeraldSkrimisherModel;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldSkrimisher;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** Client renderer using the Emerald Skrimisher model and texture. */
public final class EmeraldSkrimisherRenderer
        extends MobRenderer<EmeraldSkrimisher, EmeraldSkrimisherModel> {

    public static final ModelLayerLocation EMERALD_SKRIMISHER_LAYER =
            new ModelLayerLocation(ModIds.id("emerald_skrimisher"), "main");
    private static final ResourceLocation EMERALD_SKRIMISHER_TEXTURE =
            ModIds.id("textures/entity/emerald_skrimisher/emerald_skrimisher.png");

    public EmeraldSkrimisherRenderer(EntityRendererProvider.Context context) {
        super(context,
                new EmeraldSkrimisherModel(context.bakeLayer(EMERALD_SKRIMISHER_LAYER)),
                0.35F);
    }

    @Override
    protected void scale(@NotNull EmeraldSkrimisher entity, @NotNull PoseStack poseStack, float partialTick) {
        poseStack.scale(0.75F, 0.75F, 0.75F);
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull EmeraldSkrimisher entity) {
        return EMERALD_SKRIMISHER_TEXTURE;
    }
}
