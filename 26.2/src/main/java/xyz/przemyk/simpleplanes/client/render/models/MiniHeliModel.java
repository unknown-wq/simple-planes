package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

import java.util.Map;

import static java.util.Map.entry;

/**
 * Mini helicopter, material layer: the {@link MiniHeliAirframe} geometry (cabin tub, nose, rear pod, tail
 * boom, fins, stabiliser) textured with the block texture of the aircraft's material, tiled 1 texel per pixel
 * like {@link PlaneModel}. {@link MiniHeliMedicalModel} is the same airframe in an air-ambulance livery.
 *
 * <p>Uses the default {@code EntityModel} render type ({@code entityCutout}, not culled): no rider's eye is
 * inside any of these cubes. See MINI-HELI-MODEL.md.
 */
public class MiniHeliModel extends EntityModel<PlaneRenderState> {

    /** texOffs into the tiled 16x16 block texture; they only pick which part of the plank pattern shows. */
    private static final Map<String, int[]> WOOD_UV = Map.ofEntries(
            entry("hull", new int[]{0, 0}), entry("nose", new int[]{4, 2}), entry("pod", new int[]{8, 4}),
            entry("boom", new int[]{2, 6}), entry("fin_lo", new int[]{6, 8}), entry("fin_hi", new int[]{10, 3}),
            entry("ventral", new int[]{12, 10}), entry("stab", new int[]{3, 12}));

    public MiniHeliModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        return LayerDefinition.create(MiniHeliAirframe.create(MiniHeliAirframe.table(WOOD_UV)), 16, 16);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
