package xyz.przemyk.simpleplanes.autopilot;

/**
 * Every tunable number the flight director uses, in one place.
 *
 * <p>Units: distances and altitudes are blocks, speeds are blocks per tick (the plane's
 * {@code getDeltaMovement().length()}), angles are degrees, rates are degrees per tick.
 *
 * <p>The angular accelerations mirror {@code PlaneEntity#tickPitch/tickYaw/tickRoll}, which all
 * ramp their control rate by 0.5 deg/tick per tick. Keeping them here means the bang-bang
 * controllers brake at the right moment instead of oscillating.
 */
public final class AutopilotConfig {

    private AutopilotConfig() {}

    // ---- control surface authority (must match PlaneEntity's tickPitch/tickYaw/tickRoll) ----
    public static final double PITCH_ACCEL = 0.5;
    public static final double YAW_ACCEL = 0.5;
    public static final double ROLL_ACCEL = 0.5;

    public static final double PITCH_DEADBAND = 0.4;
    public static final double YAW_DEADBAND = 0.6;
    public static final double ROLL_DEADBAND = 1.5;

    // ---- attitude envelope ----
    public static final double MAX_PITCH = 25.0;
    public static final double MAX_CLIMB_ANGLE = 18.0;
    public static final double MAX_DESCENT_ANGLE = 12.0;
    public static final double MAX_BANK = 25.0;
    public static final double BANK_PER_HEADING_ERROR = 1.2;

    // ---- vertical guidance ----
    /** Vertical speed commanded per block of altitude error. */
    public static final double ALTITUDE_TO_VSPEED = 0.03;
    public static final double MAX_CLIMB_RATE = 0.35;
    public static final double MAX_SINK_RATE = 0.30;
    /** How hard flight-path-angle error is converted into a pitch attitude change. */
    public static final double FPA_TO_PITCH = 0.8;

    // ---- speed schedule ----
    public static final double CRUISE_SPEED = 0.80;
    public static final double CLIMB_SPEED = 0.70;
    public static final double APPROACH_SPEED = 0.50;
    public static final double FINAL_SPEED = 0.40;
    /** Deliberately unreachable, so the strike run simply pins the throttle at maximum. */
    public static final double STRIKE_SPEED = 9.0;
    public static final double SPEED_DEADBAND = 0.03;
    /** Ticks between throttle adjustments, to stop the engine lever from chattering. */
    public static final int THROTTLE_INTERVAL = 5;

    // ---- ground / takeoff ----
    public static final double ROTATE_SPEED = 0.35;
    public static final double TAKEOFF_CLEAR_HEIGHT = 10.0;
    public static final double ROLLOUT_STOP_SPEED = 0.04;

    // ---- terrain safety ----
    /** Minimum clearance kept above the highest terrain ahead. */
    public static final double TERRAIN_CLEARANCE = 22.0;
    /** How far ahead the terrain profile is sampled. */
    public static final int SCAN_DISTANCE = 220;
    public static final int SCAN_SAMPLES = 12;
    /** Half-angle of the two side probes used to pick a direction to dodge towards. */
    public static final double SCAN_SIDE_ANGLE = 35.0;
    /** Heading bias applied when dodging around a ridge. */
    public static final double AVOID_HEADING_BIAS = 30.0;
    /** Cruise altitude used when a flight plan does not specify one. */
    public static final double DEFAULT_CRUISE_ALTITUDE = 110.0;

    // ---- approach geometry ----
    /**
     * Steeper than a real 3-degree ILS on purpose. These three constants have to agree: the circuit
     * height must be roughly {@code tan(slope) * intercept distance}, or the aircraft arrives at the
     * initial approach fix far above the glide slope and has to dive at it. At 8 degrees and 300
     * blocks the slope sits at 42, just under the 45-block circuit height, so the descent is
     * continuous and the aircraft captures the slope from slightly above.
     */
    public static final double GLIDE_SLOPE_DEGREES = 8.0;
    /** Distance before the threshold at which the aircraft joins the final approach course. */
    public static final double FINAL_INTERCEPT_DISTANCE = 300.0;
    /** Circuit height above the runway threshold. */
    public static final double PATTERN_HEIGHT = 45.0;
    /** How far down the runway the aircraft aims. */
    public static final double TOUCHDOWN_AIM_OFFSET = 12.0;
    public static final double FLARE_HEIGHT = 4.0;
    public static final double FLARE_PITCH = 4.0;

    // ---- landing gates: violated on short final means go around ----
    public static final double GATE_HEADING_ERROR = 10.0;
    public static final double GATE_LATERAL_OFFSET = 10.0;
    public static final double GATE_BANK = 12.0;
    public static final double GATE_SINK_RATE = 0.45;
    /** Height above threshold below which the gates are enforced. */
    public static final double GATE_CHECK_HEIGHT = 30.0;

    // ---- holding ----
    public static final double HOLD_RADIUS = 90.0;
    /** Degrees the hold target advances around the fix each tick (a slow racetrack orbit). */
    public static final double HOLD_TURN_RATE = 1.1;
    public static final int MAX_GO_AROUNDS = 3;

    // ---- runway survey ----
    public static final int SURVEY_MAX_WIDTH = 24;
    public static final int SURVEY_APPROACH_LENGTH = 200;
    public static final int SURVEY_APPROACH_STEP = 10;
    /** Extra clearance an obstacle must leave under the approach path to not be flagged. */
    public static final double SURVEY_OBSTACLE_MARGIN = 3.0;

    // ---- limits ----
    /** Hard cap on simultaneously active autopilots per server, to bound the tick cost. */
    public static final int MAX_ACTIVE_AUTOPILOTS = 24;
    /** Default spawn distance of the strike tool. */
    public static final int STRIKE_SPAWN_DISTANCE = 400;
    /**
     * Height above the ground the strike aircraft is spawned at, and the height above ground its
     * run-in is flown at. Kept equal so the aircraft never has to trade speed for height after
     * launch.
     *
     * <p>High on purpose. A run-in flown low crosses whatever stands between the launch point and
     * the target — and what it usually meets first is a tree: a glancing hit on a canopy blocks only
     * the small vertical part of the motion, so the aircraft settles into the branches at walking
     * pace and sits there undamaged instead of reaching the target. Above the tree line there is
     * nothing to snag on.
     */
    public static final int STRIKE_RUN_IN_AGL = 100;
    /**
     * Flight path angle the terminal dive is entered at, which is what decides <i>when</i> it is
     * entered: the run-in is held until the target sits this far below the nose, so the dive point
     * follows the run-in height instead of being a fixed distance that only suits one altitude.
     *
     * <p>An earlier build dived from a fixed 350 blocks out, which at a 35-block run-in meant a
     * 6-degree glide starting almost immediately after launch — a long, shallow, treetop-scraping
     * descent. From 100 blocks up, 32 degrees commits the dive about 160 blocks out.
     *
     * <p>Past that point the nose is aimed straight at the target rather than at an altitude, so the
     * commanded angle is {@code atan(height / distance)}: it stays near this value for most of the
     * dive and steepens hyperbolically towards vertical over the last few blocks, which is both the
     * shape that hits accurately and the shape a dive bomber actually flies.
     */
    public static final double STRIKE_DIVE_ANGLE = 32.0;
    /** Floor and ceiling on the computed dive point, so an odd target height cannot produce an
     *  un-flyable one. */
    public static final double STRIKE_MIN_DIVE_DISTANCE = 60.0;
    public static final double STRIKE_MAX_DIVE_DISTANCE = 320.0;
    /** Speed under which an aircraft on a strike run is considered to have hit something. */
    public static final double STRIKE_STALLED_SPEED = 0.35;

    // ---- waypoints ----
    public static final double WAYPOINT_ARRIVAL_RADIUS = 30.0;
}
