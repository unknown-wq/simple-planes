package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.util.Mth;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Airship, metal layer: gondola windows and windscreen, deck rails, console and helm, landing wheel, the two
 * engine cars with their outriggers and braces, the suspension struts and cables, the nose cap with its
 * mooring cone, the tail cone, and the rudders and elevators. Uses
 * {@code textures/plane_upgrades/airship_metal.png}.
 *
 * <p>The window panes and the gaps between the rail balusters and helm spokes are alpha 0 (the default
 * {@code entityCutout} render type cuts them out), so the people in the gondola can see out and be seen.
 *
 * <p>Animation hook: {@link #applyControls(float, float)}. {@link PlaneRenderState} carries no control
 * inputs yet, so {@link #setupAnim} passes neutral controls. See AIRSHIP-MODEL.md. The numbers were produced
 * by a generator script that is not in the repository; this file can be edited by hand, but texOffs point
 * into the packed atlas, so a new cube needs a free region of airship_metal.png.
 */
public class AirshipMetalModel extends EntityModel<PlaneRenderState> {

    /** Largest control-surface deflection, in radians (25 degrees). */
    public static final float MAX_DEFLECTION = 0.4363F;

    private final ModelPart metal;
    private final ModelPart rudders;
    private final ModelPart elevators;

    public AirshipMetalModel(ModelPart root) {
        super(root);
        this.metal = root.getChild("Metal");
        this.rudders = this.metal.getChild("rudders");
        this.elevators = this.metal.getChild("elevators");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Metal = partdefinition.addOrReplaceChild("Metal", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        Metal.addOrReplaceChild("Windows", CubeListBuilder.create()
                .texOffs(118, 153).addBox(17.0F, -35.0F, -46.0F, 2.0F, 15.0F, 48.0F, new CubeDeformation(0.0F))
                .texOffs(118, 153).mirror().addBox(-19.0F, -35.0F, -46.0F, 2.0F, 15.0F, 48.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(0, 212).addBox(-17.0F, -35.0F, -46.0F, 34.0F, 15.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.ZERO);
        Metal.addOrReplaceChild("Controls", CubeListBuilder.create()
                .texOffs(190, 72).addBox(-10.0F, -21.0F, -42.0F, 20.0F, 4.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(190, 86).addBox(-3.0F, -25.0F, -37.0F, 6.0F, 6.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.ZERO);
        Metal.addOrReplaceChild("Rails", CubeListBuilder.create()
                .texOffs(0, 155).addBox(17.0F, -27.0F, 2.0F, 2.0F, 7.0F, 50.0F, new CubeDeformation(0.0F))
                .texOffs(0, 155).mirror().addBox(-19.0F, -27.0F, 2.0F, 2.0F, 7.0F, 50.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(156, 216).addBox(-17.0F, -27.0F, 50.0F, 34.0F, 7.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.ZERO);
        Metal.addOrReplaceChild("Trim", CubeListBuilder.create()
                .texOffs(0, 0).addBox(19.0F, -12.0F, -42.0F, 1.0F, 2.0F, 94.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).mirror().addBox(-20.0F, -12.0F, -42.0F, 1.0F, 2.0F, 94.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);
        Metal.addOrReplaceChild("Gear", CubeListBuilder.create()
                .texOffs(232, 122).addBox(-2.0F, -6.0F, -3.0F, 4.0F, 6.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.ZERO);
        PartDefinition Engines = Metal.addOrReplaceChild("Engines", CubeListBuilder.create()
                .texOffs(190, 0).addBox(37.0F, -27.0F, 14.0F, 10.0F, 10.0F, 20.0F, new CubeDeformation(0.0F))
                .texOffs(190, 0).mirror().addBox(-47.0F, -27.0F, 14.0F, 10.0F, 10.0F, 20.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(218, 49).addBox(38.0F, -26.0F, 11.0F, 8.0F, 8.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(218, 49).mirror().addBox(-46.0F, -26.0F, 11.0F, 8.0F, 8.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(232, 134).addBox(39.0F, -25.0F, 34.0F, 6.0F, 6.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(232, 134).mirror().addBox(-45.0F, -25.0F, 34.0F, 6.0F, 6.0F, 4.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(190, 80).addBox(19.0F, -17.0F, 22.0F, 25.0F, 2.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(190, 80).mirror().addBox(-44.0F, -17.0F, 22.0F, 25.0F, 2.0F, 4.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);

        Engines.addOrReplaceChild("brace_left", CubeListBuilder.create()
                .texOffs(248, 49).addBox(-1.0F, -36.0F, -1.0F, 2.0F, 36.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(40.0F, -27.0F, 24.0F, 0.0F, 0.0F, -0.591F));
        Engines.addOrReplaceChild("brace_right", CubeListBuilder.create()
                .texOffs(248, 49).addBox(-1.0F, -36.0F, -1.0F, 2.0F, 36.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-40.0F, -27.0F, 24.0F, 0.0F, 0.0F, 0.591F));
        PartDefinition Rigging = Metal.addOrReplaceChild("Rigging", CubeListBuilder.create()
                .texOffs(248, 104).addBox(16.0F, -52.0F, -45.0F, 2.0F, 14.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(248, 104).mirror().addBox(-18.0F, -52.0F, -45.0F, 2.0F, 14.0F, 2.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(248, 87).addBox(16.0F, -53.0F, -1.0F, 2.0F, 15.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(248, 87).mirror().addBox(-18.0F, -53.0F, -1.0F, 2.0F, 15.0F, 2.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(232, 86).addBox(17.0F, -61.0F, 49.0F, 2.0F, 34.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(232, 86).mirror().addBox(-19.0F, -61.0F, 49.0F, 2.0F, 34.0F, 2.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);

        Rigging.addOrReplaceChild("cable_fore_left", CubeListBuilder.create()
                .texOffs(118, 96).addBox(-0.5F, -0.5F, 0.0F, 1.0F, 1.0F, 56.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(8.0F, -40.0F, -46.0F, 2.8703F, 0.0F, 0.0F));
        Rigging.addOrReplaceChild("cable_fore_right", CubeListBuilder.create()
                .texOffs(118, 96).addBox(-0.5F, -0.5F, 0.0F, 1.0F, 1.0F, 56.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-8.0F, -40.0F, -46.0F, 2.8703F, 0.0F, 0.0F));
        Rigging.addOrReplaceChild("cable_aft_left", CubeListBuilder.create()
                .texOffs(0, 96).addBox(-0.5F, -0.5F, 0.0F, 1.0F, 1.0F, 58.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(12.0F, -27.0F, 52.0F, 0.8084F, 0.0F, 0.0F));
        Rigging.addOrReplaceChild("cable_aft_right", CubeListBuilder.create()
                .texOffs(0, 96).addBox(-0.5F, -0.5F, 0.0F, 1.0F, 1.0F, 58.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-12.0F, -27.0F, 52.0F, 0.8084F, 0.0F, 0.0F));
        // rudders: hinge line of both rudders; yRot = deflection
        Metal.addOrReplaceChild("rudders", CubeListBuilder.create()
                .texOffs(190, 30).addBox(-1.0F, -46.0F, 0.0F, 2.0F, 30.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(190, 30).addBox(-1.0F, 16.0F, 0.0F, 2.0F, 30.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, -88.0F, 104.0F));
        // elevators: hinge line of both elevators; xRot = deflection
        Metal.addOrReplaceChild("elevators", CubeListBuilder.create()
                .texOffs(72, 216).addBox(16.0F, -1.0F, 0.0F, 30.0F, 2.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(72, 216).mirror().addBox(-46.0F, -1.0F, 0.0F, 30.0F, 2.0F, 12.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offset(0.0F, -88.0F, 104.0F));
        Metal.addOrReplaceChild("Nose", CubeListBuilder.create()
                .texOffs(218, 30).addBox(-7.0F, -95.0F, -144.0F, 14.0F, 14.0F, 5.0F, new CubeDeformation(0.0F))
                .texOffs(218, 60).addBox(-4.0F, -92.0F, -147.0F, 8.0F, 8.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(218, 86).addBox(-1.0F, -89.0F, -151.0F, 2.0F, 2.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.ZERO);
        Metal.addOrReplaceChild("Tail", CubeListBuilder.create()
                .texOffs(232, 144).addBox(-3.0F, -91.0F, 125.0F, 6.0F, 6.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        return LayerDefinition.create(meshdefinition, 256, 256);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {
        super.setupAnim(state);
        // Hook for the airship entity: replace the zeros with control inputs copied into the render state.
        applyControls(0.0F, 0.0F);
    }

    /**
     * Deflects the control surfaces, both inputs in [-1, 1]. {@code rudder > 0} swings the rudders' trailing
     * edges to +X (the aircraft's left), which yaws the nose to the left; {@code elevator > 0} raises the
     * elevators' trailing edges, which pitches the nose up. Call after the pose reset done by
     * {@code super.setupAnim}.
     */
    public void applyControls(float rudder, float elevator) {
        this.rudders.yRot = Mth.clamp(rudder, -1.0F, 1.0F) * MAX_DEFLECTION;
        this.elevators.xRot = Mth.clamp(elevator, -1.0F, 1.0F) * MAX_DEFLECTION;
    }
}
