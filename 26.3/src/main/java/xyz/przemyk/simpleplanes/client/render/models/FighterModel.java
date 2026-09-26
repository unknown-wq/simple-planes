package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Fighter jet, material (wood) layer: fuselage, delta wings, stabilisers and the twin canted fins.
 * Textured with the block texture of the plane's material, tiled 1 texel per pixel like {@link PlaneModel}.
 *
 * <p>Nose points to -Z, the ground contact of the landing gear is at y = 24 (see FIGHTER-MODEL.md).
 */
public class FighterModel extends EntityModel<PlaneRenderState> {

    private final ModelPart fighter;

    public FighterModel(ModelPart root) {
        super(root);
        this.fighter = root.getChild("Fighter");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Fighter = partdefinition.addOrReplaceChild("Fighter", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        PartDefinition Fuselage = Fighter.addOrReplaceChild("Fuselage", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-5.0F, -20.0F, -46.0F, 10.0F, 8.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(4, 2).addBox(-7.0F, -22.0F, -38.0F, 14.0F, 11.0F, 14.0F, new CubeDeformation(0.0F))
                .texOffs(0, 6).addBox(-8.0F, -11.0F, -24.0F, 16.0F, 1.0F, 22.0F, new CubeDeformation(0.0F))
                .texOffs(8, 0).addBox(-8.0F, -22.0F, -24.0F, 1.0F, 11.0F, 22.0F, new CubeDeformation(0.0F))
                .texOffs(8, 0).mirror().addBox(7.0F, -22.0F, -24.0F, 1.0F, 11.0F, 22.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(2, 4).addBox(-8.0F, -22.0F, -2.0F, 16.0F, 12.0F, 32.0F, new CubeDeformation(0.0F))
                .texOffs(6, 10).addBox(-7.0F, -22.0F, 30.0F, 14.0F, 12.0F, 14.0F, new CubeDeformation(0.0F))
                .texOffs(12, 8).addBox(-4.0F, -27.0F, 0.0F, 8.0F, 5.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(10, 3).addBox(-4.0F, -24.0F, 12.0F, 8.0F, 2.0F, 16.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        PartDefinition Wings = Fighter.addOrReplaceChild("Wings", CubeListBuilder.create()
                .texOffs(0, 0).addBox(8.0F, -15.0F, -10.0F, 9.0F, 2.0F, 40.0F, new CubeDeformation(0.0F))
                .texOffs(4, 0).addBox(17.0F, -15.0F, -1.0F, 9.0F, 2.0F, 31.0F, new CubeDeformation(0.0F))
                .texOffs(8, 0).addBox(26.0F, -15.0F, 8.0F, 9.0F, 2.0F, 22.0F, new CubeDeformation(0.0F))
                .texOffs(12, 0).addBox(35.0F, -15.0F, 17.0F, 8.0F, 2.0F, 13.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).mirror().addBox(-17.0F, -15.0F, -10.0F, 9.0F, 2.0F, 40.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(4, 0).mirror().addBox(-26.0F, -15.0F, -1.0F, 9.0F, 2.0F, 31.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(8, 0).mirror().addBox(-35.0F, -15.0F, 8.0F, 9.0F, 2.0F, 22.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(12, 0).mirror().addBox(-43.0F, -15.0F, 17.0F, 8.0F, 2.0F, 13.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);

        PartDefinition Tail = Fighter.addOrReplaceChild("Tail", CubeListBuilder.create()
                .texOffs(2, 6).addBox(7.0F, -16.0F, 33.0F, 8.0F, 2.0F, 13.0F, new CubeDeformation(0.0F))
                .texOffs(6, 2).addBox(15.0F, -16.0F, 38.0F, 8.0F, 2.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(2, 6).mirror().addBox(-15.0F, -16.0F, 33.0F, 8.0F, 2.0F, 13.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(6, 2).mirror().addBox(-23.0F, -16.0F, 38.0F, 8.0F, 2.0F, 8.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);

        PartDefinition fin_left = Tail.addOrReplaceChild("fin_left", CubeListBuilder.create()
                .texOffs(0, 4).addBox(-1.0F, -4.0F, 26.0F, 2.0F, 4.0F, 16.0F, new CubeDeformation(0.0F))
                .texOffs(4, 8).addBox(-1.0F, -8.0F, 29.0F, 2.0F, 4.0F, 14.0F, new CubeDeformation(0.0F))
                .texOffs(8, 12).addBox(-1.0F, -12.0F, 32.0F, 2.0F, 4.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(12, 2).addBox(-1.0F, -15.0F, 35.0F, 2.0F, 3.0F, 10.0F, new CubeDeformation(0.0F))
                .texOffs(2, 14).addBox(-1.0F, -18.0F, 38.0F, 2.0F, 3.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(5.0F, -21.5F, 0.0F, 0.0F, 0.0F, 0.2618F));

        PartDefinition fin_right = Tail.addOrReplaceChild("fin_right", CubeListBuilder.create()
                .texOffs(0, 4).mirror().addBox(-1.0F, -4.0F, 26.0F, 2.0F, 4.0F, 16.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(4, 8).mirror().addBox(-1.0F, -8.0F, 29.0F, 2.0F, 4.0F, 14.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(8, 12).mirror().addBox(-1.0F, -12.0F, 32.0F, 2.0F, 4.0F, 12.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(12, 2).mirror().addBox(-1.0F, -15.0F, 35.0F, 2.0F, 3.0F, 10.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(2, 14).mirror().addBox(-1.0F, -18.0F, 38.0F, 2.0F, 3.0F, 8.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offsetAndRotation(-5.0F, -21.5F, 0.0F, 0.0F, 0.0F, -0.2618F));

        return LayerDefinition.create(meshdefinition, 16, 16);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
