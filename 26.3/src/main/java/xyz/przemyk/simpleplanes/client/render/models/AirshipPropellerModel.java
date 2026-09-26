package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Airship propellers: the two tractor propellers at the front of the engine cars. Goes into the renderer's
 * propeller slot and uses the same texture as {@link AirshipMetalModel}
 * ({@code textures/plane_upgrades/airship_metal.png}).
 *
 * <p>Both spin from {@code state.propellerRotation} like {@link PropellerModel}; the right one turns the
 * other way. See AIRSHIP-MODEL.md. The numbers were produced by a
 * generator script that is not in the repository; this file can be edited by hand.
 */
public class AirshipPropellerModel extends EntityModel<PlaneRenderState> {
    private final ModelPart propLeft;
    private final ModelPart propRight;

    public AirshipPropellerModel(ModelPart root) {
        super(root);
        this.propLeft = root.getChild("PropLeft");
        this.propRight = root.getChild("PropRight");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        // PropLeft: hub; zRot = propeller angle
        partdefinition.addOrReplaceChild("PropLeft", CubeListBuilder.create()
                .texOffs(204, 86).addBox(-2.0F, -2.0F, -2.0F, 4.0F, 4.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(240, 87).addBox(-1.5F, -14.0F, -0.5F, 3.0F, 28.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offset(42.0F, 2.0F, 10.0F));

        // PropRight: hub; zRot = propeller angle
        partdefinition.addOrReplaceChild("PropRight", CubeListBuilder.create()
                .texOffs(204, 86).addBox(-2.0F, -2.0F, -2.0F, 4.0F, 4.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(240, 87).addBox(-1.5F, -14.0F, -0.5F, 3.0F, 28.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offset(-42.0F, 2.0F, 10.0F));

        return LayerDefinition.create(meshdefinition, 256, 256);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {
        super.setupAnim(state);
        applyPropellerRotation(state.propellerRotation);
    }

    /**
     * Sets both propellers to the given angle in radians: the left one turns with it, the right one against
     * it (counter-rotating pair). Call after the pose reset done by {@code super.setupAnim}.
     */
    public void applyPropellerRotation(float angle) {
        this.propLeft.zRot = angle;
        this.propRight.zRot = -angle;
    }
}
