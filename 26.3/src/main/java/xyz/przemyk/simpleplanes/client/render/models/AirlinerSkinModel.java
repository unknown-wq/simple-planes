package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

import java.util.Map;

import static java.util.Map.entry;

/**
 * Mini airliner, metal-skinned material layer: the same {@link AirlinerAirframe} geometry as
 * {@link AirlinerModel}, with every cube's UV net laid out once in {@link #TEXTURE} (1024x1024) instead of tiling a
 * block texture. The skin is a white-and-aluminium airliner livery: white upper fuselage and fin, a blue cheat
 * line that continues the window belt's around the nose and tail, an aluminium belly, wings and stabilisers,
 * panel lines and a grey radome. Pair it with {@link AirlinerMetalModel} and {@link AirlinerFanModel} as usual.
 *
 * <p>Rendered with {@link RenderTypes#entityCutoutCull} for the same reason as {@link AirlinerModel}: the
 * riders' eyes are inside the fuselage. The texOffs table is generated with the texture.
 */
public class AirlinerSkinModel extends EntityModel<PlaneRenderState> {

    /** The skin texture; the renderer passes it instead of the material's block texture. */
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/plane_upgrades/airliner_skin.png");

    private static final Map<String, int[]> SKIN_UV = Map.ofEntries(
            entry("body_lower", new int[]{243, 0}),
            entry("body_upper", new int[]{257, 137}),
            entry("hull_low", new int[]{622, 137}),
            entry("hull_high", new int[]{0, 144}),
            entry("fuselage_shoulder_top", new int[]{0, 0}),
            entry("fuselage_crown_low", new int[]{608, 0}),
            entry("fuselage_crown", new int[]{231, 268}),
            entry("fuselage_shoulder_bottom", new int[]{498, 266}),
            entry("fuselage_keel", new int[]{0, 272}),
            entry("fuselage_fairing", new int[]{484, 0}),
            entry("nose_1", new int[]{831, 40}),
            entry("nose_2", new int[]{0, 53}),
            entry("nose_3", new int[]{484, 56}),
            entry("nose_4", new int[]{908, 78}),
            entry("nose_5", new int[]{484, 81}),
            entry("nose_6", new int[]{960, 0}),
            entry("nose_7", new int[]{700, 22}),
            entry("tail_1", new int[]{831, 0}),
            entry("tail_2", new int[]{243, 46}),
            entry("tail_3", new int[]{831, 75}),
            entry("tail_4", new int[]{0, 0}),
            entry("fin_fillet", new int[]{0, 22}),
            entry("fin_1", new int[]{657, 40}),
            entry("fin_2", new int[]{78, 0}),
            entry("fin_3", new int[]{312, 0}),
            entry("fin_4", new int[]{243, 0}),
            entry("fin_5", new int[]{637, 0}),
            entry("fin_6", new int[]{78, 0}),
            entry("wing_1", new int[]{0, 0}),
            entry("wing_2", new int[]{243, 0}),
            entry("wing_3", new int[]{637, 0}),
            entry("wing_4", new int[]{926, 45}),
            entry("wing_5", new int[]{952, 17}),
            entry("wing_6", new int[]{243, 83}),
            entry("stab_1", new int[]{577, 61}),
            entry("stab_2", new int[]{484, 24}),
            entry("stab_3", new int[]{78, 34}),
            entry("stab_4", new int[]{507, 0}),
            entry("pillar_0", new int[]{78, 0}),
            entry("pillar_1", new int[]{93, 0}),
            entry("pillar_2", new int[]{700, 0}),
            entry("pillar_3", new int[]{110, 0}),
            entry("pillar_4", new int[]{243, 0}),
            entry("pillar_5", new int[]{266, 0}),
            entry("pillar_6", new int[]{312, 0}),
            entry("pillar_7", new int[]{340, 0}),
            entry("pillar_8", new int[]{484, 0}),
            entry("pillar_9", new int[]{656, 0}),
            entry("pillar_10", new int[]{700, 0}),
            entry("pillar_11", new int[]{484, 0}));

    public AirlinerSkinModel(ModelPart root) {
        super(root, RenderTypes::entityCutoutCull);
    }

    public static LayerDefinition createBodyLayer() {
        return LayerDefinition.create(AirlinerAirframe.create(AirlinerAirframe.table(SKIN_UV)), 1024, 1024);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
