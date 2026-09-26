package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import xyz.przemyk.simpleplanes.client.render.MissileRenderState;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-use missiles, tiers 1 to 4, standing exactly 1, 2, 3 and 4 blocks tall. One class for all tiers:
 * {@link #createBodyLayer(int)} builds the geometry of a tier, and every tier has the same part names and
 * the same two hooks, so one renderer drives all four baked layers.
 *
 * <ul>
 *   <li>Tier 1: slim interceptor, 4 px body, one set of small tail fins, one red band.</li>
 *   <li>Tier 2: 6 px body, nose canards and swept tail fins, two red bands.</li>
 *   <li>Tier 3: heavy 8 px body, long mid-body strakes and big three-step tail fins, three red bands.</li>
 *   <li>Tier 4: two-stage: 14 px booster with large fins and an interstage (the {@code Booster} part), 10 px
 *       sustainer with its own fins, four red bands.</li>
 * </ul>
 *
 * <p>Axes (see MISSILES-MODEL.md): pixels, Y down, root part {@code Missile} at {@code (0, 24, 0)}. In the
 * root's frame the base of the missile (nozzle exit plane) is at y = 0, the axis is x = z = 0 and the nose tip
 * is at y = -16 * tier. The nose therefore points to model -Y, which is world up for an upright missile.
 *
 * <p>Hooks: {@link #setFinsDeployed(float)} and {@link #setThrust(float)}. Texture:
 * {@code textures/entity/missile.png} (128x128) for every tier.
 */
public class MissileModel extends EntityModel<MissileRenderState> {

    public static final int MIN_TIER = 1;
    public static final int MAX_TIER = 4;
    /** Fin rotation about its root hinge when folded, in radians. 0 is deployed. */
    public static final float FIN_FOLD_ANGLE = Mth.HALF_PI;
    /** Names of the fin groups; tier 1 has one, the others two ({@code booster_fins} hangs under {@code Booster}). */
    public static final String[] FIN_SETS = {"canards", "strakes", "sustainer_fins", "tail_fins", "booster_fins"};

    /** Length from the nozzle exit to the nose tip, in pixels, by tier (index 0 unused). */
    public static final int[] LENGTH_PX = {0, 16, 32, 48, 64};
    /** Width of the widest body section, in pixels (tier 4: the booster). */
    public static final int[] BODY_WIDTH_PX = {0, 4, 6, 8, 14};
    /** Width across the folded fins, as stowed in the tube, in pixels. */
    public static final int[] FOLDED_WIDTH_PX = {0, 6, 8, 10, 16};
    /** Fin tip to fin tip with the fins deployed, in pixels. */
    public static final int[] DEPLOYED_SPAN_PX = {0, 8, 12, 16, 26};

    private final ModelPart missile;
    private final ModelPart flame;
    /** Tier 4 only (null otherwise): the first stage, with its nozzle, interstage and booster fins. */
    private final ModelPart booster;
    private final List<ModelPart> finHinges = new ArrayList<>();

    public MissileModel(ModelPart root) {
        super(root, RenderTypes::entityCutoutCull);
        this.missile = root.getChild("Missile");
        this.flame = this.missile.getChild("Flame");
        this.booster = this.missile.hasChild("Booster") ? this.missile.getChild("Booster") : null;
        for (ModelPart parent : this.booster == null ? List.of(this.missile) : List.of(this.missile, this.booster)) {
            for (String set : FIN_SETS) {
                if (!parent.hasChild(set)) continue;
                ModelPart group = parent.getChild(set);
                for (int k = 0; k < 4; k++) {
                    this.finHinges.add(group.getChild("fin_" + k).getChild("hinge"));
                }
            }
        }
    }

    /** The tier-4 booster stage, or null for tiers 1 to 3. Hide it (visible = false) after staging. */
    public ModelPart booster() {
        return this.booster;
    }

    /** The exhaust flame part. For a glowing flame use a second, flame-only model instance (see MISSILES-MODEL.md). */
    public ModelPart flame() {
        return this.flame;
    }

    public static LayerDefinition createBodyLayer(int tier) {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Missile = partdefinition.addOrReplaceChild("Missile", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        switch (tier) {
            case 1 -> tier1(Missile);
            case 2 -> tier2(Missile);
            case 3 -> tier3(Missile);
            case 4 -> tier4(Missile);
            default -> throw new IllegalArgumentException("missile tier must be 1 to 4, was " + tier);
        }

        return LayerDefinition.create(meshdefinition, 128, 128);
    }

    /** Tier 1, 16 px: slim interceptor. */
    private static void tier1(PartDefinition Missile) {
        Missile.addOrReplaceChild("Body", CubeListBuilder.create()
                .texOffs(22, 102).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 1.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(60, 79).addBox(-2.0F, -12.0F, -2.0F, 4.0F, 11.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(112, 99).addBox(-1.5F, -14.0F, -1.5F, 3.0F, 2.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(100, 100).addBox(-1.0F, -15.0F, -1.0F, 2.0F, 1.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(48, 34).addBox(-0.5F, -16.0F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        addFinSet(Missile, "tail_fins", 2.0F, CubeListBuilder.create()
                .texOffs(88, 37).addBox(-0.5F, -6.0F, -0.5F, 1.0F, 5.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(38, 102).addBox(0.5F, -4.0F, -0.5F, 1.0F, 3.0F, 1.0F, new CubeDeformation(0.0F)));

        Missile.addOrReplaceChild("Flame", CubeListBuilder.create()
                .texOffs(84, 67).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(58, 94).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 7.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(124, 99).addBox(-0.5F, 0.0F, -0.5F, 1.0F, 11.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.ZERO);
    }

    /** Tier 2, 32 px: nose canards and swept tail fins. */
    private static void tier2(PartDefinition Missile) {
        Missile.addOrReplaceChild("Body", CubeListBuilder.create()
                .texOffs(112, 93).addBox(-2.0F, -2.0F, -2.0F, 4.0F, 2.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(48, 37).addBox(-3.0F, -25.0F, -3.0F, 6.0F, 23.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(76, 93).addBox(-2.5F, -27.0F, -2.5F, 5.0F, 2.0F, 5.0F, new CubeDeformation(0.0F))
                .texOffs(72, 37).addBox(-2.0F, -29.0F, -2.0F, 4.0F, 2.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(72, 100).addBox(-1.0F, -31.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(116, 81).addBox(-0.5F, -32.0F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        addFinSet(Missile, "canards", 3.0F, CubeListBuilder.create()
                .texOffs(76, 79).addBox(-0.5F, -24.0F, -0.5F, 2.0F, 3.0F, 1.0F, new CubeDeformation(0.0F)));

        addFinSet(Missile, "tail_fins", 3.0F, CubeListBuilder.create()
                .texOffs(0, 100).addBox(-0.5F, -10.0F, -0.5F, 2.0F, 8.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(30, 102).addBox(1.5F, -7.0F, -0.5F, 1.0F, 5.0F, 1.0F, new CubeDeformation(0.0F)));

        Missile.addOrReplaceChild("Flame", CubeListBuilder.create()
                .texOffs(24, 93).addBox(-2.5F, 0.0F, -2.5F, 5.0F, 4.0F, 5.0F, new CubeDeformation(0.0F))
                .texOffs(116, 68).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 10.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(44, 93).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 16.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.ZERO);
    }

    /** Tier 3, 48 px: heavy body, mid-body strakes and big three-step tail fins. */
    private static void tier3(PartDefinition Missile) {
        Missile.addOrReplaceChild("Body", CubeListBuilder.create()
                .texOffs(100, 85).addBox(-3.0F, -2.0F, -3.0F, 6.0F, 2.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(96, 0).addBox(-4.0F, -38.0F, -4.0F, 8.0F, 36.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(32, 84).addBox(-3.5F, -40.0F, -3.5F, 7.0F, 2.0F, 7.0F, new CubeDeformation(0.0F))
                .texOffs(76, 85).addBox(-3.0F, -42.0F, -3.0F, 6.0F, 2.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(96, 93).addBox(-2.0F, -45.0F, -2.0F, 4.0F, 3.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(80, 100).addBox(-1.0F, -47.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(58, 103).addBox(-0.5F, -48.0F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        addFinSet(Missile, "strakes", 4.0F, CubeListBuilder.create()
                .texOffs(16, 94).addBox(-0.5F, -27.0F, -0.5F, 2.0F, 11.0F, 1.0F, new CubeDeformation(0.0F)));

        addFinSet(Missile, "tail_fins", 4.0F, CubeListBuilder.create()
                .texOffs(52, 93).addBox(-0.5F, -14.0F, -0.5F, 2.0F, 12.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(124, 81).addBox(1.5F, -11.0F, -0.5F, 1.0F, 9.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(24, 85).addBox(2.5F, -8.0F, -0.5F, 1.0F, 6.0F, 1.0F, new CubeDeformation(0.0F)));

        Missile.addOrReplaceChild("Flame", CubeListBuilder.create()
                .texOffs(56, 67).addBox(-3.5F, 0.0F, -3.5F, 7.0F, 5.0F, 7.0F, new CubeDeformation(0.0F))
                .texOffs(36, 66).addBox(-2.5F, 0.0F, -2.5F, 5.0F, 13.0F, 5.0F, new CubeDeformation(0.0F))
                .texOffs(116, 44).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 21.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.ZERO);
    }

    /** Tier 4, 64 px: two-stage, booster (y 0 to -22) and sustainer (y -25 to -64). */
    private static void tier4(PartDefinition Missile) {
        // the booster (first stage) is its own part, so a staging effect can hide it; the sustainer is "Body"
        PartDefinition Booster = Missile.addOrReplaceChild("Booster", CubeListBuilder.create()
                .texOffs(84, 75).addBox(-4.0F, -2.0F, -4.0F, 8.0F, 2.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).addBox(-7.0F, -22.0F, -7.0F, 14.0F, 20.0F, 14.0F, new CubeDeformation(0.0F))
                .texOffs(0, 34).addBox(-6.0F, -25.0F, -6.0F, 12.0F, 3.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        Missile.addOrReplaceChild("Body", CubeListBuilder.create()
                .texOffs(56, 0).addBox(-5.0F, -52.0F, -5.0F, 10.0F, 27.0F, 10.0F, new CubeDeformation(0.0F))
                .texOffs(0, 64).addBox(-4.5F, -54.0F, -4.5F, 9.0F, 2.0F, 9.0F, new CubeDeformation(0.0F))
                .texOffs(0, 75).addBox(-4.0F, -56.0F, -4.0F, 8.0F, 2.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(0, 85).addBox(-3.0F, -59.0F, -3.0F, 6.0F, 3.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(0, 94).addBox(-2.0F, -61.0F, -2.0F, 4.0F, 2.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(88, 100).addBox(-1.0F, -63.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(100, 103).addBox(-0.5F, -64.0F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        addFinSet(Missile, "sustainer_fins", 5.0F, CubeListBuilder.create()
                .texOffs(6, 100).addBox(-0.5F, -33.0F, -0.5F, 2.0F, 8.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(34, 102).addBox(1.5F, -30.0F, -0.5F, 1.0F, 5.0F, 1.0F, new CubeDeformation(0.0F)));

        addFinSet(Booster, "booster_fins", 7.0F, CubeListBuilder.create()
                .texOffs(36, 49).addBox(-0.5F, -16.0F, -0.5F, 3.0F, 14.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(66, 94).addBox(2.5F, -12.0F, -0.5F, 2.0F, 10.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(96, 100).addBox(4.5F, -8.0F, -0.5F, 1.0F, 6.0F, 1.0F, new CubeDeformation(0.0F)));

        Missile.addOrReplaceChild("Flame", CubeListBuilder.create()
                .texOffs(0, 49).addBox(-4.5F, 0.0F, -4.5F, 9.0F, 6.0F, 9.0F, new CubeDeformation(0.0F))
                .texOffs(72, 44).addBox(-3.5F, 0.0F, -3.5F, 7.0F, 16.0F, 7.0F, new CubeDeformation(0.0F))
                .texOffs(100, 44).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 27.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.ZERO);
    }

    /**
     * Four identical fins around the body, one per face (+X, -Z, -X, +Z). Each fin is a {@code fin_k} station
     * rotated k * 90 degrees about the missile axis, holding a {@code hinge} at the body surface
     * ({@code halfWidth + 0.5} px out, the middle of the fin's 1 px root). The fin cubes are built pointing out
     * along the station's +X; the hinge's yRot folds them flat against the body face (pinwheel fashion) and
     * {@link #setFinsDeployed} swings them out to 0.
     */
    private static void addFinSet(PartDefinition Missile, String name, float halfWidth, CubeListBuilder fin) {
        PartDefinition set = Missile.addOrReplaceChild(name, CubeListBuilder.create(), PartPose.ZERO);
        for (int k = 0; k < 4; k++) {
            PartDefinition station = set.addOrReplaceChild("fin_" + k, CubeListBuilder.create(),
                    PartPose.rotation(0.0F, k * Mth.HALF_PI, 0.0F));
            station.addOrReplaceChild("hinge", fin,
                    PartPose.offsetAndRotation(halfWidth + 0.5F, 0.0F, 0.0F, 0.0F, FIN_FOLD_ANGLE, 0.0F));
        }
    }

    @Override
    public void setupAnim(MissileRenderState state) {
        super.setupAnim(state);
        setFinsDeployed(state.finsDeployed);
        setThrust(state.thrust);
    }

    /**
     * Folds or deploys every fin set: 0 = folded flat against the body (the stowed state inside the tube),
     * 1 = fully deployed. Call after the pose reset done by {@code super.setupAnim}.
     */
    public void setFinsDeployed(float deployed) {
        float fold = (1.0F - Mth.clamp(deployed, 0.0F, 1.0F)) * FIN_FOLD_ANGLE;
        for (ModelPart hinge : this.finHinges) {
            hinge.yRot = fold;
        }
    }

    /**
     * Motor flame for a thrust in [0, 1], in the spirit of {@code FighterExhaustModel.applyThrottle}: hidden at
     * or below 5%, then growing from the nozzle exit to 1.6x its modelled length and 1.1x its girth at full
     * thrust. Call after the pose reset done by {@code super.setupAnim}.
     */
    public void setThrust(float thrust) {
        float t = Mth.clamp(thrust, 0.0F, 1.0F);
        this.flame.visible = t > 0.05F;
        this.flame.yScale = 0.4F + 1.2F * t;
        float girth = 0.8F + 0.3F * t;
        this.flame.xScale = girth;
        this.flame.zScale = girth;
    }

    /** Missile length in blocks (nozzle exit to nose tip). */
    public static float lengthBlocks(int tier) {
        return LENGTH_PX[tier] / 16.0F;
    }
}
