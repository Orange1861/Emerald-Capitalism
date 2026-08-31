package com.orangevillager61.emeraldcapitalism.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldSkrimisher;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/** Client model converted from the supplied Blockbench Emerald Skrimisher export. */
public final class EmeraldSkrimisherModel extends EntityModel<EmeraldSkrimisher> {

    private final ModelPart head;
    private final ModelPart body;
    private final ModelPart leftArm;
    private final ModelPart rightArm;
    private final ModelPart leftLeg;
    private final ModelPart rightLeg;

    public EmeraldSkrimisherModel(ModelPart root) {
        this.head = root.getChild("head");
        this.body = root.getChild("body");
        this.leftArm = root.getChild("left_arm");
        this.rightArm = root.getChild("right_arm");
        this.leftLeg = root.getChild("left_leg");
        this.rightLeg = root.getChild("right_leg");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        CubeDeformation deformation = new CubeDeformation(0.0F);

        root.addOrReplaceChild("head", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0F, -6.0F, -5.0F, 8.0F, 6.0F, 10.0F, deformation)
                        .texOffs(56, 0).addBox(-1.0F, -3.0F, -6.0F, 2.0F, 3.0F, 2.0F, deformation),
                PartPose.offset(0.0F, 13.0F, 0.0F));

        root.addOrReplaceChild("body", CubeListBuilder.create()
                        .texOffs(0, 15).addBox(-4.0F, -6.0F, -3.0F, 8.0F, 6.0F, 6.0F, deformation),
                PartPose.offset(0.0F, 19.0F, 0.0F));

        root.addOrReplaceChild("left_arm", CubeListBuilder.create()
                        .texOffs(50, 16).addBox(0.0F, -1.0F, -2.0F, 3.0F, 10.0F, 4.0F, deformation),
                PartPose.offset(4.0F, 13.0F, 0.0F));

        root.addOrReplaceChild("right_arm", CubeListBuilder.create()
                        .texOffs(36, 16).addBox(-3.0F, -1.0F, -2.0F, 3.0F, 10.0F, 4.0F, deformation),
                PartPose.offset(-4.0F, 13.0F, 0.0F));

        root.addOrReplaceChild("left_leg", CubeListBuilder.create()
                        .texOffs(16, 27).addBox(-0.1F, 0.0F, -2.0F, 4.0F, 5.0F, 4.0F, deformation),
                PartPose.offset(0.0F, 19.0F, 0.0F));

        root.addOrReplaceChild("right_leg", CubeListBuilder.create()
                        .texOffs(0, 27).addBox(-3.9F, 0.0F, -1.99F, 4.0F, 5.0F, 4.0F, deformation),
                PartPose.offset(0.0F, 19.0F, 0.0F));

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(EmeraldSkrimisher entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        // The supplied export contains no authored animation.
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer,
                               int packedLight, int packedOverlay, int packedColor) {
        head.render(poseStack, vertexConsumer, packedLight, packedOverlay, packedColor);
        body.render(poseStack, vertexConsumer, packedLight, packedOverlay, packedColor);
        leftArm.render(poseStack, vertexConsumer, packedLight, packedOverlay, packedColor);
        rightArm.render(poseStack, vertexConsumer, packedLight, packedOverlay, packedColor);
        leftLeg.render(poseStack, vertexConsumer, packedLight, packedOverlay, packedColor);
        rightLeg.render(poseStack, vertexConsumer, packedLight, packedOverlay, packedColor);
    }
}
