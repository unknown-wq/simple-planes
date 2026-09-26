package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Single-use strike drone, material layer: the cropped-delta wing and the up-and-down tip winglets.
 * Textured with the block texture of the plane's material, tiled 1 texel per pixel like {@link PlaneModel}.
 *
 * <p>The large flat lifting surfaces are what a Simple Planes builder makes from the material block; the
 * fuselage, warhead and engine are on {@link StrikeDroneMetalModel}. Nose points to -Z; the lower winglet tips
 * are the lowest point, at local y = 0 (see STRIKE-DRONE-MODEL.md).
 */
public class StrikeDroneModel extends EntityModel<PlaneRenderState> {

    private final ModelPart airframe;

    public StrikeDroneModel(ModelPart root) {
        super(root);
        this.airframe = root.getChild("Airframe");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Airframe = partdefinition.addOrReplaceChild("Airframe", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Cropped delta, four 5 px steps per side, 45 degree leading edge, straight trailing edge at z = 12.
        Airframe.addOrReplaceChild("Wings", CubeListBuilder.create()
                .texOffs(0, 0).addBox(3.0F, -8.0F, -10.0F, 5.0F, 1.0F, 22.0F, new CubeDeformation(0.0F))
                .texOffs(4, 2).addBox(8.0F, -8.0F, -5.0F, 5.0F, 1.0F, 17.0F, new CubeDeformation(0.0F))
                .texOffs(8, 4).addBox(13.0F, -8.0F, 0.0F, 5.0F, 1.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(12, 6).addBox(18.0F, -8.0F, 5.0F, 5.0F, 1.0F, 7.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).mirror().addBox(-8.0F, -8.0F, -10.0F, 5.0F, 1.0F, 22.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(4, 2).mirror().addBox(-13.0F, -8.0F, -5.0F, 5.0F, 1.0F, 17.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(8, 4).mirror().addBox(-18.0F, -8.0F, 0.0F, 5.0F, 1.0F, 12.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(12, 6).mirror().addBox(-23.0F, -8.0F, 5.0F, 5.0F, 1.0F, 7.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);

        // Tip winglets above and below the wing, swept in steps. The lower tips are the ground contact (y = 0).
        Airframe.addOrReplaceChild("Winglets", CubeListBuilder.create()
                .texOffs(2, 8).addBox(23.0F, -10.0F, 6.0F, 1.0F, 2.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(6, 10).addBox(23.0F, -12.0F, 8.0F, 1.0F, 2.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(10, 12).addBox(23.0F, -14.0F, 10.0F, 1.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(2, 12).addBox(23.0F, -7.0F, 6.0F, 1.0F, 3.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(6, 14).addBox(23.0F, -4.0F, 9.0F, 1.0F, 4.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(2, 8).mirror().addBox(-24.0F, -10.0F, 6.0F, 1.0F, 2.0F, 6.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(6, 10).mirror().addBox(-24.0F, -12.0F, 8.0F, 1.0F, 2.0F, 4.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(10, 12).mirror().addBox(-24.0F, -14.0F, 10.0F, 1.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(2, 12).mirror().addBox(-24.0F, -7.0F, 6.0F, 1.0F, 3.0F, 6.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(6, 14).mirror().addBox(-24.0F, -4.0F, 9.0F, 1.0F, 4.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);

        return LayerDefinition.create(meshdefinition, 16, 16);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
