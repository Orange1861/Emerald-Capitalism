package com.orangevillager61.emeraldcapitalism.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Renders the ownership area for the player who controls the open bank. */
public final class BankOwnershipOverlayRenderer {

    private static final double HALF_SIZE = 8.0;

    // Match the dark-green bank marker used by the village POI overlay.
    private static final float BANK_RED = 0.0f;
    private static final float BANK_GREEN = 0.45f;
    private static final float BANK_BLUE = 0.1f;

    private BankOwnershipOverlayRenderer() {
    }

    /** Renders an exact 16×16×16 wireframe centered on the bank block's coordinates. */
    public static void render(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                              Vec3 cameraPos, AABB bounds) {
        VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.LINES);

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        LevelRenderer.renderLineBox(poseStack, lineConsumer, bounds,
                BANK_RED, BANK_GREEN, BANK_BLUE, 1.0f);
        poseStack.popPose();
    }

    public static AABB boundsFor(BlockPos bankPos) {
        return new AABB(
                bankPos.getX() - HALF_SIZE, bankPos.getY() - HALF_SIZE, bankPos.getZ() - HALF_SIZE,
                bankPos.getX() + HALF_SIZE, bankPos.getY() + HALF_SIZE, bankPos.getZ() + HALF_SIZE);
    }
}
