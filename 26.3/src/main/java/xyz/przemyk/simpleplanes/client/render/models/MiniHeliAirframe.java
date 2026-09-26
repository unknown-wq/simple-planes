package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;

import java.util.Map;

/**
 * The mini helicopter's material airframe: cabin tub, nose and chin, door pillars and roof, rear pod, tail
 * boom, fin, ventral fin and stabiliser. It is the single source of that geometry for both material layers, {@link MiniHeliModel}
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

    /** How far the door pillars stand proud of the glass, px. */
    static final float PILLAR_GROW = 0.05F;

    private MiniHeliAirframe() {}

    static MeshDefinition create(UvLayout uv) {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition MiniHeli = partdefinition.addOrReplaceChild("MiniHeli", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Cabin: the tub carries the body up to the door sill (y = -17); a stepped nose and chin sit in front of it,
        // with the windscreen standing on the nose 2 px below the sill. Above the sill is a framed glasshouse (the
        // glass is in MiniHeliMetalModel): four door pillars and a roof over the pilot's head. The front of the
        // glasshouse is left roof-free, as eyebrow windows. The pillars are grown by PILLAR_GROW so their faces
        // never lie in the plane of the glass.
        CubeListBuilder cabin = CubeListBuilder.create();
        box(cabin, uv, "hull", -8, -17, -16, 16, 12, 18);
        box(cabin, uv, "nose", -7, -15, -19, 14, 9, 3);
        box(cabin, uv, "chin", -5, -13, -22, 10, 6, 3);
        box(cabin, uv, "roof", -8, -31, -10, 16, 3, 12);
        box(cabin, uv, "a_pillar", 7, -28, -16, 1, 11, 1, PILLAR_GROW);
        box(cabin, uv, "a_pillar", -8, -28, -16, 1, 11, 1, PILLAR_GROW);
        box(cabin, uv, "b_pillar", 7, -28, 1, 1, 11, 1, PILLAR_GROW);
        box(cabin, uv, "b_pillar", -8, -28, 1, 1, 11, 1, PILLAR_GROW);
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
        box(builder, uv, name, x, y, z, w, h, d, 0.0F);
    }

    private static void box(CubeListBuilder builder, UvLayout uv, String name,
                            float x, float y, float z, float w, float h, float d, float grow) {
        int[] o = uv.texOffs(name);
        builder.texOffs(o[0], o[1]).addBox(x, y, z, w, h, d, new CubeDeformation(grow));
    }
}
