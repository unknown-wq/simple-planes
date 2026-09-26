package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.util.Mth;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Fighter jet exhaust: a four-petal variable nozzle, the turbine face inside it and the afterburner flame.
 * Takes the place of the propeller layer in {@code PlaneRenderer} and uses the same texture as
 * {@link FighterMetalModel} ({@code textures/plane_upgrades/fighter_metal.png}).
 *
 * <p>Animation hook: {@link #applyThrottle(float)} opens the petals and grows the flame for a throttle in
 * [0, 1]; {@link #setupAnim} passes {@code PlaneRenderState.throttle}.
 */
public class FighterExhaustModel extends EntityModel<PlaneRenderState> {

    /** Nozzle closed, no flame. */
    public static final float IDLE_THROTTLE = 0.0F;
    /** Petal deflection at full throttle, in radians. */
    public static final float PETAL_OPEN_ANGLE = 0.2618F;

    private final ModelPart Nozzle;
    private final ModelPart petal_top;
    private final ModelPart petal_bottom;
    private final ModelPart petal_left;
    private final ModelPart petal_right;
    private final ModelPart Flame;

    public FighterExhaustModel(ModelPart root) {
        super(root);
        this.Nozzle = root.getChild("Nozzle");
        this.petal_top = this.Nozzle.getChild("petal_top");
        this.petal_bottom = this.Nozzle.getChild("petal_bottom");
        this.petal_left = this.Nozzle.getChild("petal_left");
        this.petal_right = this.Nozzle.getChild("petal_right");
        this.Flame = this.Nozzle.getChild("Flame");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Nozzle = partdefinition.addOrReplaceChild("Nozzle", CubeListBuilder.create()
                .texOffs(112, 31).addBox(-3.0F, -3.0F, 1.0F, 6.0F, 6.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 8.0F, 44.0F));

        PartDefinition petal_top = Nozzle.addOrReplaceChild("petal_top", CubeListBuilder.create()
                .texOffs(74, 78).addBox(-5.0F, -1.0F, 0.0F, 10.0F, 2.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, -4.0F, 0.0F));

        PartDefinition petal_bottom = Nozzle.addOrReplaceChild("petal_bottom", CubeListBuilder.create()
                .texOffs(74, 78).addBox(-5.0F, -1.0F, 0.0F, 10.0F, 2.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 4.0F, 0.0F));

        PartDefinition petal_left = Nozzle.addOrReplaceChild("petal_left", CubeListBuilder.create()
                .texOffs(74, 88).addBox(-1.0F, -3.0F, 0.0F, 2.0F, 6.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offset(4.0F, 0.0F, 0.0F));

        PartDefinition petal_right = Nozzle.addOrReplaceChild("petal_right", CubeListBuilder.create()
                .texOffs(74, 88).mirror().addBox(-1.0F, -3.0F, 0.0F, 2.0F, 6.0F, 8.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offset(-4.0F, 0.0F, 0.0F));

        PartDefinition Flame = Nozzle.addOrReplaceChild("Flame", CubeListBuilder.create()
                .texOffs(94, 88).addBox(-2.5F, -2.5F, 0.0F, 5.0F, 5.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(84, 14).addBox(-1.5F, -1.5F, 0.0F, 3.0F, 3.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 8.0F));

        return LayerDefinition.create(meshdefinition, 128, 128);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {
        super.setupAnim(state);
        applyThrottle(state.throttle);
    }

    /**
     * Poses the nozzle for a throttle in [0, 1]: the petals open outwards by up to {@link #PETAL_OPEN_ANGLE}
     * and the afterburner flame is shown above 5% throttle, growing to 1.6x its modelled length at full
     * throttle. Call after the pose reset done by {@code super.setupAnim}.
     */
    public void applyThrottle(float throttle) {
        float t = Mth.clamp(throttle, 0.0F, 1.0F);
        float open = t * PETAL_OPEN_ANGLE;
        this.petal_top.xRot = open;
        this.petal_bottom.xRot = -open;
        this.petal_left.yRot = open;
        this.petal_right.yRot = -open;
        this.Flame.visible = t > 0.05F;
        this.Flame.zScale = 0.4F + 1.2F * t;
        float girth = 0.8F + 0.3F * t;
        this.Flame.xScale = girth;
        this.Flame.yScale = girth;
    }
}
