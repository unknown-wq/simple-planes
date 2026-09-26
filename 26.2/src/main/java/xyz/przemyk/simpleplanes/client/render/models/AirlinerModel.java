package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

import java.util.Map;

import static java.util.Map.entry;

/**
 * Mini airliner, wooden material layer: the {@link AirlinerAirframe} geometry textured with the block texture
 * of the plane's material, tiled 1 texel per pixel like {@link PlaneModel}. {@link AirlinerSkinModel} is the
 * same airframe with a painted metal skin.
 *
 * <p>Rendered with {@link RenderTypes#entityCutoutCull}: every rider's eye is inside the fuselage, and only a
 * back-face-culled render type lets them see out through the walls. The default {@code entityCutout} of
 * {@code EntityModel} does not cull in 26.2. See AIRLINER-MODEL.md.
 */
public class AirlinerModel extends EntityModel<PlaneRenderState> {

    /** texOffs into the tiled 16x16 block texture; they only pick which part of the plank pattern shows. */
    private static final Map<String, int[]> WOOD_UV = Map.ofEntries(
            entry("fuselage_body", new int[]{0, 0}), entry("fuselage_shoulder_top", new int[]{3, 5}),
            entry("fuselage_crown", new int[]{7, 1}), entry("fuselage_shoulder_bottom", new int[]{3, 9}),
            entry("fuselage_keel", new int[]{7, 13}), entry("fuselage_fairing", new int[]{5, 3}),
            entry("nose_1", new int[]{2, 2}), entry("nose_2", new int[]{6, 6}),
            entry("nose_3", new int[]{10, 10}), entry("nose_4", new int[]{14, 14}),
            entry("tail_1", new int[]{4, 4}), entry("tail_2", new int[]{8, 2}), entry("tail_3", new int[]{12, 6}),
            entry("wing_1", new int[]{0, 0}), entry("wing_2", new int[]{4, 0}), entry("wing_3", new int[]{8, 0}),
            entry("wing_4", new int[]{12, 0}), entry("wing_5", new int[]{0, 8}), entry("wing_6", new int[]{4, 8}),
            entry("stab_1", new int[]{2, 6}), entry("stab_2", new int[]{6, 2}),
            entry("stab_3", new int[]{10, 6}), entry("stab_4", new int[]{14, 2}),
            entry("fin_fillet", new int[]{0, 4}), entry("fin_1", new int[]{4, 0}), entry("fin_2", new int[]{8, 4}),
            entry("fin_3", new int[]{12, 8}), entry("fin_4", new int[]{2, 12}), entry("fin_5", new int[]{6, 14}),
            entry("fin_6", new int[]{10, 2}));

    public AirlinerModel(ModelPart root) {
        super(root, RenderTypes::entityCutoutCull);
    }

    public static LayerDefinition createBodyLayer() {
        return LayerDefinition.create(AirlinerAirframe.create(AirlinerAirframe.table(WOOD_UV)), 16, 16);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
