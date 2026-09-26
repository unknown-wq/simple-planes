package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;

import java.util.Map;

/**
 * The mini airliner's airframe: fuselage, nose, tail cone, wings, stabilisers and fin. It is the single source
 * of the geometry for both material layers, {@link AirlinerModel} (tiled block texture) and
 * {@link AirlinerSkinModel} (painted metal skin), which differ only in where each cube's UV net starts. Every
 * cube is named; a {@link UvLayout} maps the name to its {@code texOffs}.
 *
 * <p>Nose points to -Z, the ground contact of the landing gear is at local y = 0 under the root part
 * {@code Airliner} at y = 24 (see AIRLINER-MODEL.md). A mirrored right-hand cube uses the same name, and so
 * the same net, as its left-hand twin.
 */
final class AirlinerAirframe {

    /** Wing dihedral, radians (5 degrees). */
    static final float WING_DIHEDRAL = 0.0873F;
    /** Horizontal stabiliser dihedral, radians (7 degrees). */
    static final float STAB_DIHEDRAL = 0.1222F;

    /** Where each named cube's UV net starts in a layer's texture. */
    interface UvLayout {
        int[] texOffs(String cube);
    }

    /** A layout backed by a table of {@code name -> {u, v}}; an unknown name fails loudly. */
    static UvLayout table(Map<String, int[]> offsets) {
        return cube -> {
            int[] uv = offsets.get(cube);
            if (uv == null) {
                throw new IllegalArgumentException("no texOffs for airliner cube " + cube);
            }
            return uv;
        };
    }

    private AirlinerAirframe() {}

    static MeshDefinition create(UvLayout uv) {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Airliner = partdefinition.addOrReplaceChild("Airliner", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Cross-section, widest to narrowest: body 26 x 22, shoulders 24 wide, crown and keel 18 wide.
        CubeListBuilder fuselage = CubeListBuilder.create();
        box(fuselage, uv, "fuselage_body", -13, -38, -80, 26, 22, 126);
        box(fuselage, uv, "fuselage_shoulder_top", -12, -40, -76, 24, 2, 134);
        box(fuselage, uv, "fuselage_crown", -9, -41, -72, 18, 1, 130);
        box(fuselage, uv, "fuselage_shoulder_bottom", -12, -16, -80, 24, 2, 126);
        box(fuselage, uv, "fuselage_keel", -9, -14, -80, 18, 1, 126);
        box(fuselage, uv, "fuselage_fairing", -11, -14, -18, 22, 3, 46);
        Airliner.addOrReplaceChild("Fuselage", fuselage, PartPose.ZERO);

        CubeListBuilder nose = CubeListBuilder.create();
        box(nose, uv, "nose_1", -12, -30, -85, 24, 16, 5);
        box(nose, uv, "nose_2", -10, -28, -89, 20, 13, 4);
        box(nose, uv, "nose_3", -7, -26, -92, 14, 10, 3);
        box(nose, uv, "nose_4", -4, -24, -94, 8, 6, 2);
        Airliner.addOrReplaceChild("Nose", nose, PartPose.ZERO);

        // Tail cone: the top line stays level while the belly sweeps up towards the APU.
        CubeListBuilder tail = CubeListBuilder.create();
        box(tail, uv, "tail_1", -12, -38, 46, 24, 21, 12);
        box(tail, uv, "tail_2", -10, -40, 58, 20, 19, 10);
        box(tail, uv, "tail_3", -7, -39, 68, 14, 13, 10);
        Airliner.addOrReplaceChild("TailCone", tail, PartPose.ZERO);

        // Low swept wings: six chord steps each, pivoted at the fuselage side and tilted up by the dihedral.
        float[][] wing = {
                {0, -3, -16, 14, 3, 44}, {14, -3, -10, 12, 3, 38}, {26, -2, -4, 12, 2, 33},
                {38, -2, 2, 12, 2, 27}, {50, -2, 8, 12, 2, 22}, {62, -2, 14, 10, 2, 16}};
        Airliner.addOrReplaceChild("wing_left", side(uv, "wing_", wing, false),
                PartPose.offsetAndRotation(12.0F, -14.0F, 0.0F, 0.0F, 0.0F, -WING_DIHEDRAL));
        Airliner.addOrReplaceChild("wing_right", side(uv, "wing_", wing, true),
                PartPose.offsetAndRotation(-12.0F, -14.0F, 0.0F, 0.0F, 0.0F, WING_DIHEDRAL));

        // Swept horizontal stabilisers, four steps each, with a little dihedral.
        float[][] stab = {{0, -2, 56, 10, 2, 18}, {10, -2, 61, 8, 2, 14}, {18, -2, 66, 8, 2, 10}, {26, -2, 70, 6, 2, 7}};
        Airliner.addOrReplaceChild("stab_left", side(uv, "stab_", stab, false),
                PartPose.offsetAndRotation(6.0F, -25.0F, 0.0F, 0.0F, 0.0F, -STAB_DIHEDRAL));
        Airliner.addOrReplaceChild("stab_right", side(uv, "stab_", stab, true),
                PartPose.offsetAndRotation(-6.0F, -25.0F, 0.0F, 0.0F, 0.0F, STAB_DIHEDRAL));

        // Tall single fin: a dorsal fillet running forward along the crown and six swept steps.
        CubeListBuilder fin = CubeListBuilder.create();
        box(fin, uv, "fin_fillet", -1, -44, 30, 2, 4, 14);
        box(fin, uv, "fin_1", -2, -47, 44, 4, 8, 32);
        box(fin, uv, "fin_2", -1, -53, 50, 2, 6, 27);
        box(fin, uv, "fin_3", -1, -59, 55, 2, 6, 23);
        box(fin, uv, "fin_4", -1, -65, 60, 2, 6, 18);
        box(fin, uv, "fin_5", -1, -71, 65, 2, 6, 14);
        box(fin, uv, "fin_6", -1, -76, 69, 2, 5, 10);
        Airliner.addOrReplaceChild("Fin", fin, PartPose.ZERO);

        return meshdefinition;
    }

    /** One side of a wing or stabiliser; the right-hand side is the left one mirrored in x. */
    private static CubeListBuilder side(UvLayout uv, String prefix, float[][] steps, boolean right) {
        CubeListBuilder b = CubeListBuilder.create();
        for (int i = 0; i < steps.length; i++) {
            float[] s = steps[i];
            int[] t = uv.texOffs(prefix + (i + 1));
            if (right) {
                b.texOffs(t[0], t[1]).mirror().addBox(-s[0] - s[3], s[1], s[2], s[3], s[4], s[5], CubeDeformation.NONE).mirror(false);
            } else {
                b.texOffs(t[0], t[1]).addBox(s[0], s[1], s[2], s[3], s[4], s[5], CubeDeformation.NONE);
            }
        }
        return b;
    }

    private static void box(CubeListBuilder b, UvLayout uv, String cube, float x, float y, float z, float w, float h, float d) {
        int[] t = uv.texOffs(cube);
        b.texOffs(t[0], t[1]).addBox(x, y, z, w, h, d, CubeDeformation.NONE);
    }
}
