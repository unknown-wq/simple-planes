package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Mini helicopter, metal layer: the instrument panel and seat back, the engine with its exhaust, and the skid
 * landing gear. Texture {@code textures/plane_upgrades/mini_heli_metal.png} (128x64), shared with
 * {@link MiniHeliRotorModel}; the same for the wooden and the medical version. The glass is a layer of its own,
 * {@link MiniHeliGlassModel}.
 *
 * <p>Default render type ({@code entityCutout}): no eye is inside any of these cubes. See MINI-HELI-MODEL.md.
 */
public class MiniHeliMetalModel extends EntityModel<PlaneRenderState> {

    /** Outward splay of the skid legs, radians. */
    public static final float LEG_SPLAY = 0.45F;
    /** Upward bend of the skid toes, radians. */
    public static final float TOE_BEND = 0.6F;

    public MiniHeliMetalModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Metal = partdefinition.addOrReplaceChild("Metal", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Cockpit interior: the instrument panel on the tub sill and the seat back behind the pilot, both visible
        // through the glass (MiniHeliGlassModel).
        Metal.addOrReplaceChild("Cockpit", CubeListBuilder.create()
                .texOffs(78, 0).addBox(-5.0F, -20.0F, -16.0F, 10.0F, 3.0F, 2.0F, CubeDeformation.NONE)
                .texOffs(0, 0).addBox(-4.0F, -22.0F, -4.0F, 8.0F, 5.0F, 2.0F, CubeDeformation.NONE), PartPose.ZERO);

        // Engine on top of the rear pod; the rotor mast (MiniHeliRotorModel) rises from its front half.
        Metal.addOrReplaceChild("Engine", CubeListBuilder.create()
                .texOffs(40, 0).addBox(-4.0F, -20.0F, 2.0F, 8.0F, 5.0F, 7.0F, CubeDeformation.NONE)
                .texOffs(26, 0).addBox(1.0F, -19.0F, 9.0F, 2.0F, 2.0F, 3.0F, CubeDeformation.NONE), PartPose.ZERO);

        // Skids: two tubes, two splayed legs per side from the belly edge, and upturned toes.
        Metal.addOrReplaceChild("skid_left", CubeListBuilder.create()
                .texOffs(0, 0).addBox(8.5F, -1.0F, -15.0F, 1.0F, 1.0F, 24.0F, CubeDeformation.NONE), PartPose.ZERO);
        Metal.addOrReplaceChild("skid_right", CubeListBuilder.create()
                .texOffs(0, 0).mirror().addBox(-9.5F, -1.0F, -15.0F, 1.0F, 1.0F, 24.0F, CubeDeformation.NONE).mirror(false), PartPose.ZERO);
        Metal.addOrReplaceChild("legs_left", CubeListBuilder.create()
                .texOffs(20, 0).addBox(-0.5F, 0.0F, -12.0F, 1.0F, 5.0F, 1.0F, CubeDeformation.NONE)
                .texOffs(20, 0).addBox(-0.5F, 0.0F, 0.0F, 1.0F, 5.0F, 1.0F, CubeDeformation.NONE),
                PartPose.offsetAndRotation(7.0F, -5.0F, 0.0F, 0.0F, 0.0F, -LEG_SPLAY));
        Metal.addOrReplaceChild("legs_right", CubeListBuilder.create()
                .texOffs(20, 0).mirror().addBox(-0.5F, 0.0F, -12.0F, 1.0F, 5.0F, 1.0F, CubeDeformation.NONE)
                .texOffs(20, 0).addBox(-0.5F, 0.0F, 0.0F, 1.0F, 5.0F, 1.0F, CubeDeformation.NONE).mirror(false),
                PartPose.offsetAndRotation(-7.0F, -5.0F, 0.0F, 0.0F, 0.0F, LEG_SPLAY));
        Metal.addOrReplaceChild("toe_left", CubeListBuilder.create()
                .texOffs(115, 4).addBox(-0.5F, -0.5F, -3.0F, 1.0F, 1.0F, 3.0F, CubeDeformation.NONE),
                PartPose.offsetAndRotation(9.0F, -0.5F, -15.0F, -TOE_BEND, 0.0F, 0.0F));
        Metal.addOrReplaceChild("toe_right", CubeListBuilder.create()
                .texOffs(115, 4).mirror().addBox(-0.5F, -0.5F, -3.0F, 1.0F, 1.0F, 3.0F, CubeDeformation.NONE).mirror(false),
                PartPose.offsetAndRotation(-9.0F, -0.5F, -15.0F, -TOE_BEND, 0.0F, 0.0F));

        return LayerDefinition.create(meshdefinition, 128, 64);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
