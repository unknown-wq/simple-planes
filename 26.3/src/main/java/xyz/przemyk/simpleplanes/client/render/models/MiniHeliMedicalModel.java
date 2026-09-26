package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

import java.util.Map;

import static java.util.Map.entry;

/**
 * Mini helicopter, air-ambulance material layer: the same {@link MiniHeliAirframe} geometry as
 * {@link MiniHeliModel}, with every cube's UV net laid out once in {@link #TEXTURE} (128x64) instead of tiling
 * a block texture. The livery is white, body, pillars and roof alike, with a red cheat line, high-visibility
 * yellow-green bands and an invented medical mark (a white cross on a green square) on both sides of the rear pod,
 * on the roof and on the belly. Pair it with the unchanged {@link MiniHeliMetalModel}, {@link MiniHeliRotorModel}
 * and {@link MiniHeliGlassModel}.
 *
 * <p>Default render type ({@code entityCutout}), like {@link MiniHeliModel}. The texOffs table is generated
 * with the texture.
 */
public class MiniHeliMedicalModel extends EntityModel<PlaneRenderState> {

    /** The livery texture; the renderer passes it instead of the material's block texture. */
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/plane_upgrades/mini_heli_medical.png");

    private static final Map<String, int[]> SKIN_UV = Map.ofEntries(
            entry("hull", new int[]{0, 0}),
            entry("nose", new int[]{0, 30}),
            entry("chin", new int[]{98, 0}),
            entry("roof", new int[]{56, 28}),
            entry("a_pillar", new int[]{14, 0}),
            entry("b_pillar", new int[]{124, 0}),
            entry("pod", new int[]{50, 0}),
            entry("boom", new int[]{68, 0}),
            entry("fin_lo", new int[]{0, 0}),
            entry("fin_hi", new int[]{82, 0}),
            entry("ventral", new int[]{116, 9}),
            entry("stab", new int[]{98, 9}));

    public MiniHeliMedicalModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        return LayerDefinition.create(MiniHeliAirframe.create(MiniHeliAirframe.table(SKIN_UV)), 128, 64);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
