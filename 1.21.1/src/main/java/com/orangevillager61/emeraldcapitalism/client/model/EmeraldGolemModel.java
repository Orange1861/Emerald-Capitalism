package com.orangevillager61.emeraldcapitalism.client.model;

import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import net.minecraft.client.model.IronGolemModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * Client-only model for the emerald golem.
 *
 * <p>The geometry is converted from emerald_golem_current.bbmodel (an OptiFine
 * Entity-format Blockbench project). Animation remains the vanilla
 * {@link IronGolemModel} animation so gameplay values and animation timing are
 * unchanged.</p>
 */
public class EmeraldGolemModel<T extends EmeraldGolem> extends IronGolemModel<T> {

    public EmeraldGolemModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition parts = mesh.getRoot();

        parts.addOrReplaceChild("head", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0F, -12.0F, -5.5F, 8.0F, 10.0F, 8.0F)
                        .texOffs(24, 0).addBox(-1.0F, -5.0F, -7.5F, 2.0F, 4.0F, 2.0F),
                PartPose.offset(0.0F, 3.75F, -2.0F));

        parts.addOrReplaceChild("body", CubeListBuilder.create()
                        .texOffs(10, 42).addBox(-5.0F, 8.0F, -5.0F, 10.0F, 8.0F, 9.0F)
                        .texOffs(2, 70).addBox(-3.5F, 16.0F, -3.0F, 7.0F, 2.0F, 6.0F),
                PartPose.offset(0.0F, -6.25F, 0.0F));

        parts.addOrReplaceChild("right_arm", CubeListBuilder.create()
                        .texOffs(62, 22).addBox(5.0F, -2.5F, -3.0F, 3.0F, 21.0F, 5.0F)
                        .texOffs(62, 59).addBox(5.0F, -3.5F, -3.0F, 2.0F, 1.0F, 5.0F),
                PartPose.offset(0.0F, 4.75F, 0.0F));

        parts.addOrReplaceChild("left_arm", CubeListBuilder.create()
                        .texOffs(62, 59).addBox(-8.0F, -2.5F, -3.0F, 3.0F, 21.0F, 5.0F)
                        .texOffs(60, 59).addBox(-7.0F, -3.5F, -3.0F, 2.0F, 1.0F, 5.0F),
                PartPose.offset(0.0F, 4.75F, 0.0F));

        parts.addOrReplaceChild("right_leg", CubeListBuilder.create()
                        .texOffs(40, 0).addBox(-3.5F, 0.0F, -3.0F, 3.0F, 13.0F, 5.0F),
                PartPose.offset(4.0F, 11.75F, 0.0F));

        parts.addOrReplaceChild("left_leg", CubeListBuilder.create()
                        .texOffs(63, 0).addBox(1.5F, 0.0F, -3.0F, 3.0F, 13.0F, 5.0F),
                PartPose.offset(-5.0F, 11.75F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }
}
