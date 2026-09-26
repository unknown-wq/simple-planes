package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Mini airliner, material (wood) layer: the tube fuselage with its rounded nose and upswept tail cone, the
 * low swept wings with dihedral, the swept horizontal stabilisers, the tall single fin and the belly fairing.
 * Textured with the block texture of the plane's material, tiled 1 texel per pixel like {@link PlaneModel}.
 *
 * <p>Nose points to -Z, the ground contact of the landing gear is at local y = 0 under the root part
 * {@code Airliner} at y = 24. The fuselage is closed: every seat's eye lies inside the {@code body} box, so the
 * default back-face-culled render type shows the riders the world through the walls (see AIRLINER-MODEL.md).
 */
public class AirlinerModel extends EntityModel<PlaneRenderState> {

    /** Wing dihedral, radians (5 degrees). */
    public static final float WING_DIHEDRAL = 0.0873F;
    /** Horizontal stabiliser dihedral, radians (7 degrees). */
    public static final float STAB_DIHEDRAL = 0.1222F;

    private final ModelPart airliner;

    public AirlinerModel(ModelPart root) {
        super(root);
        this.airliner = root.getChild("Airliner");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Airliner = partdefinition.addOrReplaceChild("Airliner", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Cross-section, widest to narrowest: body 26 x 22, shoulders 24 wide, crown and keel 18 wide.
        PartDefinition Fuselage = Airliner.addOrReplaceChild("Fuselage", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-13.0F, -38.0F, -80.0F, 26.0F, 22.0F, 126.0F, new CubeDeformation(0.0F))
                .texOffs(3, 5).addBox(-12.0F, -40.0F, -76.0F, 24.0F, 2.0F, 134.0F, new CubeDeformation(0.0F))
                .texOffs(7, 1).addBox(-9.0F, -41.0F, -72.0F, 18.0F, 1.0F, 130.0F, new CubeDeformation(0.0F))
                .texOffs(3, 9).addBox(-12.0F, -16.0F, -80.0F, 24.0F, 2.0F, 126.0F, new CubeDeformation(0.0F))
                .texOffs(7, 13).addBox(-9.0F, -14.0F, -80.0F, 18.0F, 1.0F, 126.0F, new CubeDeformation(0.0F))
                .texOffs(5, 3).addBox(-11.0F, -14.0F, -18.0F, 22.0F, 3.0F, 46.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        PartDefinition Nose = Airliner.addOrReplaceChild("Nose", CubeListBuilder.create()
                .texOffs(2, 2).addBox(-12.0F, -30.0F, -85.0F, 24.0F, 16.0F, 5.0F, new CubeDeformation(0.0F))
                .texOffs(6, 6).addBox(-10.0F, -28.0F, -89.0F, 20.0F, 13.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(10, 10).addBox(-7.0F, -26.0F, -92.0F, 14.0F, 10.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(14, 14).addBox(-4.0F, -24.0F, -94.0F, 8.0F, 6.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        // Tail cone: the top line stays level while the belly sweeps up towards the APU.
        PartDefinition TailCone = Airliner.addOrReplaceChild("TailCone", CubeListBuilder.create()
                .texOffs(4, 4).addBox(-12.0F, -38.0F, 46.0F, 24.0F, 21.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(8, 2).addBox(-10.0F, -40.0F, 58.0F, 20.0F, 19.0F, 10.0F, new CubeDeformation(0.0F))
                .texOffs(12, 6).addBox(-7.0F, -39.0F, 68.0F, 14.0F, 13.0F, 10.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        // Low swept wings: six chord steps each, pivoted at the fuselage side and tilted up by the dihedral.
        Airliner.addOrReplaceChild("wing_left", CubeListBuilder.create()
                .texOffs(0, 0).addBox(0.0F, -3.0F, -16.0F, 14.0F, 3.0F, 44.0F, new CubeDeformation(0.0F))
                .texOffs(4, 0).addBox(14.0F, -3.0F, -10.0F, 12.0F, 3.0F, 38.0F, new CubeDeformation(0.0F))
                .texOffs(8, 0).addBox(26.0F, -2.0F, -4.0F, 12.0F, 2.0F, 33.0F, new CubeDeformation(0.0F))
                .texOffs(12, 0).addBox(38.0F, -2.0F, 2.0F, 12.0F, 2.0F, 27.0F, new CubeDeformation(0.0F))
                .texOffs(0, 8).addBox(50.0F, -2.0F, 8.0F, 12.0F, 2.0F, 22.0F, new CubeDeformation(0.0F))
                .texOffs(4, 8).addBox(62.0F, -2.0F, 14.0F, 10.0F, 2.0F, 16.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(12.0F, -14.0F, 0.0F, 0.0F, 0.0F, -WING_DIHEDRAL));

        Airliner.addOrReplaceChild("wing_right", CubeListBuilder.create()
                .texOffs(0, 0).mirror().addBox(-14.0F, -3.0F, -16.0F, 14.0F, 3.0F, 44.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(4, 0).mirror().addBox(-26.0F, -3.0F, -10.0F, 12.0F, 3.0F, 38.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(8, 0).mirror().addBox(-38.0F, -2.0F, -4.0F, 12.0F, 2.0F, 33.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(12, 0).mirror().addBox(-50.0F, -2.0F, 2.0F, 12.0F, 2.0F, 27.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(0, 8).mirror().addBox(-62.0F, -2.0F, 8.0F, 12.0F, 2.0F, 22.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(4, 8).mirror().addBox(-72.0F, -2.0F, 14.0F, 10.0F, 2.0F, 16.0F, new CubeDeformation(0.0F)).mirror(false),
                PartPose.offsetAndRotation(-12.0F, -14.0F, 0.0F, 0.0F, 0.0F, WING_DIHEDRAL));

        // Swept horizontal stabilisers, four steps each, with a little dihedral.
        Airliner.addOrReplaceChild("stab_left", CubeListBuilder.create()
                .texOffs(2, 6).addBox(0.0F, -2.0F, 56.0F, 10.0F, 2.0F, 18.0F, new CubeDeformation(0.0F))
                .texOffs(6, 2).addBox(10.0F, -2.0F, 61.0F, 8.0F, 2.0F, 14.0F, new CubeDeformation(0.0F))
                .texOffs(10, 6).addBox(18.0F, -2.0F, 66.0F, 8.0F, 2.0F, 10.0F, new CubeDeformation(0.0F))
                .texOffs(14, 2).addBox(26.0F, -2.0F, 70.0F, 6.0F, 2.0F, 7.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(6.0F, -25.0F, 0.0F, 0.0F, 0.0F, -STAB_DIHEDRAL));

        Airliner.addOrReplaceChild("stab_right", CubeListBuilder.create()
                .texOffs(2, 6).mirror().addBox(-10.0F, -2.0F, 56.0F, 10.0F, 2.0F, 18.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(6, 2).mirror().addBox(-18.0F, -2.0F, 61.0F, 8.0F, 2.0F, 14.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(10, 6).mirror().addBox(-26.0F, -2.0F, 66.0F, 8.0F, 2.0F, 10.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(14, 2).mirror().addBox(-32.0F, -2.0F, 70.0F, 6.0F, 2.0F, 7.0F, new CubeDeformation(0.0F)).mirror(false),
                PartPose.offsetAndRotation(-6.0F, -25.0F, 0.0F, 0.0F, 0.0F, STAB_DIHEDRAL));

        // Tall single fin: five swept steps and a dorsal fillet running forward along the crown.
        Airliner.addOrReplaceChild("Fin", CubeListBuilder.create()
                .texOffs(0, 4).addBox(-1.0F, -44.0F, 30.0F, 2.0F, 4.0F, 14.0F, new CubeDeformation(0.0F))
                .texOffs(4, 0).addBox(-2.0F, -47.0F, 44.0F, 4.0F, 8.0F, 32.0F, new CubeDeformation(0.0F))
                .texOffs(8, 4).addBox(-1.0F, -53.0F, 50.0F, 2.0F, 6.0F, 27.0F, new CubeDeformation(0.0F))
                .texOffs(12, 8).addBox(-1.0F, -59.0F, 55.0F, 2.0F, 6.0F, 23.0F, new CubeDeformation(0.0F))
                .texOffs(2, 12).addBox(-1.0F, -65.0F, 60.0F, 2.0F, 6.0F, 18.0F, new CubeDeformation(0.0F))
                .texOffs(6, 14).addBox(-1.0F, -71.0F, 65.0F, 2.0F, 6.0F, 14.0F, new CubeDeformation(0.0F))
                .texOffs(10, 2).addBox(-1.0F, -76.0F, 69.0F, 2.0F, 5.0F, 10.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        return LayerDefinition.create(meshdefinition, 16, 16);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
