package xyz.przemyk.simpleplanes.api.dispatch;

/**
 * What counts as a landing zone. {@link #DEFAULT} suits the mini helicopter.
 *
 * @param footprintRadius half-size of the square that must be flat: (2r+1) x (2r+1) columns
 * @param maxSpread       largest height difference allowed inside the footprint
 * @param ringWidth       width of the ring around the footprint that must be no more than one block high
 * @param clearHeight     height above the zone the approach path must be clear to, further out
 * @param approachLength  how far out an approach sector is checked
 * @param minClearSectors how many of the 8 approach sectors must be clear
 * @param maxElevation    highest surface y accepted (the airframe's ceiling minus its hover height)
 * @param budgetPerTick   column reads per tick for the incremental search
 */
public record LandingZoneSpec(int footprintRadius, int maxSpread, int ringWidth, int clearHeight,
                              int approachLength, int minClearSectors, int maxElevation, int budgetPerTick) {

    public static final LandingZoneSpec DEFAULT = new LandingZoneSpec(2, 1, 2, 16, 24, 1, 120, 2048);

    public LandingZoneSpec {
        footprintRadius = clamp(footprintRadius, 1, 4);
        maxSpread = clamp(maxSpread, 0, 3);
        ringWidth = clamp(ringWidth, 0, 4);
        clearHeight = clamp(clearHeight, 4, 48);
        approachLength = clamp(approachLength, 8, 48);
        minClearSectors = clamp(minClearSectors, 1, 8);
        budgetPerTick = clamp(budgetPerTick, 64, 65536);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
