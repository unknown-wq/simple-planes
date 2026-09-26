package xyz.przemyk.simpleplanes.api.dispatch;

/**
 * String codes used as {@link DispatchResult#reason()}, as the abort reason of an
 * {@link DispatchEvent.Type#ABORTED} event and as the cause of a {@link DispatchEvent.Type#LOST} one.
 * Plain strings so a caller that reaches the API by reflection can compare them without the class.
 * New codes may be added in later versions; treat unknown ones as a generic failure.
 */
public final class DispatchReasons {

    private DispatchReasons() {}

    // ---- refusals
    public static final String NOT_LOADED = "NOT_LOADED";
    public static final String NOT_DISPATCHABLE = "NOT_DISPATCHABLE";
    public static final String UNKNOWN_AIRCRAFT = "UNKNOWN_AIRCRAFT";
    public static final String UNKNOWN_PAD = "UNKNOWN_PAD";
    public static final String PAD_OCCUPIED = "PAD_OCCUPIED";
    public static final String BUSY = "BUSY";
    public static final String AIRBORNE = "AIRBORNE";
    public static final String TOO_MANY_FLIGHTS = "TOO_MANY_FLIGHTS";
    public static final String OUT_OF_WORLD = "OUT_OF_WORLD";
    public static final String NO_SEAT = "NO_SEAT";
    public static final String TOO_FAR = "TOO_FAR";
    public static final String INVALID_PASSENGER = "INVALID_PASSENGER";
    public static final String SPAWN_FAILED = "SPAWN_FAILED";

    // ---- aborts
    /** No landing zone within the search radius, or none found before the outbound hold timed out. */
    public static final String NO_LANDING_ZONE = "NO_LANDING_ZONE";
    /** The terrain on the way needs more height than the airframe can reach. */
    public static final String CEILING = "CEILING";
    /** The autopilot gave up on a leg (departure, transit or let-down timeout). */
    public static final String FLIGHT_FAILED = "FLIGHT_FAILED";
    /** The home pad was removed or stayed occupied. */
    public static final String HOME_PAD_UNAVAILABLE = "HOME_PAD_UNAVAILABLE";
    /** Came down in water at the target. */
    public static final String LANDED_IN_WATER = "LANDED_IN_WATER";
    /** {@code recall} was called. */
    public static final String RECALLED = "RECALLED";
    /** The return leg failed repeatedly; the aircraft landed at a landing zone short of home. */
    public static final String RETURN_FAILED = "RETURN_FAILED";

    // ---- lost
    public static final String DESTROYED = "DESTROYED";
    public static final String REMOVED = "REMOVED";
    public static final String CHANGED_DIMENSION = "CHANGED_DIMENSION";
    /** Could not be found at its last known position for {@code LOST_AFTER_TICKS}. */
    public static final String VANISHED = "VANISHED";
}
