package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Fighter jet, metal layer: nose cone and pitot probe, canopy frame (sill rails and windscreen bow), cockpit
 * (instrument panel, coaming, seat and headrest), side air intakes, landing gear and the wingtip missiles. Uses
 * {@code textures/plane_upgrades/fighter_metal.png} (128x128).
 *
 * <p>The canopy glass is its own translucent layer, {@link FighterGlassModel}. This layer stays on
 * {@link RenderTypes#entityCutoutCull}; the default {@code entityCutout} does not cull. The frame cubes are grown by
 * {@link #FRAME_GROW} so none of their faces lies in the plane of a glass face. See FIGHTER-MODEL.md.
 */
public class FighterMetalModel extends EntityModel<PlaneRenderState> {
    /** Frame cubes stand this much (px) proud of the glass. */
    public static final float FRAME_GROW = 0.05F;

    private final ModelPart Metal;

    public FighterMetalModel(ModelPart root) {
        super(root, RenderTypes::entityCutoutCull);
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

        CubeDeformation frame = new CubeDeformation(FRAME_GROW);
        // Sill rails along canopy_main, and the windscreen bow at z -23..-22 following the stepped profile.
        PartDefinition CanopyFrame = Metal.addOrReplaceChild("CanopyFrame", CubeListBuilder.create()
                .texOffs(0, 0).addBox(6.0F, -23.0F, -28.0F, 1.0F, 1.0F, 28.0F, frame)
                .texOffs(0, 0).mirror().addBox(-7.0F, -23.0F, -28.0F, 1.0F, 1.0F, 28.0F, frame).mirror(false)
                .texOffs(42, 69).addBox(6.0F, -27.0F, -23.0F, 1.0F, 4.0F, 1.0F, frame)
                .texOffs(42, 69).mirror().addBox(-7.0F, -27.0F, -23.0F, 1.0F, 4.0F, 1.0F, frame).mirror(false)
                .texOffs(46, 69).addBox(5.0F, -30.0F, -23.0F, 1.0F, 3.0F, 1.0F, frame)
                .texOffs(46, 69).mirror().addBox(-6.0F, -30.0F, -23.0F, 1.0F, 3.0F, 1.0F, frame).mirror(false)
                .texOffs(50, 69).addBox(-5.0F, -30.0F, -23.0F, 10.0F, 1.0F, 1.0F, frame), PartPose.ZERO);

        // Instrument panel with a coaming over it, seat pan, seat back and headrest. The rider sits with the feet
        // point at (0, -1, -6): hips at y -12.25, back at z -4.1, back of the head at z -2.25.
        PartDefinition Cockpit = Metal.addOrReplaceChild("Cockpit", CubeListBuilder.create()
                .texOffs(94, 99).addBox(-6.0F, -23.0F, -25.0F, 12.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(0, 69).addBox(-5.0F, -24.0F, -25.0F, 10.0F, 1.0F, 4.0F, CubeDeformation.NONE)
                .texOffs(58, 14).addBox(-4.0F, -12.0F, -9.0F, 8.0F, 1.0F, 5.0F, CubeDeformation.NONE)
                .texOffs(58, 0).addBox(-4.0F, -24.0F, -4.0F, 8.0F, 12.0F, 2.0F, CubeDeformation.NONE)
                .texOffs(28, 69).addBox(-3.0F, -29.0F, -2.25F, 6.0F, 5.0F, 1.0F, CubeDeformation.NONE), PartPose.ZERO);

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
