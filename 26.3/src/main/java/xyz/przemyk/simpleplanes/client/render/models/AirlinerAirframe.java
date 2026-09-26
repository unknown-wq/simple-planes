package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.core.Direction;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The mini airliner's airframe: fuselage, window band, nose, tail cone, wings, stabilisers and fin. It is the
 * single source of the geometry for both material layers, {@link AirlinerModel} (tiled block texture) and
 * {@link AirlinerSkinModel} (painted metal skin), which differ only in where each cube's UV net starts. Every
 * cube is named; a {@link UvLayout} maps the name to its {@code texOffs}.
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

    /** Half width of the fuselage body, px. */
    static final int HALF_WIDTH = 29;
    /** Wing root pivot, px from the centre line; the wing reaches 76 px further out. */
    static final float WING_ROOT_X = 28.0F;
    /** Horizontal stabiliser pivot, px from the centre line. */
    static final float STAB_ROOT_X = 18.0F;
    /** Window band: local y of the lintel and of the sill. */
    static final int BAND_TOP = -37;
    static final int BAND_BOTTOM = -32;
    /** Window openings along z, [start, end) px: two cockpit windows, then one per seat row and one between rows. */
    static final int[][] WINDOWS = {
            {-76, -71}, {-69, -64},
            {-48, -44}, {-39, -35}, {-30, -26}, {-21, -17}, {-12, -8}, {-3, 1}, {6, 10}, {15, 19}, {24, 28}};
    /** The body runs from the windscreen to the tail cone. */
    static final int BODY_FRONT = -78;
    static final int BODY_REAR = 46;

    // Model-space directions: DOWN is the face at min y (the top), UP the one at max y, NORTH faces the nose.
    private static final Set<Direction> LOWER_BODY =
            EnumSet.of(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
    // no NORTH face either: the windscreen opens into the cockpit
    private static final Set<Direction> UPPER_BODY = EnumSet.of(Direction.DOWN, Direction.SOUTH, Direction.EAST, Direction.WEST);
    private static final Set<Direction> BELOW_CABIN = EnumSet.of(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
    private static final Set<Direction> ABOVE_CABIN = EnumSet.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
    private static final Set<Direction> NOSE = EnumSet.of(Direction.UP, Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.WEST);
    private static final Set<Direction> PILLAR = EnumSet.of(Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH);

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

        // Cross-section 58 x 38 px: flat sides from the window band down to the floor and up to the ceiling,
        // rounded by narrower steps above and below. Inner top faces below the cabin and inner bottom faces
        // above it are left out; only the floor (the lower shoulder) and the ceiling (the upper shoulder) show.
        CubeListBuilder fuselage = CubeListBuilder.create();
        faces(fuselage, uv, "body_lower", -29, -32, BODY_FRONT, 58, 12, 124, LOWER_BODY);
        faces(fuselage, uv, "body_upper", -29, -43, BODY_FRONT, 58, 6, 124, UPPER_BODY);
        faces(fuselage, uv, "hull_low", -28, -20, BODY_FRONT, 56, 4, 124, BELOW_CABIN);
        faces(fuselage, uv, "hull_high", -28, -46, BODY_FRONT, 56, 3, 124, ABOVE_CABIN);
        box(fuselage, uv, "fuselage_shoulder_top", -26, -48, -76, 52, 2, 138);
        box(fuselage, uv, "fuselage_crown_low", -22, -50, -72, 44, 2, 134);
        box(fuselage, uv, "fuselage_crown", -15, -51, -68, 30, 1, 130);
        box(fuselage, uv, "fuselage_shoulder_bottom", -26, -16, BODY_FRONT, 52, 2, 124);
        box(fuselage, uv, "fuselage_keel", -19, -14, BODY_FRONT, 38, 1, 124);
        box(fuselage, uv, "fuselage_fairing", -25, -14, -20, 50, 3, 52);
        Airliner.addOrReplaceChild("Fuselage", fuselage, PartPose.ZERO);

        // Window band: a pillar on each side between neighbouring windows, from the windscreen to the tail cone.
        CubeListBuilder band = CubeListBuilder.create();
        int z = BODY_FRONT;
        int height = BAND_BOTTOM - BAND_TOP;
        for (int i = 0; i <= WINDOWS.length; i++) {
            int end = i < WINDOWS.length ? WINDOWS[i][0] : BODY_REAR;
            String name = "pillar_" + i;
            faces(band, uv, name, HALF_WIDTH - 2, BAND_TOP, z, 2, height, end - z, PILLAR);
            faces(band, uv, name, -HALF_WIDTH, BAND_TOP, z, 2, height, end - z, PILLAR);
            if (i < WINDOWS.length) {
                z = WINDOWS[i][1];
            }
        }
        Airliner.addOrReplaceChild("WindowBand", band, PartPose.ZERO);

        // Nose: a raked windscreen and radome in steps down and forward; open at the back towards the cockpit.
        CubeListBuilder nose = CubeListBuilder.create();
        faces(nose, uv, "nose_1", -29, -44, -82, 58, 30, 4, NOSE);
        faces(nose, uv, "nose_2", -28, -40, -86, 56, 26, 4, NOSE);
        faces(nose, uv, "nose_3", -26, -35, -90, 52, 20, 4, NOSE);
        faces(nose, uv, "nose_4", -23, -33, -94, 46, 17, 4, NOSE);
        faces(nose, uv, "nose_5", -19, -30, -98, 38, 13, 4, NOSE);
        faces(nose, uv, "nose_6", -14, -27, -101, 28, 8, 3, NOSE);
        faces(nose, uv, "nose_7", -8, -25, -103, 16, 4, 2, NOSE);
        Airliner.addOrReplaceChild("Nose", nose, PartPose.ZERO);

        // Tail cone: the top line stays level while the belly sweeps up towards the APU.
        CubeListBuilder tail = CubeListBuilder.create();
        box(tail, uv, "tail_1", -27, -46, 46, 54, 29, 10);
        box(tail, uv, "tail_2", -22, -47, 56, 44, 26, 10);
        box(tail, uv, "tail_3", -15, -47, 66, 30, 21, 8);
        box(tail, uv, "tail_4", -9, -46, 74, 18, 15, 6);
        Airliner.addOrReplaceChild("TailCone", tail, PartPose.ZERO);

        // Low swept wings: six chord steps each, pivoted at the fuselage side and tilted up by the dihedral.
        float[][] wing = {
                {0, -3, -18, 14, 3, 49}, {14, -3, -10, 13, 3, 42}, {27, -3, -3, 13, 3, 36},
                {40, -2, 4, 12, 2, 30}, {52, -2, 11, 12, 2, 23}, {64, -2, 17, 12, 2, 18}};
        Airliner.addOrReplaceChild("wing_left", side(uv, "wing_", wing, false),
                PartPose.offsetAndRotation(WING_ROOT_X, -14.0F, 0.0F, 0.0F, 0.0F, -WING_DIHEDRAL));
        Airliner.addOrReplaceChild("wing_right", side(uv, "wing_", wing, true),
                PartPose.offsetAndRotation(-WING_ROOT_X, -14.0F, 0.0F, 0.0F, 0.0F, WING_DIHEDRAL));

        // Swept horizontal stabilisers, four steps each, with a little dihedral.
        float[][] stab = {{0, -2, 56, 12, 2, 20}, {12, -2, 62, 10, 2, 15}, {22, -2, 67, 10, 2, 11}, {32, -2, 72, 7, 2, 7}};
        Airliner.addOrReplaceChild("stab_left", side(uv, "stab_", stab, false),
                PartPose.offsetAndRotation(STAB_ROOT_X, -28.0F, 0.0F, 0.0F, 0.0F, -STAB_DIHEDRAL));
        Airliner.addOrReplaceChild("stab_right", side(uv, "stab_", stab, true),
                PartPose.offsetAndRotation(-STAB_ROOT_X, -28.0F, 0.0F, 0.0F, 0.0F, STAB_DIHEDRAL));

        // Tall single fin: a dorsal fillet running forward along the crown and six swept steps.
        CubeListBuilder fin = CubeListBuilder.create();
        box(fin, uv, "fin_fillet", -1, -55, 30, 2, 4, 14);
        box(fin, uv, "fin_1", -2, -58, 44, 4, 13, 32);
        box(fin, uv, "fin_2", -1, -64, 50, 2, 6, 27);
        box(fin, uv, "fin_3", -1, -70, 55, 2, 6, 23);
        box(fin, uv, "fin_4", -1, -76, 60, 2, 6, 18);
        box(fin, uv, "fin_5", -1, -82, 65, 2, 6, 14);
        box(fin, uv, "fin_6", -1, -87, 69, 2, 5, 10);
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

    /** A box that draws only the given faces. */
    private static void faces(CubeListBuilder b, UvLayout uv, String cube, float x, float y, float z, float w, float h, float d,
                              Set<Direction> visible) {
        int[] t = uv.texOffs(cube);
        b.texOffs(t[0], t[1]).addBox(x, y, z, w, h, d, visible);
    }
}
