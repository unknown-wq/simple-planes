package xyz.przemyk.simpleplanes.entities;

/**
 * The seats and the hull of one airliner size, shared by the entity (rider placement, click-to-board, hitboxes)
 * and the model (seat cubes). Seat 0 is the captain's (left, the pilot), 1 the first officer's, then the cabin
 * row by row from the front, left to right.
 *
 * <p>Model px use the model convention (+x left, nose at -z); entity blocks use the entity's (+x left, nose
 * at +z), related by {@code X = x / 16} and {@code Z = -(6 + z) / 16} (AIRLINER-MODEL.md).
 */
public final class AirlinerLayout {

    public static final int PILOT = 0;
    public static final int FIRST_OFFICER = 1;
    public static final int FIRST_CABIN_SEAT = 2;
    /** Feet point height of a cabin seat before the {@link #lift()}, blocks: hips on the cushion 4 px above the floor. */
    public static final float CABIN_Y = 0.5625F;
    /** Feet point height of a crew seat before the {@link #lift()}, blocks: 2 px higher, eye in the lower windscreen. */
    public static final float CREW_Y = 0.6875F;
    /** Hull box, blocks: belly (the same for every size), crown before the {@link #lift()}, and the nose tip. */
    public static final float HULL_Y0 = 0.8125F, HULL_Y1 = 3.1875F, HULL_NOSE = 6.0625F;
    /** Most seats of any size: the entity defines this many synched seat slots. */
    public static final int MAX_SEATS = 22;

    /** Mini airliner: 58 px wide, 2 + 20 seats, two by two either side of the aisle; cabin 16 px above the base. */
    public static final AirlinerLayout WIDE = new AirlinerLayout(16, new int[]{11, -11}, -66,
        new int[]{-46, -28, -10, 8, 26}, new int[]{21, 9, -9, -21}, 3.25F,
        1.8125F, 5.625F, new float[]{4.5F, 1.5F, -1.5F, -4.5F});
    /** Regional airliner: 40 px wide, 2 + 12 seats, one either side of the aisle; cabin 8 px above the base. */
    public static final AirlinerLayout REGIONAL = new AirlinerLayout(8, new int[]{9, -9}, -66,
        new int[]{-50, -33, -16, 1, 18, 35}, new int[]{12, -12}, 3.3125F,
        1.25F, 5.875F, new float[]{4.75F, 2.25F, -2.25F, -4.75F});

    private final int lift;
    private final int[] cockpitX;
    private final int cockpitRowZ;
    private final int[] rowZ;
    private final int[] abreastX;
    private final float cockpitZ;
    private final float hullHalfWidth;
    private final float hullTail;
    private final float[] partStations;

    private AirlinerLayout(int lift, int[] cockpitX, int cockpitRowZ, int[] rowZ, int[] abreastX, float cockpitZ,
                           float hullHalfWidth, float hullTail, float[] partStations) {
        this.lift = lift;
        this.cockpitX = cockpitX;
        this.cockpitRowZ = cockpitRowZ;
        this.rowZ = rowZ;
        this.abreastX = abreastX;
        this.cockpitZ = cockpitZ;
        this.hullHalfWidth = hullHalfWidth;
        this.hullTail = hullTail;
        this.partStations = partStations;
    }

    /**
     * Model px by which the cabin, everything above it and the seats sit above the base cross-section; the keel,
     * wings, engines and gear do not move, the lower lobe grows by this much.
     */
    public int lift() {
        return lift;
    }

    /** Feet point height of a cabin seat, blocks. */
    public float cabinY() {
        return CABIN_Y + lift / 16.0F;
    }

    /** Top of the hull box (the crown), blocks. */
    public float hullTop() {
        return HULL_Y1 + lift / 16.0F;
    }

    public int count() {
        return FIRST_CABIN_SEAT + rowZ.length * abreastX.length;
    }

    /** Cabin seats per row. */
    public int abreast() {
        return abreastX.length;
    }

    /** Clicks at or ahead of this entity z (blocks) are on the cockpit or the nose. */
    public float cockpitZ() {
        return cockpitZ;
    }

    /** Half width of the fuselage, blocks. */
    public float hullHalfWidth() {
        return hullHalfWidth;
    }

    /** Distance from the entity origin back to the tail tip, blocks. */
    public float hullTail() {
        return hullTail;
    }

    public int partCount() {
        return partStations.length;
    }

    /** Entity z (blocks) of {@link AirlinerPartEntity} {@code station}, nose to tail. */
    public float partStation(int station) {
        return partStations[station];
    }

    public static boolean isCockpit(int seat) {
        return seat == PILOT || seat == FIRST_OFFICER;
    }

    /** Model x of the seat's centre line, px. */
    public int modelX(int seat) {
        return isCockpit(seat) ? cockpitX[seat] : abreastX[(seat - FIRST_CABIN_SEAT) % abreastX.length];
    }

    /** Model z of the rider's feet point, px. */
    public int modelZ(int seat) {
        return isCockpit(seat) ? cockpitRowZ : rowZ[(seat - FIRST_CABIN_SEAT) / abreastX.length];
    }

    /** Entity-space y of the feet point, blocks. */
    public float y(int seat) {
        return (isCockpit(seat) ? CREW_Y : CABIN_Y) + lift / 16.0F;
    }

    /** Entity-space x of the feet point, blocks. */
    public float x(int seat) {
        return modelX(seat) / 16.0F;
    }

    /** Entity-space z of the feet point, blocks (+ towards the nose). */
    public float z(int seat) {
        return -(6 + modelZ(seat)) / 16.0F;
    }

    /** "captain", "first officer", or the cabin row and letter from the left, e.g. "row 3B". */
    public String seatName(int seat) {
        if (seat < 0) {
            return "none";
        }
        if (seat == PILOT) {
            return "captain";
        }
        if (seat == FIRST_OFFICER) {
            return "first officer";
        }
        int cabin = seat - FIRST_CABIN_SEAT;
        return "row " + (cabin / abreastX.length + 1) + (char) ('A' + cabin % abreastX.length);
    }
}
