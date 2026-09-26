package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Mini helicopter, metal layer: the bubble canopy (tinted glass), the instrument panel, the engine with its
 * exhaust, and the skid landing gear. Texture {@code textures/plane_upgrades/mini_heli_metal.png} (128x64),
 * shared with {@link MiniHeliRotorModel}; the same for the wooden and the medical version.
 *
 * <p>Rendered with {@link RenderTypes#entityCutoutCull}: the pilot's eye is inside the {@code glass_main} box,
 * and only a back-face-culled render type lets the pilot see out through the bubble. The glass faces that
 * still point at the eye from inside (the rear of the front bulge, the bottom of the cap, the part of the main
 * box's top under the cap) are transparent in the texture. See MINI-HELI-MODEL.md.
 */
public class MiniHeliMetalModel extends EntityModel<PlaneRenderState> {

    /** Outward splay of the skid legs, radians. */
    public static final float LEG_SPLAY = 0.45F;
    /** Upward bend of the skid toes, radians. */
    public static final float TOE_BEND = 0.6F;

    public MiniHeliMetalModel(ModelPart root) {
        super(root, RenderTypes::entityCutoutCull);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Metal = partdefinition.addOrReplaceChild("Metal", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Bubble: main box around the pilot, a cap over the head and a bulge over the nose.
        Metal.addOrReplaceChild("Canopy", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-8.0F, -27.0F, -16.0F, 16.0F, 15.0F, 18.0F, CubeDeformation.NONE)
                .texOffs(54, 25).addBox(-6.0F, -31.0F, -14.0F, 12.0F, 4.0F, 14.0F, CubeDeformation.NONE)
                .texOffs(50, 0).addBox(-6.0F, -25.0F, -19.0F, 12.0F, 13.0F, 3.0F, CubeDeformation.NONE)
                .texOffs(94, 14).addBox(-5.0F, -15.0F, -16.0F, 10.0F, 3.0F, 2.0F, CubeDeformation.NONE), PartPose.ZERO);

        // Engine on top of the rear pod; the rotor mast (MiniHeliRotorModel) rises from its front half.
        Metal.addOrReplaceChild("Engine", CubeListBuilder.create()
                .texOffs(92, 25).addBox(-4.0F, -20.0F, 2.0F, 8.0F, 5.0F, 7.0F, CubeDeformation.NONE)
                .texOffs(8, 0).addBox(1.0F, -19.0F, 9.0F, 2.0F, 2.0F, 3.0F, CubeDeformation.NONE), PartPose.ZERO);

        // Skids: two tubes, two splayed legs per side from the belly edge, and upturned toes.
        Metal.addOrReplaceChild("skid_left", CubeListBuilder.create()
                .texOffs(68, 0).addBox(8.5F, -1.0F, -15.0F, 1.0F, 1.0F, 24.0F, CubeDeformation.NONE), PartPose.ZERO);
        Metal.addOrReplaceChild("skid_right", CubeListBuilder.create()
                .texOffs(68, 0).mirror().addBox(-9.5F, -1.0F, -15.0F, 1.0F, 1.0F, 24.0F, CubeDeformation.NONE).mirror(false), PartPose.ZERO);
        Metal.addOrReplaceChild("legs_left", CubeListBuilder.create()
                .texOffs(102, 0).addBox(-0.5F, 0.0F, -12.0F, 1.0F, 5.0F, 1.0F, CubeDeformation.NONE)
                .texOffs(102, 0).addBox(-0.5F, 0.0F, 0.0F, 1.0F, 5.0F, 1.0F, CubeDeformation.NONE),
                PartPose.offsetAndRotation(7.0F, -5.0F, 0.0F, 0.0F, 0.0F, -LEG_SPLAY));
        Metal.addOrReplaceChild("legs_right", CubeListBuilder.create()
                .texOffs(102, 0).mirror().addBox(-0.5F, 0.0F, -12.0F, 1.0F, 5.0F, 1.0F, CubeDeformation.NONE)
                .texOffs(102, 0).addBox(-0.5F, 0.0F, 0.0F, 1.0F, 5.0F, 1.0F, CubeDeformation.NONE).mirror(false),
                PartPose.offsetAndRotation(-7.0F, -5.0F, 0.0F, 0.0F, 0.0F, LEG_SPLAY));
        Metal.addOrReplaceChild("toe_left", CubeListBuilder.create()
                .texOffs(94, 0).addBox(-0.5F, -0.5F, -3.0F, 1.0F, 1.0F, 3.0F, CubeDeformation.NONE),
                PartPose.offsetAndRotation(9.0F, -0.5F, -15.0F, -TOE_BEND, 0.0F, 0.0F));
        Metal.addOrReplaceChild("toe_right", CubeListBuilder.create()
                .texOffs(94, 0).mirror().addBox(-0.5F, -0.5F, -3.0F, 1.0F, 1.0F, 3.0F, CubeDeformation.NONE).mirror(false),
                PartPose.offsetAndRotation(-9.0F, -0.5F, -15.0F, -TOE_BEND, 0.0F, 0.0F));

        return LayerDefinition.create(meshdefinition, 128, 64);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
