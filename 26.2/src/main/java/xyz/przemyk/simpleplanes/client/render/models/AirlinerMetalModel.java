package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Mini airliner, metal layer: cockpit windows and instrument panel, the passenger window belts and
 * door outlines, the two underwing turbofan nacelles with their pylons, the winglets, the airline logo on the
 * fin, the APU cone and the tricycle landing gear. Uses {@code textures/plane_upgrades/airliner_metal.png}
 * (256x256). Shared by the wooden ({@link AirlinerModel}) and the metal-skinned ({@link AirlinerSkinModel})
 * airliner.
 *
 * <p>Windows, doors and logos are flush plates just outside the skin ({@code CubeDeformation} 0.05); every
 * face of them that points into the cabin is transparent in the texture. Rendered with
 * {@link RenderTypes#entityCutoutCull}, because the pilot's eye is inside the windscreen box: the default
 * {@code entityCutout} does not cull in 26.2 and would close the view. The fan discs are in
 * {@link AirlinerFanModel}.
 *
 * <p>The fin carries one of {@link #LOGO_COUNT} fictional airline logos, chosen per aircraft with
 * {@link #setLogo(int)}; see AIRLINER-MODEL.md for how the entity rolls, saves and passes it.
 */
public class AirlinerMetalModel extends EntityModel<PlaneRenderState> {
    /** Number of airline logos: 0 Terntide, 1 Glimmerwing, 2 Pinewind, 3 Puffcloud Express, 4 Coralline, 5 Marigold Hop. */
    public static final int LOGO_COUNT = 6;
    /**
     * Logo used until the render state carries the aircraft's own: replace it in {@link #setupAnim} with the
     * logo index from {@code PlaneRenderState} (see AIRLINER-MODEL.md).
     */
    public static final int DEFAULT_LOGO = 0;

    private final ModelPart Metal;
    private final ModelPart[] logos = new ModelPart[LOGO_COUNT];

    public AirlinerMetalModel(ModelPart root) {
        super(root, RenderTypes::entityCutoutCull);
        this.Metal = root.getChild("Metal");
        ModelPart tail = this.Metal.getChild("Tail");
        for (int i = 0; i < LOGO_COUNT; i++) {
            this.logos[i] = tail.getChild("logo_" + i);
        }
        setLogo(DEFAULT_LOGO);
    }

    /** Shows logo {@code index} (taken modulo {@link #LOGO_COUNT}) on both sides of the fin and hides the others. */
    public void setLogo(int index) {
        int shown = Math.floorMod(index, LOGO_COUNT);
        for (int i = 0; i < LOGO_COUNT; i++) {
            this.logos[i].visible = i == shown;
        }
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Metal = partdefinition.addOrReplaceChild("Metal", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Two-tier windscreen; the pilot's eye is inside glass_lo.
        Metal.addOrReplaceChild("Cockpit", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-13.0F, -33.0F, -81.0F, 26.0F, 3.0F, 11.0F, new CubeDeformation(0.05F))
                .texOffs(102, 0).addBox(-13.0F, -36.0F, -80.0F, 26.0F, 3.0F, 10.0F, new CubeDeformation(0.05F))
                .texOffs(102, 14).addBox(-10.0F, -29.0F, -80.0F, 20.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        Metal.addOrReplaceChild("Cabin", CubeListBuilder.create()
                .texOffs(0, 0).addBox(12.0F, -35.0F, -60.0F, 1.0F, 6.0F, 99.0F, new CubeDeformation(0.05F))
                .texOffs(0, 0).mirror().addBox(-13.0F, -35.0F, -60.0F, 1.0F, 6.0F, 99.0F, new CubeDeformation(0.05F)).mirror(false)
                .texOffs(233, 6).addBox(12.0F, -37.0F, -67.0F, 1.0F, 20.0F, 7.0F, new CubeDeformation(0.04F))
                .texOffs(233, 6).mirror().addBox(-13.0F, -37.0F, -67.0F, 1.0F, 20.0F, 7.0F, new CubeDeformation(0.04F)).mirror(false)
                .texOffs(233, 6).addBox(12.0F, -37.0F, 39.0F, 1.0F, 20.0F, 7.0F, new CubeDeformation(0.04F))
                .texOffs(233, 6).mirror().addBox(-13.0F, -37.0F, 39.0F, 1.0F, 20.0F, 7.0F, new CubeDeformation(0.04F)).mirror(false), PartPose.ZERO);

        // Turbofan nacelles: an open intake ring, the cowl (its front face is the fan case), exhaust and plug.
        Metal.addOrReplaceChild("engine_left", CubeListBuilder.create()
                .texOffs(218, 0).addBox(25.0F, -17.0F, -26.0F, 14.0F, 2.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(218, 0).addBox(25.0F, -5.0F, -26.0F, 14.0F, 2.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(63, 22).addBox(25.0F, -15.0F, -26.0F, 2.0F, 10.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(63, 22).addBox(37.0F, -15.0F, -26.0F, 2.0F, 10.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(178, 0).addBox(26.0F, -16.0F, -23.0F, 12.0F, 12.0F, 15.0F, new CubeDeformation(0.0F))
                .texOffs(32, 22).addBox(27.0F, -15.0F, -8.0F, 10.0F, 10.0F, 5.0F, new CubeDeformation(0.0F))
                .texOffs(178, 0).addBox(30.0F, -12.0F, -3.0F, 4.0F, 4.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(57, 0).addBox(31.0F, -19.0F, -21.0F, 2.0F, 3.0F, 18.0F, new CubeDeformation(0.0F)), PartPose.ZERO);

        Metal.addOrReplaceChild("engine_right", CubeListBuilder.create()
                .texOffs(218, 0).mirror().addBox(-39.0F, -17.0F, -26.0F, 14.0F, 2.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(218, 0).mirror().addBox(-39.0F, -5.0F, -26.0F, 14.0F, 2.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(63, 22).mirror().addBox(-27.0F, -15.0F, -26.0F, 2.0F, 10.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(63, 22).mirror().addBox(-39.0F, -15.0F, -26.0F, 2.0F, 10.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(178, 0).mirror().addBox(-38.0F, -16.0F, -23.0F, 12.0F, 12.0F, 15.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(32, 22).mirror().addBox(-37.0F, -15.0F, -8.0F, 10.0F, 10.0F, 5.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(178, 0).mirror().addBox(-34.0F, -12.0F, -3.0F, 4.0F, 4.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(57, 0).mirror().addBox(-33.0F, -19.0F, -21.0F, 2.0F, 3.0F, 18.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);

        // Winglets ride on the wing tips, so they share the wings' pivots and dihedral (see AirlinerModel).
        Metal.addOrReplaceChild("winglet_left", CubeListBuilder.create()
                .texOffs(0, 15).addBox(70.0F, -7.0F, 18.0F, 2.0F, 5.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(102, 19).addBox(70.0F, -12.0F, 22.0F, 2.0F, 5.0F, 8.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(12.0F, -14.0F, 0.0F, 0.0F, 0.0F, -AirlinerAirframe.WING_DIHEDRAL));

        Metal.addOrReplaceChild("winglet_right", CubeListBuilder.create()
                .texOffs(0, 15).mirror().addBox(-72.0F, -7.0F, 18.0F, 2.0F, 5.0F, 12.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(102, 19).mirror().addBox(-72.0F, -12.0F, 22.0F, 2.0F, 5.0F, 8.0F, new CubeDeformation(0.0F)).mirror(false),
                PartPose.offsetAndRotation(-12.0F, -14.0F, 0.0F, 0.0F, 0.0F, AirlinerAirframe.WING_DIHEDRAL));

        // Airline logos: one flush plate per side of the fin, covering its upper five steps. Texels outside the
        // fin's outline are transparent. Only the logo chosen with setLogo is visible.
        PartDefinition Tail = Metal.addOrReplaceChild("Tail", CubeListBuilder.create()
                .texOffs(143, 18).addBox(-3.0F, -37.0F, 78.0F, 6.0F, 6.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.ZERO);
        Tail.addOrReplaceChild("logo_0", CubeListBuilder.create()
                .texOffs(146, 0).addBox(0.0F, -76.0F, 50.0F, 1.0F, 29.0F, 29.0F, new CubeDeformation(0.05F))
                .texOffs(146, 0).mirror().addBox(-1.0F, -76.0F, 50.0F, 1.0F, 29.0F, 29.0F, new CubeDeformation(0.05F)).mirror(false), PartPose.ZERO);
        Tail.addOrReplaceChild("logo_1", CubeListBuilder.create()
                .texOffs(0, 15).addBox(0.0F, -76.0F, 50.0F, 1.0F, 29.0F, 29.0F, new CubeDeformation(0.05F))
                .texOffs(0, 15).mirror().addBox(-1.0F, -76.0F, 50.0F, 1.0F, 29.0F, 29.0F, new CubeDeformation(0.05F)).mirror(false), PartPose.ZERO);
        Tail.addOrReplaceChild("logo_2", CubeListBuilder.create()
                .texOffs(102, 30).addBox(0.0F, -76.0F, 50.0F, 1.0F, 29.0F, 29.0F, new CubeDeformation(0.05F))
                .texOffs(102, 30).mirror().addBox(-1.0F, -76.0F, 50.0F, 1.0F, 29.0F, 29.0F, new CubeDeformation(0.05F)).mirror(false), PartPose.ZERO);
        Tail.addOrReplaceChild("logo_3", CubeListBuilder.create()
                .texOffs(178, 30).addBox(0.0F, -76.0F, 50.0F, 1.0F, 29.0F, 29.0F, new CubeDeformation(0.05F))
                .texOffs(178, 30).mirror().addBox(-1.0F, -76.0F, 50.0F, 1.0F, 29.0F, 29.0F, new CubeDeformation(0.05F)).mirror(false), PartPose.ZERO);
        Tail.addOrReplaceChild("logo_4", CubeListBuilder.create()
                .texOffs(172, 89).addBox(0.0F, -76.0F, 50.0F, 1.0F, 29.0F, 29.0F, new CubeDeformation(0.05F))
                .texOffs(172, 89).mirror().addBox(-1.0F, -76.0F, 50.0F, 1.0F, 29.0F, 29.0F, new CubeDeformation(0.05F)).mirror(false), PartPose.ZERO);
        Tail.addOrReplaceChild("logo_5", CubeListBuilder.create()
                .texOffs(0, 106).addBox(0.0F, -76.0F, 50.0F, 1.0F, 29.0F, 29.0F, new CubeDeformation(0.05F))
                .texOffs(0, 106).mirror().addBox(-1.0F, -76.0F, 50.0F, 1.0F, 29.0F, 29.0F, new CubeDeformation(0.05F)).mirror(false), PartPose.ZERO);
        Metal.addOrReplaceChild("Gear", CubeListBuilder.create()
                .texOffs(0, 15).addBox(-1.0F, -13.0F, -75.0F, 2.0F, 9.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(218, 6).addBox(-3.0F, -4.0F, -76.0F, 2.0F, 4.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(218, 6).mirror().addBox(1.0F, -4.0F, -76.0F, 2.0F, 4.0F, 4.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(134, 19).addBox(15.0F, -15.0F, 15.0F, 2.0F, 10.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(80, 0).addBox(12.0F, -6.0F, 13.0F, 3.0F, 6.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(80, 0).addBox(17.0F, -6.0F, 13.0F, 3.0F, 6.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(134, 19).mirror().addBox(-17.0F, -15.0F, 15.0F, 2.0F, 10.0F, 2.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(80, 0).mirror().addBox(-15.0F, -6.0F, 13.0F, 3.0F, 6.0F, 6.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(80, 0).mirror().addBox(-20.0F, -6.0F, 13.0F, 3.0F, 6.0F, 6.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.ZERO);

        return LayerDefinition.create(meshdefinition, 256, 256);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {
        super.setupAnim(state);
        setLogo(DEFAULT_LOGO);
    }
}
