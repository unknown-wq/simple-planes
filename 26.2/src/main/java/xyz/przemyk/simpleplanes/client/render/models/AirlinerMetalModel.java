package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Mini airliner, metal layer: cockpit windows and instrument panel, the passenger window belts and
 * door outlines, the two underwing turbofan nacelles with their pylons, the winglets, the tail emblem, the APU
 * cone and the tricycle landing gear. Uses {@code textures/plane_upgrades/airliner_metal.png} (256x256).
 *
 * <p>Windows, doors and the emblem are flush plates just outside the wooden skin ({@code CubeDeformation}
 * 0.05); every face of them that points into the cabin is transparent in the texture, so the riders, whose
 * eyes are inside the fuselage, see out through the walls. The fan discs are in {@link AirlinerFanModel}.
 */
public class AirlinerMetalModel extends EntityModel<PlaneRenderState> {
    private final ModelPart Metal;

    public AirlinerMetalModel(ModelPart root) {
        super(root);
        this.Metal = root.getChild("Metal");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Metal = partdefinition.addOrReplaceChild("Metal", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Two-tier windscreen; the pilot's eye is inside glass_lo.
        Metal.addOrReplaceChild("Cockpit", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-13.0F, -33.0F, -81.0F, 26.0F, 3.0F, 11.0F, new CubeDeformation(0.05F))
                .texOffs(102, 0).addBox(-13.0F, -36.0F, -80.0F, 26.0F, 3.0F, 10.0F, new CubeDeformation(0.05F))
                .texOffs(200, 0).addBox(-10.0F, -29.0F, -80.0F, 20.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        Metal.addOrReplaceChild("Cabin", CubeListBuilder.create()
                .texOffs(0, 0).addBox(12.0F, -35.0F, -60.0F, 1.0F, 6.0F, 99.0F, new CubeDeformation(0.05F))
                .texOffs(0, 0).mirror().addBox(-13.0F, -35.0F, -60.0F, 1.0F, 6.0F, 99.0F, new CubeDeformation(0.05F)).mirror(false)
                .texOffs(131, 14).addBox(12.0F, -37.0F, -67.0F, 1.0F, 20.0F, 7.0F, new CubeDeformation(0.04F))
                .texOffs(131, 14).mirror().addBox(-13.0F, -37.0F, -67.0F, 1.0F, 20.0F, 7.0F, new CubeDeformation(0.04F)).mirror(false)
                .texOffs(131, 14).addBox(12.0F, -37.0F, 39.0F, 1.0F, 20.0F, 7.0F, new CubeDeformation(0.04F))
                .texOffs(131, 14).mirror().addBox(-13.0F, -37.0F, 39.0F, 1.0F, 20.0F, 7.0F, new CubeDeformation(0.04F)).mirror(false), PartPose.ZERO);

        // Turbofan nacelles: an open intake ring, the cowl (its front face is the fan case), exhaust and plug.
        Metal.addOrReplaceChild("engine_left", CubeListBuilder.create()
                .texOffs(200, 5).addBox(25.0F, -17.0F, -26.0F, 14.0F, 2.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(200, 5).addBox(25.0F, -5.0F, -26.0F, 14.0F, 2.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(246, 10).addBox(25.0F, -15.0F, -26.0F, 2.0F, 10.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(246, 10).addBox(37.0F, -15.0F, -26.0F, 2.0F, 10.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(160, 0).addBox(26.0F, -16.0F, -23.0F, 12.0F, 12.0F, 15.0F, new CubeDeformation(0.0F))
                .texOffs(215, 11).addBox(27.0F, -15.0F, -8.0F, 10.0F, 10.0F, 5.0F, new CubeDeformation(0.0F))
                .texOffs(242, 2).addBox(30.0F, -12.0F, -3.0F, 4.0F, 4.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(57, 0).addBox(31.0F, -19.0F, -21.0F, 2.0F, 3.0F, 18.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        Metal.addOrReplaceChild("engine_right", CubeListBuilder.create()
                .texOffs(200, 5).mirror().addBox(-39.0F, -17.0F, -26.0F, 14.0F, 2.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(200, 5).mirror().addBox(-39.0F, -5.0F, -26.0F, 14.0F, 2.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(246, 10).mirror().addBox(-27.0F, -15.0F, -26.0F, 2.0F, 10.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(246, 10).mirror().addBox(-39.0F, -15.0F, -26.0F, 2.0F, 10.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(160, 0).mirror().addBox(-38.0F, -16.0F, -23.0F, 12.0F, 12.0F, 15.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(215, 11).mirror().addBox(-37.0F, -15.0F, -8.0F, 10.0F, 10.0F, 5.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(242, 2).mirror().addBox(-34.0F, -12.0F, -3.0F, 4.0F, 4.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(57, 0).mirror().addBox(-33.0F, -19.0F, -21.0F, 2.0F, 3.0F, 18.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);

        // Winglets ride on the wing tips, so they share the wings' pivots and dihedral (see AirlinerModel).
        Metal.addOrReplaceChild("winglet_left", CubeListBuilder.create()
                .texOffs(102, 14).addBox(70.0F, -7.0F, 18.0F, 2.0F, 5.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(30, 15).addBox(70.0F, -12.0F, 22.0F, 2.0F, 5.0F, 8.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(12.0F, -14.0F, 0.0F, 0.0F, 0.0F, -AirlinerModel.WING_DIHEDRAL));

        Metal.addOrReplaceChild("winglet_right", CubeListBuilder.create()
                .texOffs(102, 14).mirror().addBox(-72.0F, -7.0F, 18.0F, 2.0F, 5.0F, 12.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(30, 15).mirror().addBox(-72.0F, -12.0F, 22.0F, 2.0F, 5.0F, 8.0F, new CubeDeformation(0.0F)).mirror(false),
                PartPose.offsetAndRotation(-12.0F, -14.0F, 0.0F, 0.0F, 0.0F, AirlinerModel.WING_DIHEDRAL));

        Metal.addOrReplaceChild("Tail", CubeListBuilder.create()
                .texOffs(0, 15).addBox(0.0F, -64.0F, 62.0F, 1.0F, 14.0F, 12.0F, new CubeDeformation(0.05F))
                .texOffs(0, 15).mirror().addBox(-1.0F, -64.0F, 62.0F, 1.0F, 14.0F, 12.0F, new CubeDeformation(0.05F)).mirror(false)
                .texOffs(51, 22).addBox(-3.0F, -37.0F, 78.0F, 6.0F, 6.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        Metal.addOrReplaceChild("Gear", CubeListBuilder.create()
                .texOffs(102, 14).addBox(-1.0F, -13.0F, -75.0F, 2.0F, 9.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(72, 22).addBox(-3.0F, -4.0F, -76.0F, 2.0F, 4.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(72, 22).mirror().addBox(1.0F, -4.0F, -76.0F, 2.0F, 4.0F, 4.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(148, 14).addBox(15.0F, -15.0F, 15.0F, 2.0F, 10.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(80, 0).addBox(12.0F, -6.0F, 13.0F, 3.0F, 6.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(80, 0).addBox(17.0F, -6.0F, 13.0F, 3.0F, 6.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(148, 14).mirror().addBox(-17.0F, -15.0F, 15.0F, 2.0F, 10.0F, 2.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(80, 0).mirror().addBox(-15.0F, -6.0F, 13.0F, 3.0F, 6.0F, 6.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(80, 0).mirror().addBox(-20.0F, -6.0F, 13.0F, 3.0F, 6.0F, 6.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);

        return LayerDefinition.create(meshdefinition, 256, 256);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
