package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Mini helicopter, rotor layer (the renderer's propeller slot): the two-blade main rotor with its mast and hub,
 * and the two-blade tail rotor on the left side of the fin. Uses the same
 * {@code textures/plane_upgrades/mini_heli_metal.png} as {@link MiniHeliMetalModel}.
 *
 * <p>Both rotors spin from {@code state.propellerRotation} with the axes and signs of
 * {@link HelicopterPropellerModel}: {@code main_rotor.yRot = rotation} about the vertical mast and
 * {@code tail_rotor.xRot = rotation} about the tail rotor's lateral shaft. Default render type
 * ({@code entityCutout}, two-sided); no eye is inside these cubes.
 */
public class MiniHeliRotorModel extends EntityModel<PlaneRenderState> {

    private final ModelPart main_rotor;
    private final ModelPart tail_rotor;

    public MiniHeliRotorModel(ModelPart root) {
        super(root);
        ModelPart rotors = root.getChild("Rotors");
        this.main_rotor = rotors.getChild("main_rotor");
        this.tail_rotor = rotors.getChild("tail_rotor");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Rotors = partdefinition.addOrReplaceChild("Rotors", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Pivot on the mast axis at the blade root plane. The mast reaches down to the engine top (y = -20).
        Rotors.addOrReplaceChild("main_rotor", CubeListBuilder.create()
                .texOffs(70, 0).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 14.0F, 2.0F, CubeDeformation.NONE)
                .texOffs(102, 0).addBox(-2.0F, -2.5F, -2.0F, 4.0F, 2.0F, 4.0F, CubeDeformation.NONE)
                .texOffs(0, 25).addBox(-27.0F, -1.0F, -1.5F, 54.0F, 1.0F, 3.0F, CubeDeformation.NONE),
                PartPose.offset(0.0F, -34.0F, 4.0F));

        // Pivot on the tail rotor shaft, against the left (+X) side of the fin.
        Rotors.addOrReplaceChild("tail_rotor", CubeListBuilder.create()
                .texOffs(114, 0).addBox(-0.5F, -1.0F, -1.0F, 3.0F, 2.0F, 2.0F, CubeDeformation.NONE)
                .texOffs(26, 0).addBox(0.5F, -1.0F, -6.0F, 1.0F, 2.0F, 12.0F, CubeDeformation.NONE),
                PartPose.offset(1.0F, -18.0F, 34.0F));

        return LayerDefinition.create(meshdefinition, 128, 64);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {
        super.setupAnim(state);
        this.main_rotor.yRot = state.propellerRotation;
        this.tail_rotor.xRot = state.propellerRotation;
    }
}
