package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Fighter jet, metal layer: nose cone and pitot probe, bubble canopy, instrument panel, side air intakes,
 * landing gear and the wingtip missiles. Uses {@code textures/plane_upgrades/fighter_metal.png} (128x128).
 *
 * <p>The canopy is opaque tinted glass. The pilot's eye sits inside {@code canopy_main}, and the default
 * {@code entityCutout} render type culls back faces, so the canopy does not block the first-person view;
 * the parts of the lower tiers' top faces that lie under the next tier are cut out of the texture so this
 * still holds if the seat ends up slightly higher. See FIGHTER-MODEL.md.
 */
public class FighterMetalModel extends EntityModel<PlaneRenderState> {
    private final ModelPart Metal;

    public FighterMetalModel(ModelPart root) {
        super(root);
        this.Metal = root.getChild("Metal");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Metal = partdefinition.addOrReplaceChild("Metal", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        PartDefinition Nose = Metal.addOrReplaceChild("Nose", CubeListBuilder.create()
                .texOffs(84, 0).addBox(-4.0F, -19.0F, -54.0F, 8.0F, 6.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(48, 97).addBox(-2.0F, -18.0F, -60.0F, 4.0F, 4.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(112, 24).addBox(-0.5F, -16.5F, -66.0F, 1.0F, 1.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        PartDefinition Canopy = Metal.addOrReplaceChild("Canopy", CubeListBuilder.create()
                .texOffs(0, 97).addBox(-5.0F, -25.0F, -33.0F, 10.0F, 3.0F, 5.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).addBox(-7.0F, -27.0F, -28.0F, 14.0F, 5.0F, 28.0F, new CubeDeformation(0.0F))
                .texOffs(0, 69).addBox(-6.0F, -30.0F, -26.0F, 12.0F, 3.0F, 25.0F, new CubeDeformation(0.0F))
                .texOffs(74, 57).addBox(-4.0F, -33.0F, -21.0F, 8.0F, 3.0F, 18.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        PartDefinition Cockpit = Metal.addOrReplaceChild("Cockpit", CubeListBuilder.create()
                .texOffs(94, 99).addBox(-6.0F, -23.0F, -25.0F, 12.0F, 3.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        PartDefinition Intakes = Metal.addOrReplaceChild("Intakes", CubeListBuilder.create()
                .texOffs(0, 33).addBox(8.0F, -20.0F, -20.0F, 4.0F, 8.0F, 28.0F, new CubeDeformation(0.0F))
                .texOffs(0, 33).mirror().addBox(-12.0F, -20.0F, -20.0F, 4.0F, 8.0F, 28.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);

        PartDefinition Gear = Metal.addOrReplaceChild("Gear", CubeListBuilder.create()
                .texOffs(112, 38).addBox(-1.0F, -11.0F, -35.5F, 2.0F, 6.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(112, 14).addBox(-1.0F, -5.0F, -37.0F, 2.0F, 5.0F, 5.0F, new CubeDeformation(0.0F))
                .texOffs(116, 0).addBox(13.5F, -13.0F, 12.0F, 2.0F, 7.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(30, 97).addBox(13.0F, -6.0F, 10.0F, 3.0F, 6.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(116, 0).mirror().addBox(-15.5F, -13.0F, 12.0F, 2.0F, 7.0F, 2.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(30, 97).mirror().addBox(-16.0F, -6.0F, 10.0F, 3.0F, 6.0F, 6.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);

        PartDefinition Missiles = Metal.addOrReplaceChild("Missiles", CubeListBuilder.create()
                .texOffs(64, 33).addBox(43.0F, -15.0F, 10.0F, 2.0F, 2.0F, 22.0F, new CubeDeformation(0.0F))
                .texOffs(64, 33).mirror().addBox(-45.0F, -15.0F, 10.0F, 2.0F, 2.0F, 22.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);

        return LayerDefinition.create(meshdefinition, 128, 128);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
