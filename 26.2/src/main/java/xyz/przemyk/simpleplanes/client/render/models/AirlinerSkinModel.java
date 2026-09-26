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
 * {@link AirlinerModel}, with every cube's UV net laid out once in {@link #TEXTURE} (512x512) instead of tiling a
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
            entry("fuselage_body", new int[]{0, 0}),
            entry("fuselage_shoulder_top", new int[]{171, 15}),
            entry("fuselage_crown", new int[]{0, 149}),
            entry("fuselage_shoulder_bottom", new int[]{171, 155}),
            entry("fuselage_keel", new int[]{0, 281}),
            entry("fuselage_fairing", new int[]{354, 0}),
            entry("nose_1", new int[]{237, 42}),
            entry("nose_2", new int[]{448, 75}),
            entry("nose_3", new int[]{274, 0}),
            entry("nose_4", new int[]{23, 0}),
            entry("tail_1", new int[]{354, 50}),
            entry("tail_2", new int[]{445, 0}),
            entry("tail_3", new int[]{73, 78}),
            entry("fin_fillet", new int[]{354, 17}),
            entry("fin_1", new int[]{0, 48}),
            entry("fin_2", new int[]{242, 0}),
            entry("fin_3", new int[]{73, 0}),
            entry("fin_4", new int[]{0, 0}),
            entry("fin_5", new int[]{179, 0}),
            entry("fin_6", new int[]{101, 0}),
            entry("wing_1", new int[]{0, 0}),
            entry("wing_2", new int[]{179, 0}),
            entry("wing_3", new int[]{179, 42}),
            entry("wing_4", new int[]{41, 48}),
            entry("wing_5", new int[]{427, 50}),
            entry("wing_6", new int[]{179, 78}),
            entry("stab_1", new int[]{409, 75}),
            entry("stab_2", new int[]{354, 0}),
            entry("stab_3", new int[]{309, 0}),
            entry("stab_4", new int[]{242, 0}));

    public AirlinerSkinModel(ModelPart root) {
        super(root, RenderTypes::entityCutoutCull);
    }

    public static LayerDefinition createBodyLayer() {
        return LayerDefinition.create(AirlinerAirframe.create(AirlinerAirframe.table(SKIN_UV)), 512, 512);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
