package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Single-use strike drone, metal layer: the slim fuselage with its avionics spine and keel, the pointed nose
 * holding the warhead and the seeker, the rear engine with its exhaust and cooling scoop, and the GPS puck and
 * whip antenna. Uses {@code textures/plane_upgrades/strike_drone_metal.png} (64x64).
 *
 * <p>No landing gear: the drone is launched and not recovered. See STRIKE-DRONE-MODEL.md.
 */
public class StrikeDroneMetalModel extends EntityModel<PlaneRenderState> {
    private final ModelPart Metal;

    public StrikeDroneMetalModel(ModelPart root) {
        super(root);
        this.Metal = root.getChild("Metal");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Metal = partdefinition.addOrReplaceChild("Metal", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Fuselage centre line at y = -7.5. The spine on top and the keel below round off the square section.
        Metal.addOrReplaceChild("Fuselage", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-3.0F, -10.0F, -8.0F, 6.0F, 5.0F, 20.0F, new CubeDeformation(0.0F))
                .texOffs(0, 25).addBox(-2.0F, -11.0F, -6.0F, 4.0F, 1.0F, 16.0F, new CubeDeformation(0.0F))
                .texOffs(0, 42).addBox(-2.0F, -5.0F, -6.0F, 4.0F, 1.0F, 14.0F, new CubeDeformation(0.0F))
                .texOffs(36, 53).addBox(-1.5F, -12.0F, 4.0F, 3.0F, 1.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(52, 18).addBox(-1.0F, -12.0F, -4.0F, 2.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        // Warhead section and pointed nose, stepping down to the seeker window at the tip (z = -22).
        Metal.addOrReplaceChild("Warhead", CubeListBuilder.create()
                .texOffs(40, 25).addBox(-3.0F, -10.0F, -14.0F, 6.0F, 5.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(40, 36).addBox(-2.0F, -9.5F, -19.0F, 4.0F, 4.0F, 5.0F, new CubeDeformation(0.0F))
                .texOffs(52, 0).addBox(-1.0F, -8.5F, -22.0F, 2.0F, 2.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        // Engine behind the fuselage, driving the pusher propeller (StrikeDronePropellerModel).
        Metal.addOrReplaceChild("Engine", CubeListBuilder.create()
                .texOffs(36, 45).addBox(-2.0F, -9.5F, 12.0F, 4.0F, 4.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(52, 5).addBox(2.0F, -7.0F, 12.0F, 1.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        Metal.addOrReplaceChild("antenna", CubeListBuilder.create()
                .texOffs(60, 5).addBox(-0.5F, -5.0F, -0.5F, 1.0F, 5.0F, 1.0F, new CubeDeformation(-0.25F)), PartPose.offsetAndRotation(0.0F, -10.75F, 1.0F, -0.4363F, 0.0F, 0.0F));

        return LayerDefinition.create(meshdefinition, 64, 64);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
