package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Mini reconnaissance quadcopter, rotor layer (the renderer's propeller slot): four two-blade rotors on the
 * motors of {@link DroneMetalModel}. Uses the same {@code textures/plane_upgrades/drone_metal.png}.
 *
 * <p>Each rotor spins about its own vertical axis from {@code state.propellerRotation}, the same input
 * {@link PropellerModel} uses. Diagonal pairs turn the same way and adjacent rotors counter-rotate:
 * front-left and rear-right with {@code +rotation}, front-right and rear-left with {@code -rotation}.
 * At rotation 0 every blade lies across its arm. The blades are flat 0-thickness planes with a cut-out
 * blade shape, visible from above and below.
 */
public class DroneRotorModel extends EntityModel<PlaneRenderState> {

    /** Blade angle at rotation 0, so the blades start across the arms (left/right mirror-symmetric). */
    public static final float BLADE_PHASE = 0.7854F;

    private final ModelPart rotor_front_left;
    private final ModelPart rotor_front_right;
    private final ModelPart rotor_rear_left;
    private final ModelPart rotor_rear_right;

    public DroneRotorModel(ModelPart root) {
        super(root);
        ModelPart rotors = root.getChild("Rotors");
        this.rotor_front_left = rotors.getChild("rotor_front_left");
        this.rotor_front_right = rotors.getChild("rotor_front_right");
        this.rotor_rear_left = rotors.getChild("rotor_rear_left");
        this.rotor_rear_right = rotors.getChild("rotor_rear_right");
    }

    private static CubeListBuilder rotor() {
        return CubeListBuilder.create()
                .texOffs(4, 17).addBox(-0.5F, -1.0F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(16, 14).addBox(-4.5F, -0.5F, -1.0F, 9.0F, 0.0F, 2.0F, new CubeDeformation(0.0F));
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Rotors = partdefinition.addOrReplaceChild("Rotors", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Pivots on the motor axes, at the top of the motor bells (y = -12). +X is the aircraft's left.
        Rotors.addOrReplaceChild("rotor_front_left", rotor(), PartPose.offsetAndRotation(6.5F, -12.0F, -6.5F, 0.0F, -BLADE_PHASE, 0.0F));
        Rotors.addOrReplaceChild("rotor_front_right", rotor(), PartPose.offsetAndRotation(-6.5F, -12.0F, -6.5F, 0.0F, BLADE_PHASE, 0.0F));
        Rotors.addOrReplaceChild("rotor_rear_left", rotor(), PartPose.offsetAndRotation(6.5F, -12.0F, 6.5F, 0.0F, BLADE_PHASE, 0.0F));
        Rotors.addOrReplaceChild("rotor_rear_right", rotor(), PartPose.offsetAndRotation(-6.5F, -12.0F, 6.5F, 0.0F, -BLADE_PHASE, 0.0F));

        return LayerDefinition.create(meshdefinition, 64, 32);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {
        super.setupAnim(state);
        float r = state.propellerRotation;
        this.rotor_front_left.yRot = r - BLADE_PHASE;
        this.rotor_rear_right.yRot = r - BLADE_PHASE;
        this.rotor_front_right.yRot = -r + BLADE_PHASE;
        this.rotor_rear_left.yRot = -r + BLADE_PHASE;
    }
}
