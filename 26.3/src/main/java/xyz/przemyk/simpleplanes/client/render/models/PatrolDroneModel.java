package xyz.przemyk.simpleplanes.client.render.models;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import xyz.przemyk.simpleplanes.client.render.PatrolDroneRenderState;

/**
 * Patrol drone, built here and textured by {@code textures/plane_upgrades/patrol_drone.png} (64x64): a flat
 * fuselage with a camera pod underneath, an X of arms with a motor and a two-blade rotor at each tip, four skids,
 * and a beacon on top. Origin is the airframe centre; +Y is down, -Z is the nose.
 *
 * <p>One layer, baked twice: the body instance hides the beacon, the beacon instance shows only the beacon (green
 * on patrol, red while tracking) so the renderer can draw it full-bright.
 */
@Environment(EnvType.CLIENT)
public class PatrolDroneModel extends EntityModel<PatrolDroneRenderState> {

    /** Rotor centres, px from the airframe centre on each axis. */
    public static final float ARM = 5.5F;

    private final boolean beaconOnly;
    private final ModelPart[] body;
    private final ModelPart[] rotors = new ModelPart[4];
    private final ModelPart beaconGreen;
    private final ModelPart beaconRed;

    public PatrolDroneModel(ModelPart root, boolean beaconOnly) {
        super(root);
        this.beaconOnly = beaconOnly;
        ModelPart frame = root.getChild("frame");
        this.body = new ModelPart[] {frame.getChild("fuselage"), frame.getChild("pod"), frame.getChild("arm_a"),
            frame.getChild("arm_b"), frame.getChild("skids")};
        for (int i = 0; i < 4; i++) {
            rotors[i] = frame.getChild("motor_" + i);
        }
        this.beaconGreen = frame.getChild("beacon_green");
        this.beaconRed = frame.getChild("beacon_red");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition frame = mesh.getRoot().addOrReplaceChild("frame", CubeListBuilder.create(), PartPose.ZERO);

        frame.addOrReplaceChild("fuselage", CubeListBuilder.create()
            .texOffs(0, 0).addBox(-3.0F, -2.0F, -4.0F, 6.0F, 3.0F, 8.0F), PartPose.ZERO);
        frame.addOrReplaceChild("pod", CubeListBuilder.create()
            .texOffs(0, 12).addBox(-1.5F, 1.0F, -3.5F, 3.0F, 2.0F, 3.0F), PartPose.ZERO);

        float diag = 0.7854F;
        CubeListBuilder arm = CubeListBuilder.create().texOffs(0, 20).addBox(-0.5F, -0.5F, -7.0F, 1.0F, 1.0F, 14.0F);
        frame.addOrReplaceChild("arm_a", arm, PartPose.offsetAndRotation(0.0F, -1.0F, 0.0F, 0.0F, diag, 0.0F));
        frame.addOrReplaceChild("arm_b", arm, PartPose.offsetAndRotation(0.0F, -1.0F, 0.0F, 0.0F, -diag, 0.0F));

        CubeListBuilder skids = CubeListBuilder.create();
        for (float x : new float[] {-3.5F, 2.5F}) {
            for (float z : new float[] {-3.5F, 2.5F}) {
                skids.texOffs(32, 6).addBox(x, 1.0F, z, 1.0F, 3.0F, 1.0F);
            }
        }
        frame.addOrReplaceChild("skids", skids, PartPose.ZERO);

        float[][] tips = {{ARM, -ARM}, {-ARM, -ARM}, {ARM, ARM}, {-ARM, ARM}};
        for (int i = 0; i < 4; i++) {
            PartDefinition motor = frame.addOrReplaceChild("motor_" + i, CubeListBuilder.create()
                .texOffs(32, 0).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F),
                PartPose.offset(tips[i][0], -1.5F, tips[i][1]));
            motor.addOrReplaceChild("blade", CubeListBuilder.create()
                .texOffs(32, 12).addBox(-3.5F, 0.0F, -0.5F, 7.0F, 0.0F, 1.0F),
                PartPose.offset(0.0F, -1.2F, 0.0F));
        }

        frame.addOrReplaceChild("beacon_green", CubeListBuilder.create()
            .texOffs(48, 0).addBox(-0.5F, -3.0F, 2.0F, 1.0F, 1.0F, 1.0F), PartPose.ZERO);
        frame.addOrReplaceChild("beacon_red", CubeListBuilder.create()
            .texOffs(48, 4).addBox(-0.5F, -3.0F, 2.0F, 1.0F, 1.0F, 1.0F), PartPose.ZERO);

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(PatrolDroneRenderState state) {
        super.setupAnim(state);
        for (ModelPart part : body) {
            part.visible = !beaconOnly;
        }
        for (int i = 0; i < 4; i++) {
            rotors[i].visible = !beaconOnly;
            // diagonal pairs turn the same way
            float r = state.propellerRotation;
            rotors[i].getChild("blade").yRot = (i == 0 || i == 3) ? r : -r;
        }
        beaconGreen.visible = beaconOnly && !state.tracking;
        beaconRed.visible = beaconOnly && state.tracking;
    }
}
