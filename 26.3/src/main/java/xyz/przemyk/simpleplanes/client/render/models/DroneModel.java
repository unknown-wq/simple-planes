package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Mini reconnaissance quadcopter, material layer: the airframe proper, meaning the bottom frame plate and the
 * two crossed arm beams of the X frame. Textured with the block texture of the plane's material, tiled
 * 1 texel per pixel like {@link PlaneModel}.
 *
 * <p>Everything else on a drone is electronics or plastic (shell, battery, motors, camera, legs, clamp), so
 * it lives on {@link DroneMetalModel}; only the structural frame shows the material. Nose points to -Z and
 * the bottom of the landing feet is at local y = 0 (see DRONE-MODEL.md).
 */
public class DroneModel extends EntityModel<PlaneRenderState> {

    private final ModelPart frame;

    public DroneModel(ModelPart root) {
        super(root);
        this.frame = root.getChild("Frame");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Frame = partdefinition.addOrReplaceChild("Frame", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-3.0F, -8.0F, -5.0F, 6.0F, 1.0F, 10.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Two full-length beams crossing under the shell; each end carries a motor (front-left/rear-right and
        // front-right/rear-left diagonals).
        Frame.addOrReplaceChild("arm_a", CubeListBuilder.create()
                .texOffs(0, 3).addBox(-9.0F, -0.5F, -1.0F, 18.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -8.5F, 0.0F, 0.0F, 0.7854F, 0.0F));

        Frame.addOrReplaceChild("arm_b", CubeListBuilder.create()
                .texOffs(0, 9).addBox(-9.0F, -0.5F, -1.0F, 18.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -8.5F, 0.0F, 0.0F, -0.7854F, 0.0F));

        return LayerDefinition.create(meshdefinition, 16, 16);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
