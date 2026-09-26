package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Airship, envelope layer: the gasbag and the four fixed tail fins. Uses the tiling fabric texture
 * {@code textures/plane_upgrades/airship_envelope.png} (32x32, declared 32x32 so every face repeats it at
 * 1 texel per pixel, exactly as the material layer repeats a block texture).
 *
 * <p>This is a fourth layer: {@code PlaneRenderer} draws three models today, so the airship needs one more
 * {@code submitModel} call with the same pose (see AIRSHIP-MODEL.md).
 *
 * <p>The gasbag is a stack of rings along its axis (y = -88 under the root). Each ring is a stepped circle of
 * three or five nested boxes whose outer corners lie on a body of revolution, so it reads round from the
 * front and smooth from the side. The numbers were produced by a
 * generator script that is not in the repository; this file can be edited by hand.
 */
public class AirshipEnvelopeModel extends EntityModel<PlaneRenderState> {
    private final ModelPart envelope;

    public AirshipEnvelopeModel(ModelPart root) {
        super(root);
        this.envelope = root.getChild("Envelope");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Envelope = partdefinition.addOrReplaceChild("Envelope", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        Envelope.addOrReplaceChild("Gasbag", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-5.0F, -93.0F, -142.0F, 10.0F, 10.0F, 269.0F, new CubeDeformation(0.0F))
                .texOffs(7, 13).addBox(-6.0F, -91.0F, -141.0F, 12.0F, 6.0F, 267.0F, new CubeDeformation(0.0F))
                .texOffs(14, 26).addBox(-3.0F, -94.0F, -141.0F, 6.0F, 12.0F, 267.0F, new CubeDeformation(0.0F))
                .texOffs(21, 7).addBox(-9.0F, -97.0F, -139.0F, 18.0F, 18.0F, 257.0F, new CubeDeformation(0.0F))
                .texOffs(28, 20).addBox(-12.0F, -93.0F, -138.0F, 24.0F, 10.0F, 255.0F, new CubeDeformation(0.0F))
                .texOffs(3, 1).addBox(-5.0F, -100.0F, -138.0F, 10.0F, 24.0F, 255.0F, new CubeDeformation(0.0F))
                .texOffs(10, 14).addBox(-14.0F, -102.0F, -135.0F, 28.0F, 28.0F, 241.0F, new CubeDeformation(0.0F))
                .texOffs(17, 27).addBox(-18.0F, -96.0F, -134.0F, 36.0F, 16.0F, 239.0F, new CubeDeformation(0.0F))
                .texOffs(24, 8).addBox(-8.0F, -106.0F, -134.0F, 16.0F, 36.0F, 239.0F, new CubeDeformation(0.0F))
                .texOffs(31, 21).addBox(-17.0F, -105.0F, -129.0F, 34.0F, 34.0F, 221.0F, new CubeDeformation(0.0F))
                .texOffs(6, 2).addBox(-22.0F, -97.0F, -128.0F, 44.0F, 18.0F, 219.0F, new CubeDeformation(0.0F))
                .texOffs(13, 15).addBox(-9.0F, -110.0F, -128.0F, 18.0F, 44.0F, 219.0F, new CubeDeformation(0.0F))
                .texOffs(20, 28).addBox(-20.0F, -108.0F, -121.0F, 40.0F, 40.0F, 199.0F, new CubeDeformation(0.0F))
                .texOffs(27, 9).addBox(-25.0F, -99.0F, -120.0F, 50.0F, 22.0F, 197.0F, new CubeDeformation(0.0F))
                .texOffs(2, 22).addBox(-11.0F, -113.0F, -120.0F, 22.0F, 50.0F, 197.0F, new CubeDeformation(0.0F))
                .texOffs(9, 3).addBox(-22.0F, -110.0F, -114.0F, 44.0F, 44.0F, 178.0F, new CubeDeformation(0.0F))
                .texOffs(16, 16).addBox(-26.0F, -104.0F, -113.0F, 52.0F, 32.0F, 176.0F, new CubeDeformation(0.0F))
                .texOffs(23, 29).addBox(-16.0F, -114.0F, -113.0F, 32.0F, 52.0F, 176.0F, new CubeDeformation(0.0F))
                .texOffs(30, 10).addBox(-30.0F, -94.0F, -112.0F, 60.0F, 12.0F, 174.0F, new CubeDeformation(0.0F))
                .texOffs(5, 23).addBox(-6.0F, -118.0F, -112.0F, 12.0F, 60.0F, 174.0F, new CubeDeformation(0.0F))
                .texOffs(12, 4).addBox(-24.0F, -112.0F, -105.0F, 48.0F, 48.0F, 154.0F, new CubeDeformation(0.0F))
                .texOffs(19, 17).addBox(-29.0F, -106.0F, -104.0F, 58.0F, 36.0F, 152.0F, new CubeDeformation(0.0F))
                .texOffs(26, 30).addBox(-18.0F, -117.0F, -104.0F, 36.0F, 58.0F, 152.0F, new CubeDeformation(0.0F))
                .texOffs(1, 11).addBox(-33.0F, -95.0F, -103.0F, 66.0F, 14.0F, 150.0F, new CubeDeformation(0.0F))
                .texOffs(8, 24).addBox(-7.0F, -121.0F, -103.0F, 14.0F, 66.0F, 150.0F, new CubeDeformation(0.0F))
                .texOffs(15, 5).addBox(-26.0F, -114.0F, -95.0F, 52.0F, 52.0F, 129.0F, new CubeDeformation(0.0F))
                .texOffs(22, 18).addBox(-31.0F, -107.0F, -94.0F, 62.0F, 38.0F, 127.0F, new CubeDeformation(0.0F))
                .texOffs(29, 31).addBox(-19.0F, -119.0F, -94.0F, 38.0F, 62.0F, 127.0F, new CubeDeformation(0.0F))
                .texOffs(4, 12).addBox(-35.0F, -96.0F, -93.0F, 70.0F, 16.0F, 125.0F, new CubeDeformation(0.0F))
                .texOffs(11, 25).addBox(-8.0F, -123.0F, -93.0F, 16.0F, 70.0F, 125.0F, new CubeDeformation(0.0F))
                .texOffs(18, 6).addBox(-27.0F, -115.0F, -85.0F, 54.0F, 54.0F, 104.0F, new CubeDeformation(0.0F))
                .texOffs(25, 19).addBox(-32.0F, -108.0F, -84.0F, 64.0F, 40.0F, 102.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).addBox(-20.0F, -120.0F, -84.0F, 40.0F, 64.0F, 102.0F, new CubeDeformation(0.0F))
                .texOffs(7, 13).addBox(-37.0F, -97.0F, -83.0F, 74.0F, 18.0F, 100.0F, new CubeDeformation(0.0F))
                .texOffs(14, 26).addBox(-9.0F, -125.0F, -83.0F, 18.0F, 74.0F, 100.0F, new CubeDeformation(0.0F))
                .texOffs(21, 7).addBox(-28.0F, -116.0F, -73.0F, 56.0F, 56.0F, 77.0F, new CubeDeformation(0.0F))
                .texOffs(28, 20).addBox(-33.0F, -109.0F, -72.0F, 66.0F, 42.0F, 75.0F, new CubeDeformation(0.0F))
                .texOffs(3, 1).addBox(-21.0F, -121.0F, -72.0F, 42.0F, 66.0F, 75.0F, new CubeDeformation(0.0F))
                .texOffs(10, 14).addBox(-38.0F, -98.0F, -71.0F, 76.0F, 20.0F, 73.0F, new CubeDeformation(0.0F))
                .texOffs(17, 27).addBox(-10.0F, -126.0F, -71.0F, 20.0F, 76.0F, 73.0F, new CubeDeformation(0.0F))
                .texOffs(24, 8).addBox(-29.0F, -117.0F, -60.0F, 58.0F, 58.0F, 49.0F, new CubeDeformation(0.0F))
                .texOffs(31, 21).addBox(-34.0F, -110.0F, -59.0F, 68.0F, 44.0F, 47.0F, new CubeDeformation(0.0F))
                .texOffs(6, 2).addBox(-22.0F, -122.0F, -59.0F, 44.0F, 68.0F, 47.0F, new CubeDeformation(0.0F))
                .texOffs(13, 15).addBox(-39.0F, -99.0F, -58.0F, 78.0F, 22.0F, 45.0F, new CubeDeformation(0.0F))
                .texOffs(20, 28).addBox(-11.0F, -127.0F, -58.0F, 22.0F, 78.0F, 45.0F, new CubeDeformation(0.0F)), PartPose.ZERO);
        Envelope.addOrReplaceChild("Fins", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-1.0F, -124.0F, 54.0F, 2.0F, 32.0F, 50.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).addBox(-1.0F, -84.0F, 54.0F, 2.0F, 32.0F, 50.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).addBox(4.0F, -89.0F, 54.0F, 32.0F, 2.0F, 50.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).mirror().addBox(-36.0F, -89.0F, 54.0F, 32.0F, 2.0F, 50.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(9, 5).addBox(-1.0F, -128.0F, 62.0F, 2.0F, 4.0F, 42.0F, new CubeDeformation(0.0F))
                .texOffs(9, 5).addBox(-1.0F, -52.0F, 62.0F, 2.0F, 4.0F, 42.0F, new CubeDeformation(0.0F))
                .texOffs(9, 5).addBox(36.0F, -89.0F, 62.0F, 4.0F, 2.0F, 42.0F, new CubeDeformation(0.0F))
                .texOffs(9, 5).mirror().addBox(-40.0F, -89.0F, 62.0F, 4.0F, 2.0F, 42.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(18, 10).addBox(-1.0F, -132.0F, 70.0F, 2.0F, 4.0F, 34.0F, new CubeDeformation(0.0F))
                .texOffs(18, 10).addBox(-1.0F, -48.0F, 70.0F, 2.0F, 4.0F, 34.0F, new CubeDeformation(0.0F))
                .texOffs(18, 10).addBox(40.0F, -89.0F, 70.0F, 4.0F, 2.0F, 34.0F, new CubeDeformation(0.0F))
                .texOffs(18, 10).mirror().addBox(-44.0F, -89.0F, 70.0F, 4.0F, 2.0F, 34.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(27, 15).addBox(-1.0F, -136.0F, 78.0F, 2.0F, 4.0F, 26.0F, new CubeDeformation(0.0F))
                .texOffs(27, 15).addBox(-1.0F, -44.0F, 78.0F, 2.0F, 4.0F, 26.0F, new CubeDeformation(0.0F))
                .texOffs(27, 15).addBox(44.0F, -89.0F, 78.0F, 4.0F, 2.0F, 26.0F, new CubeDeformation(0.0F))
                .texOffs(27, 15).mirror().addBox(-48.0F, -89.0F, 78.0F, 4.0F, 2.0F, 26.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);

        return LayerDefinition.create(meshdefinition, 32, 32);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
