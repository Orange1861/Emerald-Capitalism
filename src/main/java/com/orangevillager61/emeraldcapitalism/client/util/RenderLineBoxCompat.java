package com.orangevillager61.emeraldcapitalism.client.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.AABB;

/** Bridges the debug line-box helper moved from LevelRenderer in 1.21.4. */
public final class RenderLineBoxCompat {
    private RenderLineBoxCompat() {
    }

    public static void render(PoseStack poseStack, VertexConsumer buffer, AABB box,
                              float red, float green, float blue, float alpha) {
//? if >=1.21.4 {
        net.minecraft.client.renderer.ShapeRenderer.renderLineBox(
                poseStack, buffer, box, red, green, blue, alpha);
//?} else {
/*        net.minecraft.client.renderer.LevelRenderer.renderLineBox(
                poseStack, buffer, box, red, green, blue, alpha);
 *///?}
    }
}
