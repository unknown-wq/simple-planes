package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Single-use strike drone, propeller layer: the rear pusher propeller, a hub and spinner with two tapered,
 * pitched blades, 14 px across. Uses the same {@code textures/plane_upgrades/strike_drone_metal.png} as
 * {@link StrikeDroneMetalModel}.
 *
 * <p>Spins about the fuselage axis from {@code state.propellerRotation}, exactly like {@link PropellerModel}.
 */
public class StrikeDronePropellerModel extends EntityModel<PlaneRenderState> {

    /** Blade pitch about each blade's long axis, in radians. */
    public static final float BLADE_PITCH = 0.3491F;

    private final ModelPart Propeller;

    public StrikeDronePropellerModel(ModelPart root) {
        super(root);
        this.Propeller = root.getChild("Propeller");
    }

    private static CubeListBuilder blade() {
        return CubeListBuilder.create()
                .texOffs(52, 13).addBox(-1.0F, -5.0F, -0.5F, 2.0F, 4.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(60, 11).addBox(-0.5F, -7.0F, -0.5F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F));
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        // Pivot on the fuselage axis just behind the engine: absolute (0, 16.5, 16), local (0, -7.5, 16).
        PartDefinition Propeller = partdefinition.addOrReplaceChild("Propeller", CubeListBuilder.create()
                .texOffs(52, 9).addBox(-1.0F, -1.0F, 0.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(58, 14).addBox(-0.5F, -0.5F, 2.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 16.5F, 16.0F));

        // The second blade is the first turned half a turn about the shaft, so both have the same pitch sense.
        Propeller.addOrReplaceChild("blade_a", blade(), PartPose.offsetAndRotation(0.0F, 0.0F, 1.0F, 0.0F, BLADE_PITCH, 0.0F));
        Propeller.addOrReplaceChild("blade_b", blade(), PartPose.offsetAndRotation(0.0F, 0.0F, 1.0F, 0.0F, BLADE_PITCH, 3.1416F));

        return LayerDefinition.create(meshdefinition, 64, 64);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {
        super.setupAnim(state);
        this.Propeller.zRot = state.propellerRotation;
    }
}
