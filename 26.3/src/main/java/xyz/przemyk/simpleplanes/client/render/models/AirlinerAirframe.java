package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.core.Direction;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The airliner's airframe: fuselage, window band, nose, tail cone, wings, stabilisers and fin, for either size
 * ({@link AirlinerShape}). It is the single source of the geometry for both material layers, {@link AirlinerModel}
 * (tiled block texture) and {@link AirlinerSkinModel} (painted metal skin), which differ only in where each cube's
 * UV net starts. Every cube is named; a {@link UvLayout} maps the name to its {@code texOffs}.
 *
 * <p>Nose points to -Z, the ground contact of the landing gear is at local y = 0 under the root part
 * {@code Airliner} at y = 24 (see AIRLINER-MODEL.md). A mirrored right-hand cube uses the same name, and so
 * the same net, as its left-hand twin.
 *
 * <p>The cabin walls are open at window height: the body is two boxes, below and above the window band, and
 * the band is pillars between the windows. The faces a rider would see from inside (the top of the lower box,
 * the underside of the upper box) are left out, so riders still see out through the walls, while the pillars
 * frame the windows at eye level and onlookers see the seats through them.
 */
final class AirlinerAirframe {

    /** Wing dihedral, radians (5 degrees). */
    static final float WING_DIHEDRAL = 0.0873F;
    /** Horizontal stabiliser dihedral, radians (7 degrees). */
    static final float STAB_DIHEDRAL = 0.1222F;
    /** Height of the wing and stabiliser pivots. */
    static final float WING_Y = -14.0F;
    static final float STAB_Y = -28.0F;
    /** Window band: local y of the lintel and of the sill. */
    static final int BAND_TOP = -37;
    static final int BAND_BOTTOM = -32;

    // Model-space directions: DOWN is the face at min y (the top), UP the one at max y, NORTH faces the nose.
    private static final Set<Direction> LOWER_BODY =
            EnumSet.of(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
    // no NORTH face either: the windscreen opens into the cockpit
    private static final Set<Direction> UPPER_BODY = EnumSet.of(Direction.DOWN, Direction.SOUTH, Direction.EAST, Direction.WEST);
    private static final Set<Direction> BELOW_CABIN = EnumSet.of(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
    private static final Set<Direction> ABOVE_CABIN = EnumSet.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
    private static final Set<Direction> NOSE = EnumSet.of(Direction.UP, Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.WEST);
    private static final Set<Direction> PILLAR = EnumSet.of(Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH);

    /** Where each named cube's UV net starts in a layer's texture; the cube's box is passed for layouts that record it. */
    interface UvLayout {
        int[] texOffs(String cube, float x, float y, float z, float w, float h, float d);
    }

    /** A layout backed by a table of {@code name -> {u, v}}; an unknown name fails loudly. */
    static UvLayout table(Map<String, int[]> offsets) {
        return (cube, x, y, z, w, h, d) -> {
            int[] uv = offsets.get(cube);
            if (uv == null) {
                throw new IllegalArgumentException("no texOffs for airliner cube " + cube);
            }
            return uv;
        };
    }

    private AirlinerAirframe() {}

    static MeshDefinition create(AirlinerShape shape, UvLayout uv) {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Airliner = partdefinition.addOrReplaceChild("Airliner", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Cross-section 2 * halfWidth x 38 px: flat sides from the window band down to the floor and up to the
        // ceiling, rounded by narrower steps above and below. Inner top faces below the cabin and inner bottom faces
        // above it are left out; only the floor (the lower shoulder) and the ceiling (the upper shoulder) show.
        int hw = shape.halfWidth;
        int front = AirlinerShape.BODY_FRONT;
        int length = shape.bodyRear - front;
        int crownEnd = shape.bodyRear + 16;
        CubeListBuilder fuselage = CubeListBuilder.create();
        faces(fuselage, uv, "body_lower", -hw, -32, front, 2 * hw, 12, length, LOWER_BODY);
        faces(fuselage, uv, "body_upper", -hw, -43, front, 2 * hw, 6, length, UPPER_BODY);
        faces(fuselage, uv, "hull_low", -hw + 1, -20, front, 2 * hw - 2, 4, length, BELOW_CABIN);
        faces(fuselage, uv, "hull_high", -hw + 1, -46, front, 2 * hw - 2, 3, length, ABOVE_CABIN);
        box(fuselage, uv, "fuselage_shoulder_top", -shape.shoulder, -48, -76, 2 * shape.shoulder, 2, crownEnd + 76);
        box(fuselage, uv, "fuselage_crown_low", -shape.crownLow, -50, -72, 2 * shape.crownLow, 2, crownEnd + 72);
        box(fuselage, uv, "fuselage_crown", -shape.crown, -51, -68, 2 * shape.crown, 1, crownEnd + 68);
        box(fuselage, uv, "fuselage_shoulder_bottom", -shape.shoulder, -16, front, 2 * shape.shoulder, 2, length);
        box(fuselage, uv, "fuselage_keel", -shape.keel, -14, front, 2 * shape.keel, 1, length);
        int[] fairing = shape.fairing;
        box(fuselage, uv, "fuselage_fairing", -fairing[0], -14, fairing[1], 2 * fairing[0], 3, fairing[2]);
        Airliner.addOrReplaceChild("Fuselage", fuselage, PartPose.ZERO);

        // Window band: a pillar on each side between neighbouring windows, from the windscreen to the tail cone.
        CubeListBuilder band = CubeListBuilder.create();
        int[][] windows = shape.windows;
        int z = front;
        int height = BAND_BOTTOM - BAND_TOP;
        for (int i = 0; i <= windows.length; i++) {
            int end = i < windows.length ? windows[i][0] : shape.bodyRear;
            String name = "pillar_" + i;
            faces(band, uv, name, hw - 2, BAND_TOP, z, 2, height, end - z, PILLAR);
            faces(band, uv, name, -hw, BAND_TOP, z, 2, height, end - z, PILLAR);
            if (i < windows.length) {
                z = windows[i][1];
            }
        }
        Airliner.addOrReplaceChild("WindowBand", band, PartPose.ZERO);

        // Nose: a raked windscreen and radome in steps down and forward; open at the back towards the cockpit.
        CubeListBuilder nose = CubeListBuilder.create();
        for (int i = 0; i < shape.nose.length; i++) {
            int[] n = shape.nose[i];
            faces(nose, uv, "nose_" + (i + 1), n[0], n[1], n[2], n[3], n[4], n[5], NOSE);
        }
        Airliner.addOrReplaceChild("Nose", nose, PartPose.ZERO);

        // Tail cone: the top line stays level while the belly sweeps up towards the APU.
        CubeListBuilder tail = CubeListBuilder.create();
        int rear = shape.bodyRear;
        int[] cone = shape.tailCone;
        box(tail, uv, "tail_1", -cone[0], -46, rear, 2 * cone[0], 29, 10);
        box(tail, uv, "tail_2", -cone[1], -47, rear + 10, 2 * cone[1], 26, 10);
        box(tail, uv, "tail_3", -cone[2], -47, rear + 20, 2 * cone[2], 21, 8);
        box(tail, uv, "tail_4", -cone[3], -46, rear + 28, 2 * cone[3], 15, 6);
        Airliner.addOrReplaceChild("TailCone", tail, PartPose.ZERO);

        // Low swept wings in chord steps, pivoted at the fuselage side and tilted up by the dihedral.
        Airliner.addOrReplaceChild("wing_left", side(uv, "wing_", shape.wing, false),
                PartPose.offsetAndRotation(shape.wingRoot, WING_Y, 0.0F, 0.0F, 0.0F, -WING_DIHEDRAL));
        Airliner.addOrReplaceChild("wing_right", side(uv, "wing_", shape.wing, true),
                PartPose.offsetAndRotation(-shape.wingRoot, WING_Y, 0.0F, 0.0F, 0.0F, WING_DIHEDRAL));

        // Swept horizontal stabilisers, four steps each, with a little dihedral.
        Airliner.addOrReplaceChild("stab_left", side(uv, "stab_", shape.stab, false),
                PartPose.offsetAndRotation(shape.stabRoot, STAB_Y, 0.0F, 0.0F, 0.0F, -STAB_DIHEDRAL));
        Airliner.addOrReplaceChild("stab_right", side(uv, "stab_", shape.stab, true),
                PartPose.offsetAndRotation(-shape.stabRoot, STAB_Y, 0.0F, 0.0F, 0.0F, STAB_DIHEDRAL));

        // Tall single fin, the same on both sizes: a dorsal fillet running forward along the crown and six swept steps.
        CubeListBuilder fin = CubeListBuilder.create();
        int t = shape.tailShift();
        box(fin, uv, "fin_fillet", -1, -55, 30 + t, 2, 4, 14);
        box(fin, uv, "fin_1", -2, -58, 44 + t, 4, 13, 32);
        box(fin, uv, "fin_2", -1, -64, 50 + t, 2, 6, 27);
        box(fin, uv, "fin_3", -1, -70, 55 + t, 2, 6, 23);
        box(fin, uv, "fin_4", -1, -76, 60 + t, 2, 6, 18);
        box(fin, uv, "fin_5", -1, -82, 65 + t, 2, 6, 14);
        box(fin, uv, "fin_6", -1, -87, 69 + t, 2, 5, 10);
        Airliner.addOrReplaceChild("Fin", fin, PartPose.ZERO);

        return meshdefinition;
    }

    /** One side of a wing or stabiliser; the right-hand side is the left one mirrored in x. */
    private static CubeListBuilder side(UvLayout uv, String prefix, float[][] steps, boolean right) {
        CubeListBuilder b = CubeListBuilder.create();
        for (int i = 0; i < steps.length; i++) {
            float[] s = steps[i];
            int[] t = uv.texOffs(prefix + (i + 1), s[0], s[1], s[2], s[3], s[4], s[5]);
            if (right) {
                b.texOffs(t[0], t[1]).mirror().addBox(-s[0] - s[3], s[1], s[2], s[3], s[4], s[5], CubeDeformation.NONE).mirror(false);
            } else {
                b.texOffs(t[0], t[1]).addBox(s[0], s[1], s[2], s[3], s[4], s[5], CubeDeformation.NONE);
            }
        }
        return b;
    }

    private static void box(CubeListBuilder b, UvLayout uv, String cube, float x, float y, float z, float w, float h, float d) {
        int[] t = uv.texOffs(cube, x, y, z, w, h, d);
        b.texOffs(t[0], t[1]).addBox(x, y, z, w, h, d, CubeDeformation.NONE);
    }

    /** A box that draws only the given faces. */
    private static void faces(CubeListBuilder b, UvLayout uv, String cube, float x, float y, float z, float w, float h, float d,
                              Set<Direction> visible) {
        int[] t = uv.texOffs(cube, x, y, z, w, h, d);
        b.texOffs(t[0], t[1]).addBox(x, y, z, w, h, d, visible);
    }
}
