package com.orangevillager61.emeraldcapitalism.client.model;

import com.orangevillager61.emeraldcapitalism.entity.EmeraldSkrimisher;
import net.minecraft.client.model.IronGolemModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/** Iron-golem-based model for the Emerald Skrimisher. */
public final class EmeraldSkrimisherModel extends IronGolemModel<EmeraldSkrimisher> {

    public EmeraldSkrimisherModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        root.addOrReplaceChild("body", CubeListBuilder.create()
                        .texOffs(0, 15).addBox(-4.0F, -6.0F, -3.0F, 8.0F, 6.0F, 6.0F),
                PartPose.offset(0.0F, -5.0F, 0.0F));
        root.addOrReplaceChild("head", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0F, -5.0F, -5.0F, 8.0F, 5.0F, 10.0F)
                        .texOffs(56, 0).addBox(-1.0F, -2.0F, -6.0F, 2.0F, 3.0F, 2.0F)
                        .texOffs(37, 8).addBox(-1.0F, -9.0F, -1.0F, 2.0F, 4.0F, 2.0F),
                PartPose.offset(0.0F, -6.0F, 0.0F));
        root.addOrReplaceChild("right_arm", CubeListBuilder.create()
                        .texOffs(36, 16).addBox(-3.0F, -1.0F, -2.0F, 3.0F, 10.0F, 4.0F),
                PartPose.offset(-4.0F, -6.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create()
                        .texOffs(50, 16).addBox(0.0F, -1.0F, -2.0F, 3.0F, 10.0F, 4.0F),
                PartPose.offset(4.0F, -6.0F, 0.0F));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create()
                        .texOffs(0, 27).addBox(-4.0F, 0.0F, -2.0F, 4.0F, 5.0F, 4.0F),
                PartPose.offset(0.0F, -5.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create()
                        .texOffs(16, 27).addBox(0.0F, 0.0F, -2.0F, 4.0F, 5.0F, 4.0F),
                PartPose.offset(0.0F, -5.0F, 0.0F));

        return LayerDefinition.create(mesh, 64, 64);
    }
}
