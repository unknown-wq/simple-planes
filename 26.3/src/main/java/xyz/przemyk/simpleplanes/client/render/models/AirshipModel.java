package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Airship, material (wood) layer: the gondola hull, cabin roof, pilot seat and passenger benches. Textured with
 * the block texture of the plane's material, tiled 1 texel per pixel like {@link PlaneModel}.
 *
 * <p>Nose points to -Z; the ground contact (the landing wheel under the gondola) is at y = 24 and the gondola
 * is centred on z = 0. See AIRSHIP-MODEL.md. The numbers were produced by a
 * generator script that is not in the repository; this file can be edited by hand.
 */
public class AirshipModel extends EntityModel<PlaneRenderState> {
    private final ModelPart airship;

    public AirshipModel(ModelPart root) {
        super(root);
        this.airship = root.getChild("Airship");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Airship = partdefinition.addOrReplaceChild("Airship", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        Airship.addOrReplaceChild("Hull", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-19.0F, -9.0F, -42.0F, 38.0F, 5.0F, 92.0F, new CubeDeformation(0.0F))
                .texOffs(4, 8).addBox(-13.0F, -4.0F, -38.0F, 26.0F, 2.0F, 84.0F, new CubeDeformation(0.0F))
                .texOffs(2, 4).addBox(-17.0F, -20.0F, -50.0F, 34.0F, 16.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(6, 2).addBox(-13.0F, -20.0F, -54.0F, 26.0F, 14.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(4, 6).addBox(-8.0F, -19.0F, -57.0F, 16.0F, 11.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(8, 6).addBox(-17.0F, -20.0F, 50.0F, 34.0F, 11.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(10, 4).addBox(-15.0F, -18.0F, 52.0F, 30.0F, 13.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(0, 4).addBox(17.0F, -20.0F, -42.0F, 2.0F, 11.0F, 94.0F, new CubeDeformation(0.0F))
                .texOffs(0, 4).mirror().addBox(-19.0F, -20.0F, -42.0F, 2.0F, 11.0F, 94.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);
        Airship.addOrReplaceChild("Cabin", CubeListBuilder.create()
                .texOffs(2, 2).addBox(-20.0F, -38.0F, -50.0F, 40.0F, 3.0F, 54.0F, new CubeDeformation(0.0F))
                .texOffs(6, 6).addBox(-16.0F, -40.0F, -46.0F, 32.0F, 2.0F, 46.0F, new CubeDeformation(0.0F)), PartPose.ZERO);
        Airship.addOrReplaceChild("Seats", CubeListBuilder.create()
                .texOffs(0, 10).addBox(-5.0F, -12.0F, -32.0F, 10.0F, 3.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(4, 12).addBox(-5.0F, -24.0F, -24.0F, 10.0F, 12.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(0, 10).addBox(-17.0F, -12.0F, -10.0F, 34.0F, 3.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(4, 12).addBox(-17.0F, -22.0F, -2.0F, 34.0F, 10.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(0, 10).addBox(-17.0F, -12.0F, 16.0F, 34.0F, 3.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(4, 12).addBox(-17.0F, -22.0F, 24.0F, 34.0F, 10.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(0, 10).addBox(-17.0F, -12.0F, 40.0F, 34.0F, 3.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(4, 12).addBox(-17.0F, -22.0F, 48.0F, 34.0F, 10.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        return LayerDefinition.create(meshdefinition, 16, 16);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
