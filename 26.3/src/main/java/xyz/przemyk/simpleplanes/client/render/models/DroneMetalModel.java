package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Mini reconnaissance quadcopter, metal layer: electronics shell, battery pack, GPS puck, the two antennas,
 * the four motors, landing legs, the camera gimbal and the drop-release clamp with its payload.
 * Uses {@code textures/plane_upgrades/drone_metal.png} (64x32).
 *
 * <p>Animation hook: {@link #setPayloadAttached(boolean)} hides the payload and opens the clamp jaws.
 * {@link PlaneRenderState} carries no payload flag yet, so {@link #setupAnim} currently passes
 * {@link #DEFAULT_PAYLOAD_ATTACHED}; see DRONE-MODEL.md for how to wire it.
 */
public class DroneMetalModel extends EntityModel<PlaneRenderState> {

    /** Payload state used until the render state carries a real one: payload loaded, clamp closed. */
    public static final boolean DEFAULT_PAYLOAD_ATTACHED = true;
    /** Outward swing of each clamp jaw once the payload is released, in radians. */
    public static final float JAW_OPEN_ANGLE = 0.6109F;
    /** Nose-down tilt of the gimbal camera, in radians. */
    public static final float CAMERA_TILT = 0.3491F;
    /** Outward splay of each landing leg about the X and Z axes, in radians. */
    public static final float LEG_SPLAY = 0.2F;

    private final ModelPart Metal;
    private final ModelPart jaw_left;
    private final ModelPart jaw_right;
    private final ModelPart Payload;

    public DroneMetalModel(ModelPart root) {
        super(root);
        this.Metal = root.getChild("Metal");
        ModelPart clamp = this.Metal.getChild("Clamp");
        this.jaw_left = clamp.getChild("jaw_left");
        this.jaw_right = clamp.getChild("jaw_right");
        this.Payload = this.Metal.getChild("Payload");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Metal = partdefinition.addOrReplaceChild("Metal", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        Metal.addOrReplaceChild("Body", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-3.0F, -12.0F, -4.0F, 6.0F, 3.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(28, 0).addBox(-2.0F, -14.0F, -2.0F, 4.0F, 2.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(38, 14).addBox(-1.0F, -13.0F, -4.0F, 2.0F, 1.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(16, 11).addBox(2.0F, -13.0F, 3.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(16, 11).addBox(-3.0F, -13.0F, 3.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        // Whip antennas, 0.5 px thick, leaning out and back from the rear corners of the shell.
        Metal.addOrReplaceChild("antenna_left", CubeListBuilder.create()
                .texOffs(46, 14).addBox(-0.5F, -5.0F, -0.5F, 1.0F, 5.0F, 1.0F, new CubeDeformation(-0.25F)), PartPose.offsetAndRotation(2.5F, -12.75F, 3.5F, -0.2618F, 0.0F, 0.3491F));

        Metal.addOrReplaceChild("antenna_right", CubeListBuilder.create()
                .texOffs(46, 14).addBox(-0.5F, -5.0F, -0.5F, 1.0F, 5.0F, 1.0F, new CubeDeformation(-0.25F)), PartPose.offsetAndRotation(-2.5F, -12.75F, 3.5F, -0.2618F, 0.0F, -0.3491F));

        // Motors at the four beam ends, (+-6.5, +-6.5); the rotors sit on top (DroneRotorModel).
        Metal.addOrReplaceChild("Motors", CubeListBuilder.create()
                .texOffs(28, 8).addBox(5.0F, -12.0F, -8.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(28, 8).addBox(-8.0F, -12.0F, -8.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(28, 8).addBox(5.0F, -12.0F, 5.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(28, 8).addBox(-8.0F, -12.0F, 5.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        // Landing legs: four struts from the middle of the arms, splayed out along the diagonals, with rubber
        // feet whose bottoms are the ground contact (y = 0).
        PartDefinition Legs = Metal.addOrReplaceChild("Legs", CubeListBuilder.create()
                .texOffs(56, 14).addBox(5.0F, -1.0F, -7.0F, 2.0F, 1.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(56, 14).addBox(-7.0F, -1.0F, -7.0F, 2.0F, 1.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(56, 14).addBox(5.0F, -1.0F, 5.0F, 2.0F, 1.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(56, 14).addBox(-7.0F, -1.0F, 5.0F, 2.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        Legs.addOrReplaceChild("leg_front_left", CubeListBuilder.create()
                .texOffs(52, 8).addBox(-0.5F, 0.0F, -0.5F, 1.0F, 8.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(4.5F, -8.5F, -4.5F, -LEG_SPLAY, 0.0F, -LEG_SPLAY));
        Legs.addOrReplaceChild("leg_front_right", CubeListBuilder.create()
                .texOffs(52, 8).addBox(-0.5F, 0.0F, -0.5F, 1.0F, 8.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.5F, -8.5F, -4.5F, -LEG_SPLAY, 0.0F, LEG_SPLAY));
        Legs.addOrReplaceChild("leg_rear_left", CubeListBuilder.create()
                .texOffs(52, 8).addBox(-0.5F, 0.0F, -0.5F, 1.0F, 8.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(4.5F, -8.5F, 4.5F, LEG_SPLAY, 0.0F, -LEG_SPLAY));
        Legs.addOrReplaceChild("leg_rear_right", CubeListBuilder.create()
                .texOffs(52, 8).addBox(-0.5F, 0.0F, -0.5F, 1.0F, 8.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.5F, -8.5F, 4.5F, LEG_SPLAY, 0.0F, LEG_SPLAY));

        // Gimbal under the nose: a U yoke hanging from the frame plate and a camera pitched nose-down in it.
        PartDefinition Gimbal = Metal.addOrReplaceChild("Gimbal", CubeListBuilder.create()
                .texOffs(0, 15).addBox(-2.5F, 0.0F, -0.5F, 5.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 17).addBox(1.5F, 1.0F, -0.5F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 17).addBox(-2.5F, 1.0F, -0.5F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, -7.0F, -5.0F));

        Gimbal.addOrReplaceChild("Camera", CubeListBuilder.create()
                .texOffs(40, 8).addBox(-1.5F, -1.5F, -1.5F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 2.5F, 0.0F, CAMERA_TILT, 0.0F, 0.0F));

        // Drop-release clamp under the centre: a servo housing and two L-shaped jaws hinged at its sides.
        PartDefinition Clamp = Metal.addOrReplaceChild("Clamp", CubeListBuilder.create()
                .texOffs(0, 11).addBox(-2.5F, -7.0F, -0.5F, 5.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        Clamp.addOrReplaceChild("jaw_left", CubeListBuilder.create()
                .texOffs(56, 8).addBox(0.0F, 0.0F, -1.0F, 1.0F, 4.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(28, 16).addBox(-1.0F, 3.0F, -1.0F, 1.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offset(1.5F, -6.0F, 1.0F));

        Clamp.addOrReplaceChild("jaw_right", CubeListBuilder.create()
                .texOffs(56, 8).addBox(-1.0F, 0.0F, -1.0F, 1.0F, 4.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(28, 16).addBox(0.0F, 3.0F, -1.0F, 1.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offset(-1.5F, -6.0F, 1.0F));

        // The payload: a small finned drop canister held in the jaws. Its centre is the payload attachment point.
        Metal.addOrReplaceChild("Payload", CubeListBuilder.create()
                .texOffs(48, 0).addBox(-1.5F, -6.0F, -1.5F, 3.0F, 3.0F, 5.0F, new CubeDeformation(0.0F))
                .texOffs(20, 11).addBox(-0.5F, -5.0F, -2.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(12, 16).addBox(0.0F, -6.5F, 3.5F, 0.0F, 4.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(16, 16).addBox(-2.0F, -4.5F, 3.5F, 4.0F, 0.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        return LayerDefinition.create(meshdefinition, 64, 32);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {
        super.setupAnim(state);
        // Hook for the drone entity: replace DEFAULT_PAYLOAD_ATTACHED with a flag copied into the render state.
        setPayloadAttached(DEFAULT_PAYLOAD_ATTACHED);
    }

    /**
     * Closes the clamp jaws while a load is attached and swings them outwards by {@link #JAW_OPEN_ANGLE}
     * otherwise. The payload canister is never drawn: this airframe is a crane. Call after the pose reset
     * done by {@code super.setupAnim}.
     */
    public void setPayloadAttached(boolean attached) {
        this.Payload.visible = false;
        float open = attached ? 0.0F : JAW_OPEN_ANGLE;
        this.jaw_left.zRot = -open;
        this.jaw_right.zRot = open;
    }
}
