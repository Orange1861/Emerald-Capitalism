package com.orangevillager61.emeraldcapitalism.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.block.entity.EmeraldChestBlockEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jetbrains.annotations.NotNull;

public class EmeraldChestRenderer implements BlockEntityRenderer<EmeraldChestBlockEntity> {

    private static final Material SINGLE_MATERIAL = chestMaterial("entity/chest/emerald_chest");
    private static final Material LEFT_MATERIAL   = chestMaterial("entity/chest/emerald_chest_left");
    private static final Material RIGHT_MATERIAL  = chestMaterial("entity/chest/emerald_chest_right");

    private final ModelPart singleLid, singleBottom, singleLock;
    private final ModelPart leftLid, leftBottom, leftLock;
    private final ModelPart rightLid, rightBottom, rightLock;

    private static Material chestMaterial(String path) {
        return new Material(
                Sheets.CHEST_SHEET,
                ModIds.id(path)
        );
    }

    public EmeraldChestRenderer(BlockEntityRendererProvider.Context context) {
        ModelPart single = context.bakeLayer(ModelLayers.CHEST);
        this.singleBottom = single.getChild("bottom");
        this.singleLid = single.getChild("lid");
        this.singleLock = single.getChild("lock");

        ModelPart left = context.bakeLayer(ModelLayers.DOUBLE_CHEST_LEFT);
        this.leftBottom = left.getChild("bottom");
        this.leftLid = left.getChild("lid");
        this.leftLock = left.getChild("lock");

        ModelPart right = context.bakeLayer(ModelLayers.DOUBLE_CHEST_RIGHT);
        this.rightBottom = right.getChild("bottom");
        this.rightLid = right.getChild("lid");
        this.rightLock = right.getChild("lock");
    }

    @Override
    public void render(EmeraldChestBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       @NotNull MultiBufferSource buffer, int packedLight, int packedOverlay) {

        Level level = blockEntity.getLevel();
        boolean hasLevel = level != null;

        BlockState state = hasLevel
                ? blockEntity.getBlockState()
                : net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState()
                        .setValue(ChestBlock.FACING, Direction.SOUTH);

        ChestType chestType = state.hasProperty(ChestBlock.TYPE)
                ? state.getValue(ChestBlock.TYPE)
                : ChestType.SINGLE;

        poseStack.pushPose();

        float rotation = state.getValue(ChestBlock.FACING).toYRot();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-rotation));
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        // Lid openness (animated via ChestBlockEntity's lid controller)
        float openness = blockEntity.getOpenNess(partialTick);
        openness = 1.0F - openness;
        openness = 1.0F - openness * openness * openness;

        ModelPart lid, bottom, lock;
        Material material;

        switch (chestType) {
            case LEFT:
                lid = leftLid;
                bottom = leftBottom;
                lock = leftLock;
                material = LEFT_MATERIAL;
                break;
            case RIGHT:
                lid = rightLid;
                bottom = rightBottom;
                lock = rightLock;
                material = RIGHT_MATERIAL;
                break;
            default:
                lid = singleLid;
                bottom = singleBottom;
                lock = singleLock;
                material = SINGLE_MATERIAL;
                break;
        }

        VertexConsumer vertexConsumer = material.buffer(buffer, RenderType::entityCutout);

        lid.xRot = -(openness * ((float) Math.PI / 2F));
        lock.xRot = lid.xRot;

        bottom.render(poseStack, vertexConsumer, packedLight, packedOverlay);
        lid.render(poseStack, vertexConsumer, packedLight, packedOverlay);
        lock.render(poseStack, vertexConsumer, packedLight, packedOverlay);

        poseStack.popPose();
    }
}
