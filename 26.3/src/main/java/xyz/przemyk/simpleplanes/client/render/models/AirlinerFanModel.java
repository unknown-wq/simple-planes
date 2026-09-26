package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Airliner, propeller slot: the two turbofan fan discs, each a spinner and eight twisted blades (four crossed bars),
 * recessed in the intake rings of {@link AirlinerMetalModel}. They spin with {@code state.propellerRotation},
 * exactly like {@link PropellerModel}. Uses the size's {@code *_metal.png} (256x256).
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

    public static LayerDefinition createBodyLayer(AirlinerShape shape) {
        return LayerDefinition.create(create(shape, AirlinerAirframe.table(shape.metalUv)), 256, 256);
    }

    static MeshDefinition create(AirlinerShape shape, AirlinerAirframe.UvLayout uv) {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Fans = partdefinition.addOrReplaceChild("Fans", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        addFan(Fans, "fan_left", shape, uv, shape.engineX);
        addFan(Fans, "fan_right", shape, uv, -shape.engineX);

        return meshdefinition;
    }

    /** One fan disc, pivoted on the nacelle axis, 2 px behind the front of the intake lip; the blades span the cowl. */
    private static void addFan(PartDefinition parent, String name, AirlinerShape shape, AirlinerAirframe.UvLayout uv, float x) {
        float span = 2 * shape.engineRadius - 2;
        int[] spinner = uv.texOffs("spinner", -1.5F, -1.5F, -2.0F, 3.0F, 3.0F, 3.0F);
        int[] blade = uv.texOffs("blade", -span / 2, -1.0F, -0.5F, span, 2.0F, 1.0F);
        PartDefinition fan = parent.addOrReplaceChild(name, CubeListBuilder.create()
                .texOffs(spinner[0], spinner[1]).addBox(-1.5F, -1.5F, -2.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F)),
                PartPose.offset(x, shape.engineY, shape.engineFront + 2));
        for (int i = 0; i < 4; i++) {
            fan.addOrReplaceChild("blades_" + i, CubeListBuilder.create()
                    .texOffs(blade[0], blade[1]).addBox(-span / 2, -1.0F, -0.5F, span, 2.0F, 1.0F, new CubeDeformation(0.0F)),
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
