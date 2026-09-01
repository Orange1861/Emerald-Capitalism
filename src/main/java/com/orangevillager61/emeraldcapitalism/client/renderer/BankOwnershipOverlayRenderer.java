package com.orangevillager61.emeraldcapitalism.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Renders remembered ownership areas for banks controlled by the local player. */
public final class BankOwnershipOverlayRenderer {

    private static final double HALF_SIZE = 8.0;
    private static final Map<BlockPos, AABB> OWNED_BANK_BOUNDS = new LinkedHashMap<>();

    // Match the dark-green bank marker used by the village POI overlay.
    private static final float BANK_RED = 0.0f;
    private static final float BANK_GREEN = 0.45f;
    private static final float BANK_BLUE = 0.1f;

    private BankOwnershipOverlayRenderer() {
    }

    /** Remembers the server-confirmed ownership state for a bank opened by this client. */
    public static void updateBank(BlockPos bankPos, boolean ownedByPlayer) {
        Objects.requireNonNull(bankPos, "bankPos");
        if (ownedByPlayer) {
            BlockPos immutablePos = bankPos.immutable();
            OWNED_BANK_BOUNDS.computeIfAbsent(immutablePos, BankOwnershipOverlayRenderer::boundsFor);
        } else {
            OWNED_BANK_BOUNDS.remove(bankPos);
        }
    }

    public static boolean hasOwnedBanks() {
        return !OWNED_BANK_BOUNDS.isEmpty();
    }

    /** Clears remembered ownership when the client level or network session ends. */
    public static void clear() {
        OWNED_BANK_BOUNDS.clear();
    }

    /** Renders exact 16×16×16 wireframes for the client's remembered owned banks. */
    public static void render(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                              Vec3 cameraPos) {
        if (OWNED_BANK_BOUNDS.isEmpty()) {
            return;
        }

        VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.LINES);

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        for (AABB bounds : OWNED_BANK_BOUNDS.values()) {
            LevelRenderer.renderLineBox(poseStack, lineConsumer, bounds,
                    BANK_RED, BANK_GREEN, BANK_BLUE, 1.0f);
        }
        poseStack.popPose();
    }

    /** Creates the exact 16×16×16 ownership bounds for a bank position. */
    public static AABB boundsFor(BlockPos bankPos) {
        Objects.requireNonNull(bankPos, "bankPos");
        return new AABB(
                bankPos.getX() - HALF_SIZE, bankPos.getY() - HALF_SIZE, bankPos.getZ() - HALF_SIZE,
                bankPos.getX() + HALF_SIZE, bankPos.getY() + HALF_SIZE, bankPos.getZ() + HALF_SIZE);
    }
}
