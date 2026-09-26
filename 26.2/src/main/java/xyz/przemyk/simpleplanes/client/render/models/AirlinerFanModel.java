package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Mini airliner, propeller slot: the two turbofan fan discs, each a spinner and eight twisted blades (four crossed bars),
 * recessed in the intake rings of {@link AirlinerMetalModel}. They spin with {@code state.propellerRotation},
 * exactly like {@link PropellerModel}. Uses {@code textures/plane_upgrades/airliner_metal.png} (256x256).
 */
public class AirlinerFanModel extends EntityModel<PlaneRenderState> {
    /** Blade twist about the blade's own axis, radians. */
    private static final float BLADE_PITCH = 0.5F;

    private final ModelPart fanLeft;
    private final ModelPart fanRight;

    public AirlinerFanModel(ModelPart root) {
        super(root);
        ModelPart fans = root.getChild("Fans");
        this.fanLeft = fans.getChild("fan_left");
        this.fanRight = fans.getChild("fan_right");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Fans = partdefinition.addOrReplaceChild("Fans", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        addFan(Fans, "fan_left", 32.0F);
        addFan(Fans, "fan_right", -32.0F);

        return LayerDefinition.create(meshdefinition, 256, 256);
    }

    /** One fan disc, pivoted on the nacelle axis, 2 px behind the front of the intake lip. */
    private static void addFan(PartDefinition parent, String name, float x) {
        PartDefinition fan = parent.addOrReplaceChild(name, CubeListBuilder.create()
                .texOffs(119, 14).addBox(-1.5F, -1.5F, -2.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F)),
                PartPose.offset(x, -10.0F, -24.0F));
        for (int i = 0; i < 4; i++) {
            fan.addOrReplaceChild("blades_" + i, CubeListBuilder.create()
                    .texOffs(15, 15).addBox(-5.0F, -1.0F, -0.5F, 10.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)),
                    PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, BLADE_PITCH, 0.0F, i * (float) Math.PI / 4.0F));
        }
    }

    @Override
    public void setupAnim(PlaneRenderState state) {
        super.setupAnim(state);
        fanLeft.zRot = state.propellerRotation;
        fanRight.zRot = state.propellerRotation;
    }
}
