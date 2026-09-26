package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;

import java.util.Map;

/**
 * The mini helicopter's material airframe: cabin tub, nose, rear pod, tail boom, fin, ventral fin and
 * stabiliser. It is the single source of that geometry for both material layers, {@link MiniHeliModel}
 * (tiled block texture) and {@link MiniHeliMedicalModel} (painted air-ambulance livery), which differ only in
 * where each cube's UV net starts. Every cube is named; a {@link UvLayout} maps the name to its
 * {@code texOffs}.
 *
 * <p>Nose points to -Z; the bottom of the skids is at local y = 0 under the root part {@code MiniHeli} at
 * y = 24, and the main rotor axis is at local z = 4 (see MINI-HELI-MODEL.md).
 */
final class MiniHeliAirframe {

    /** Where each named cube's UV net starts in a layer's texture. */
    interface UvLayout {
        int[] texOffs(String cube);
    }

    /** A layout backed by a table of {@code name -> {u, v}}; an unknown name fails loudly. */
    static UvLayout table(Map<String, int[]> offsets) {
        return cube -> {
            int[] uv = offsets.get(cube);
            if (uv == null) {
                throw new IllegalArgumentException("no texOffs for mini helicopter cube " + cube);
            }
            return uv;
        };
    }

    private MiniHeliAirframe() {}

    static MeshDefinition create(UvLayout uv) {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition MiniHeli = partdefinition.addOrReplaceChild("MiniHeli", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Cabin tub under the bubble (the bubble itself is glass, in MiniHeliMetalModel) and the nose under the
        // bubble's front bulge.
        CubeListBuilder cabin = CubeListBuilder.create();
        box(cabin, uv, "hull", -8, -12, -16, 16, 7, 18);
        box(cabin, uv, "nose", -7, -12, -19, 14, 6, 3);
        MiniHeli.addOrReplaceChild("Cabin", cabin, PartPose.ZERO);

        // Engine bay behind the cabin; the engine sits on top of it and the mast rises from the engine.
        CubeListBuilder pod = CubeListBuilder.create();
        box(pod, uv, "pod", -6, -15, 2, 12, 9, 8);
        MiniHeli.addOrReplaceChild("RearPod", pod, PartPose.ZERO);

        // Thin boom, swept fin in two steps, a ventral fin that guards the tail rotor, and a small stabiliser
        // passing through the boom (offset half a pixel so no face is coplanar with the boom's).
        CubeListBuilder tail = CubeListBuilder.create();
        box(tail, uv, "boom", -1, -15, 10, 2, 2, 26);
        box(tail, uv, "fin_lo", -0.5F, -18, 31, 1, 4, 6);
        box(tail, uv, "fin_hi", -0.5F, -22, 33, 1, 4, 4);
        box(tail, uv, "ventral", -0.5F, -13, 33, 1, 3, 4);
        box(tail, uv, "stab", -4, -14.5F, 23, 8, 1, 3);
        MiniHeli.addOrReplaceChild("Tail", tail, PartPose.ZERO);

        return meshdefinition;
    }

    private static void box(CubeListBuilder builder, UvLayout uv, String name,
                            float x, float y, float z, float w, float h, float d) {
        int[] o = uv.texOffs(name);
        builder.texOffs(o[0], o[1]).addBox(x, y, z, w, h, d, CubeDeformation.NONE);
    }
}
