package xyz.przemyk.simpleplanes.entities;

/**
 * The mini airliner's 22 seats, shared by the entity (rider placement, click-to-board) and the model (seat
 * cubes). Seat 0 is the captain's (left, the pilot), 1 the first officer's, 2 to 21 the cabin, row by row from
 * the front, left to right: two by two either side of the aisle.
 *
 * <p>Model px use the model convention (+x left, nose at -z); entity blocks use the entity's (+x left, nose
 * at +z), related by {@code X = x / 16} and {@code Z = -(6 + z) / 16} (AIRLINER-MODEL.md).
 */
public final class AirlinerSeats {

    public static final int COUNT = 22;
    public static final int PILOT = 0;
    public static final int FIRST_OFFICER = 1;
    public static final int FIRST_CABIN_SEAT = 2;
    /** Feet point height of a cabin seat, blocks: hips on the cushion 4 px above the floor; eye 2.18 (2.26 in game). */
    public static final float CABIN_Y = 0.5625F;
    /** Feet point height of a crew seat, blocks: 2 px higher; eye 2.31 (2.38 in game), in the lower windscreen's panes. */
    public static final float CREW_Y = 0.6875F;
    /** Clicks at or ahead of this entity z (blocks) are on the cockpit or the nose. */
    public static final float COCKPIT_Z = 3.25F;

    private static final int COCKPIT_ROW_Z = -66;
    private static final int[] COCKPIT_X = {11, -11};
    private static final int[] ROW_Z = {-46, -28, -10, 8, 26};
    private static final int[] ABREAST_X = {21, 9, -9, -21};

    private AirlinerSeats() {}

    public static boolean isCockpit(int seat) {
        return seat == PILOT || seat == FIRST_OFFICER;
    }

    /** Model x of the seat's centre line, px. */
    public static int modelX(int seat) {
        return isCockpit(seat) ? COCKPIT_X[seat] : ABREAST_X[(seat - FIRST_CABIN_SEAT) % ABREAST_X.length];
    }

    /** Model z of the rider's feet point, px. */
    public static int modelZ(int seat) {
        return isCockpit(seat) ? COCKPIT_ROW_Z : ROW_Z[(seat - FIRST_CABIN_SEAT) / ABREAST_X.length];
    }

    /** Entity-space y of the feet point, blocks. */
    public static float y(int seat) {
        return isCockpit(seat) ? CREW_Y : CABIN_Y;
    }

    /** Entity-space x of the feet point, blocks. */
    public static float x(int seat) {
        return modelX(seat) / 16.0F;
    }

    /** Entity-space z of the feet point, blocks (+ towards the nose). */
    public static float z(int seat) {
        return -(6 + modelZ(seat)) / 16.0F;
    }
}
