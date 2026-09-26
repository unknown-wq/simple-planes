package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;

/**
 * Ground launch tubes for {@link MissileModel}, one layer per tier from {@link #createBodyLayer(int)}. A tube is
 * a square shaft sunk into the ground with its top flush with the surface, closed by a hatch: a single leaf on
 * the 1x1 tubes (tiers 1 and 2) and a two-leaf clamshell on the 2x2 tubes (tiers 3 and 4).
 *
 * <p>Built for a {@code BlockEntityRenderer}: like vanilla's {@code ChestModel}, it extends {@link Model}
 * directly, and its render state is the hatch opening in [0, 1] ({@code Model<Float>}).
 *
 * <p>Axes (see MISSILES-MODEL.md): pixels, Y down, root part {@code Tube} at the origin, which is the centre of
 * the footprint at ground level. The surface is y = 0 and the shaft goes down to y = +{@link #DEPTH_PX}. The
 * missile stands on the launch seat with its base at y = +{@link #SEAT_PX}.
 *
 * <p>Hook: {@link #setHatchOpen(float)}. Texture: {@code textures/entity/launch_tube.png} (256x512).
 */
public class LaunchTubeModel extends Model<Float> {

    /** How far the hatch leaves swing open, in radians (105 degrees: past upright, leaning outwards). */
    public static final float HATCH_OPEN_ANGLE = 1.8326F;

    /** Footprint side in blocks, by tier (index 0 unused). */
    public static final int[] FOOTPRINT_BLOCKS = {0, 1, 1, 2, 2};
    /** Inner width of the square bore, in pixels. */
    public static final int[] BORE_PX = {0, 10, 12, 16, 20};
    /** Depth from the surface to the bottom of the floor, in pixels: always (tier + 1) blocks. */
    public static final int[] DEPTH_PX = {0, 32, 48, 64, 80};
    /** Depth of the launch seat (the missile's base) below the surface, in pixels: 4 + 16 * tier. */
    public static final int[] SEAT_PX = {0, 20, 36, 52, 68};

    private final ModelPart tube;
    private final ModelPart hatch;
    private final ModelPart hatchLeft;
    private final ModelPart hatchRight;

    public LaunchTubeModel(ModelPart root) {
        super(root, RenderTypes::entityCutoutCull);
        this.tube = root.getChild("Tube");
        this.hatch = this.tube.hasChild("hatch") ? this.tube.getChild("hatch") : null;
        this.hatchLeft = this.tube.hasChild("hatch_left") ? this.tube.getChild("hatch_left") : null;
        this.hatchRight = this.tube.hasChild("hatch_right") ? this.tube.getChild("hatch_right") : null;
    }

    public static LayerDefinition createBodyLayer(int tier) {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Tube = partdefinition.addOrReplaceChild("Tube", CubeListBuilder.create(), PartPose.ZERO);

        switch (tier) {
            case 1 -> tier1(Tube);
            case 2 -> tier2(Tube);
            case 3 -> tier3(Tube);
            case 4 -> tier4(Tube);
            default -> throw new IllegalArgumentException("launch tube tier must be 1 to 4, was " + tier);
        }

        return LayerDefinition.create(meshdefinition, 256, 512);
    }

    /** Tier 1: 1x1, bore 10, 2 blocks deep, single hatch hinged on the -X edge. */
    private static void tier1(PartDefinition Tube) {
        Tube.addOrReplaceChild("Shaft", CubeListBuilder.create()
                .texOffs(80, 251).addBox(-5.0F, 0.0F, -8.0F, 10.0F, 32.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(0, 254).addBox(-5.0F, 0.0F, 5.0F, 10.0F, 32.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(54, 251).addBox(-8.0F, 0.0F, -5.0F, 3.0F, 32.0F, 10.0F, new CubeDeformation(0.0F))
                .texOffs(28, 251).addBox(5.0F, 0.0F, -5.0F, 3.0F, 32.0F, 10.0F, new CubeDeformation(0.0F))
                .texOffs(244, 288).addBox(-8.0F, 0.0F, -8.0F, 3.0F, 32.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(232, 288).addBox(5.0F, 0.0F, -8.0F, 3.0F, 32.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(172, 302).addBox(-8.0F, 0.0F, 5.0F, 3.0F, 32.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(160, 295).addBox(5.0F, 0.0F, 5.0F, 3.0F, 32.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(40, 293).addBox(-5.0F, 30.0F, -5.0F, 10.0F, 2.0F, 10.0F, new CubeDeformation(0.0F))
                .texOffs(96, 306).addBox(-3.0F, 27.0F, -3.0F, 6.0F, 3.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(120, 295).addBox(-5.0F, 20.0F, -5.0F, 10.0F, 1.0F, 10.0F, new CubeDeformation(0.0F))
                .texOffs(80, 172).addBox(4.0F, 2.0F, 4.0F, 1.0F, 18.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(80, 172).addBox(-5.0F, 2.0F, 4.0F, 1.0F, 18.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(80, 172).addBox(4.0F, 2.0F, -5.0F, 1.0F, 18.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(80, 172).addBox(-5.0F, 2.0F, -5.0F, 1.0F, 18.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        Tube.addOrReplaceChild("hatch", CubeListBuilder.create()
                .texOffs(80, 294).addBox(0.0F, 0.0F, -5.0F, 10.0F, 2.0F, 10.0F, new CubeDeformation(0.0F)), PartPose.offset(-5.0F, 0.0F, 0.0F));
    }

    /** Tier 2: 1x1, bore 12, 3 blocks deep, single hatch hinged on the -X edge. */
    private static void tier2(PartDefinition Tube) {
        Tube.addOrReplaceChild("Shaft", CubeListBuilder.create()
                .texOffs(224, 220).addBox(-6.0F, 0.0F, -8.0F, 12.0F, 48.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(108, 244).addBox(-6.0F, 0.0F, 6.0F, 12.0F, 48.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(0, 194).addBox(-8.0F, 0.0F, -6.0F, 2.0F, 48.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(224, 160).addBox(6.0F, 0.0F, -6.0F, 2.0F, 48.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(192, 302).addBox(-8.0F, 0.0F, -8.0F, 2.0F, 48.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(184, 302).addBox(6.0F, 0.0F, -8.0F, 2.0F, 48.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(208, 302).addBox(-8.0F, 0.0F, 6.0F, 2.0F, 48.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(200, 302).addBox(6.0F, 0.0F, 6.0F, 2.0F, 48.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(136, 281).addBox(-6.0F, 46.0F, -6.0F, 12.0F, 2.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(72, 306).addBox(-3.0F, 42.0F, -3.0F, 6.0F, 4.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(28, 216).addBox(-6.0F, 36.0F, -6.0F, 12.0F, 1.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(252, 160).addBox(5.0F, 2.0F, 5.0F, 1.0F, 34.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(252, 160).addBox(-6.0F, 2.0F, 5.0F, 1.0F, 34.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(252, 160).addBox(5.0F, 2.0F, -6.0F, 1.0F, 34.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(252, 160).addBox(-6.0F, 2.0F, -6.0F, 1.0F, 34.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        Tube.addOrReplaceChild("hatch", CubeListBuilder.create()
                .texOffs(184, 288).addBox(0.0F, 0.0F, -6.0F, 12.0F, 2.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offset(-6.0F, 0.0F, 0.0F));
    }

    /** Tier 3: 2x2, bore 16, 4 blocks deep, clamshell hatch hinged on the -X and +X edges. */
    private static void tier3(PartDefinition Tube) {
        Tube.addOrReplaceChild("Shaft", CubeListBuilder.create()
                .texOffs(104, 86).addBox(-8.0F, 0.0F, -16.0F, 16.0F, 64.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(152, 86).addBox(-8.0F, 0.0F, 8.0F, 16.0F, 64.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(208, 80).addBox(-16.0F, 0.0F, -8.0F, 8.0F, 64.0F, 16.0F, new CubeDeformation(0.0F))
                .texOffs(208, 0).addBox(8.0F, 0.0F, -8.0F, 8.0F, 64.0F, 16.0F, new CubeDeformation(0.0F))
                .texOffs(32, 100).addBox(-16.0F, 0.0F, -16.0F, 8.0F, 64.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(0, 100).addBox(8.0F, 0.0F, -16.0F, 8.0F, 64.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(96, 158).addBox(-16.0F, 0.0F, 8.0F, 8.0F, 64.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(64, 100).addBox(8.0F, 0.0F, 8.0F, 8.0F, 64.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(136, 246).addBox(-8.0F, 62.0F, -8.0F, 16.0F, 2.0F, 16.0F, new CubeDeformation(0.0F))
                .texOffs(40, 305).addBox(-4.0F, 58.0F, -4.0F, 8.0F, 4.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(136, 264).addBox(-8.0F, 52.0F, -8.0F, 16.0F, 1.0F, 16.0F, new CubeDeformation(0.0F))
                .texOffs(96, 100).addBox(7.0F, 2.0F, 7.0F, 1.0F, 50.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(96, 100).addBox(-8.0F, 2.0F, 7.0F, 1.0F, 50.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(96, 100).addBox(7.0F, 2.0F, -8.0F, 1.0F, 50.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(96, 100).addBox(-8.0F, 2.0F, -8.0F, 1.0F, 50.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        Tube.addOrReplaceChild("hatch_left", CubeListBuilder.create()
                .texOffs(200, 270).addBox(0.0F, 0.0F, -8.0F, 8.0F, 2.0F, 16.0F, new CubeDeformation(0.0F)), PartPose.offset(-8.0F, 0.0F, 0.0F));
        Tube.addOrReplaceChild("hatch_right", CubeListBuilder.create()
                .texOffs(200, 270).mirror().addBox(-8.0F, 0.0F, -8.0F, 8.0F, 2.0F, 16.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offset(8.0F, 0.0F, 0.0F));
    }

    /** Tier 4: 2x2, bore 20, 5 blocks deep, clamshell hatch hinged on the -X and +X edges. */
    private static void tier4(PartDefinition Tube) {
        Tube.addOrReplaceChild("Shaft", CubeListBuilder.create()
                .texOffs(104, 0).addBox(-10.0F, 0.0F, -16.0F, 20.0F, 80.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(156, 0).addBox(-10.0F, 0.0F, 10.0F, 20.0F, 80.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(52, 0).addBox(-16.0F, 0.0F, -10.0F, 6.0F, 80.0F, 20.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).addBox(10.0F, 0.0F, -10.0F, 6.0F, 80.0F, 20.0F, new CubeDeformation(0.0F))
                .texOffs(152, 158).addBox(-16.0F, 0.0F, -16.0F, 6.0F, 80.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(128, 158).addBox(10.0F, 0.0F, -16.0F, 6.0F, 80.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(200, 160).addBox(-16.0F, 0.0F, 10.0F, 6.0F, 80.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(176, 158).addBox(10.0F, 0.0F, 10.0F, 6.0F, 80.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(0, 172).addBox(-10.0F, 78.0F, -10.0F, 20.0F, 2.0F, 20.0F, new CubeDeformation(0.0F))
                .texOffs(0, 293).addBox(-5.0F, 73.0F, -5.0F, 10.0F, 5.0F, 10.0F, new CubeDeformation(0.0F))
                .texOffs(28, 230).addBox(-10.0F, 68.0F, -10.0F, 20.0F, 1.0F, 20.0F, new CubeDeformation(0.0F))
                .texOffs(200, 86).addBox(9.0F, 2.0F, 9.0F, 1.0F, 66.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(200, 86).addBox(-10.0F, 2.0F, 9.0F, 1.0F, 66.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(200, 86).addBox(9.0F, 2.0F, -10.0F, 1.0F, 66.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(200, 86).addBox(-10.0F, 2.0F, -10.0F, 1.0F, 66.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        Tube.addOrReplaceChild("hatch_left", CubeListBuilder.create()
                .texOffs(28, 194).addBox(0.0F, 0.0F, -10.0F, 10.0F, 2.0F, 20.0F, new CubeDeformation(0.0F)), PartPose.offset(-10.0F, 0.0F, 0.0F));
        Tube.addOrReplaceChild("hatch_right", CubeListBuilder.create()
                .texOffs(28, 194).mirror().addBox(-10.0F, 0.0F, -10.0F, 10.0F, 2.0F, 20.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offset(10.0F, 0.0F, 0.0F));
    }

    @Override
    public void setupAnim(Float hatchOpen) {
        super.setupAnim(hatchOpen);
        setHatchOpen(hatchOpen);
    }

    /**
     * Opens the hatch: 0 = closed, flush with the surface; 1 = swung up by {@link #HATCH_OPEN_ANGLE} about its
     * hinge at the rim of the bore. Call after the pose reset done by {@code super.setupAnim}.
     */
    public void setHatchOpen(float open) {
        float angle = Mth.clamp(open, 0.0F, 1.0F) * HATCH_OPEN_ANGLE;
        if (this.hatch != null) this.hatch.zRot = -angle;
        if (this.hatchLeft != null) this.hatchLeft.zRot = -angle;
        if (this.hatchRight != null) this.hatchRight.zRot = angle;
    }

    /** Footprint side in blocks (1 for tiers 1 and 2, 2 for tiers 3 and 4). */
    public static int footprintBlocks(int tier) {
        return FOOTPRINT_BLOCKS[tier];
    }
}
