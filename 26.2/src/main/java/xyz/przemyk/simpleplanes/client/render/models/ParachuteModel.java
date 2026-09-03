package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

public class ParachuteModel extends EntityModel<EntityRenderState> {

	private final ModelPart material;
	private final ModelPart bb_main;

	public ParachuteModel(ModelPart root) {
		super(root);
		this.material = root.getChild("material");
		this.bb_main = root.getChild("bb_main");
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition meshdefinition = new MeshDefinition();
		PartDefinition partdefinition = meshdefinition.getRoot();

		PartDefinition material = partdefinition.addOrReplaceChild("material", CubeListBuilder.create().texOffs(0, 0).addBox(-8.0F, -1.0F, -7.0F, 16.0F, 1.0F, 14.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));

		material.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(0, 0).addBox(0.0F, 0.0F, -7.0F, 16.0F, 1.0F, 14.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(8.0F, -1.0F, 0.0F, 0.0F, 0.0F, 0.3927F));

		material.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(0, 0).addBox(-16.0F, 0.0F, -7.0F, 16.0F, 1.0F, 14.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-8.0F, -1.0F, 0.0F, 0.0F, 0.0F, -0.3927F));

		PartDefinition bb_main = partdefinition.addOrReplaceChild("bb_main", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

		bb_main.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(0, 0).addBox(-1.9624F, -28.5897F, -0.4467F, 1.0F, 25.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 2.9147F, -0.2618F, -2.2864F));

		bb_main.addOrReplaceChild("cube_r4", CubeListBuilder.create().texOffs(0, 0).addBox(-2.2212F, -28.807F, -0.4945F, 1.0F, 25.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, -2.9147F, 0.2618F, -2.2864F));

		bb_main.addOrReplaceChild("cube_r5", CubeListBuilder.create().texOffs(0, 0).addBox(-1.0F, -1.0F, -3.0F, 1.0F, 25.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-21.0F, -18.0F, -4.0F, 0.2269F, -0.2618F, -0.8552F));

		bb_main.addOrReplaceChild("cube_r6", CubeListBuilder.create().texOffs(0, 0).addBox(-1.0F, -1.0F, 3.0F, 1.0F, 25.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-21.0F, -18.0F, 3.0F, -0.2269F, 0.2618F, -0.8552F));

		return LayerDefinition.create(meshdefinition, 16, 16);
	}

	@Override
	public void setupAnim(EntityRenderState state) {}
}