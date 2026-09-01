package com.orangevillager61.emeraldcapitalism.client.renderer.layer;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.PoseStack;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.client.model.EmeraldGolemModel;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.world.entity.Crackiness;
//? if >=1.21.4 {
import net.minecraft.client.renderer.entity.state.IronGolemRenderState;
//?}
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/** Renders health-based crack overlays for non-invisible emerald golems. */
//? if >=1.21.4 {
public class EmeraldGolemCrackLayer extends RenderLayer<IronGolemRenderState, EmeraldGolemModel> {
//?} else {
/*public class EmeraldGolemCrackLayer extends RenderLayer<EmeraldGolem, EmeraldGolemModel<EmeraldGolem>> {
 *///?}

    private static final Map<Crackiness.Level, ResourceLocation> CRACK_TEXTURES =
            ImmutableMap.of(
                    Crackiness.Level.LOW,
                    ModIds.id("textures/entity/emerald_golem/emerald_golem_crackiness_low.png"),
                    Crackiness.Level.MEDIUM,
                    ModIds.id("textures/entity/emerald_golem/emerald_golem_crackiness_medium.png"),
                    Crackiness.Level.HIGH,
                    ModIds.id("textures/entity/emerald_golem/emerald_golem_crackiness_high.png")
            );

//? if >=1.21.4 {
    public EmeraldGolemCrackLayer(RenderLayerParent<IronGolemRenderState, EmeraldGolemModel> parent) {
//?} else {
/*    public EmeraldGolemCrackLayer(RenderLayerParent<EmeraldGolem, EmeraldGolemModel<EmeraldGolem>> parent) {
 *///?}
        super(parent);
    }

//? if >=1.21.4 {
    @Override
    public void render(@NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource, int packedLight,
                       IronGolemRenderState state, float yRot, float xRot) {
        if (!state.isInvisible) {
            Crackiness.Level crackiness = state.crackiness;
            if (crackiness != Crackiness.Level.NONE) {
                ResourceLocation texture = CRACK_TEXTURES.get(crackiness);
                renderColoredCutoutModel(this.getParentModel(), texture, poseStack, bufferSource, packedLight, state,
                        -1);
            }
        }
    }
//?} else {
/*    @Override
    public void render(@NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource, int packedLight,
                       EmeraldGolem entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (!entity.isInvisible()) {
            Crackiness.Level crackiness = entity.getCrackiness();
            if (crackiness != Crackiness.Level.NONE) {
                ResourceLocation texture = CRACK_TEXTURES.get(crackiness);
                renderColoredCutoutModel(this.getParentModel(), texture, poseStack, bufferSource, packedLight, entity,
                        -1);
            }
        }
    }
 *///?}
}
