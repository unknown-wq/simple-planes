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
    /**
     * Cruise target. The airframe's own equilibrium at full throttle is 0.76 blocks/tick — solve
     * {@code 0.03125 * (1 - v / 0.8125) = 0.001 v^2 + 0.0005 v + 0.001} — so anything at or above
     * that simply pins the throttle open, which is what is wanted for a cruise.
     */
    public static final double CRUISE_SPEED = 0.80;
    public static final double CLIMB_SPEED = 0.70;
    public static final double APPROACH_SPEED = 0.50;
    public static final double FINAL_SPEED = 0.40;
    /** Deliberately unreachable, so the strike run simply pins the throttle at maximum. */
    public static final double STRIKE_SPEED = 9.0;
    public static final double SPEED_DEADBAND = 0.03;
    /** Ticks between throttle adjustments, to stop the engine lever from chattering. */
    public static final int THROTTLE_INTERVAL = 5;

    /**
     * Horizontal speed below which the throttle loop stops being polite and slams the lever open.
     * <p>
     * {@code PlaneEntity#getLiftRatio} produces zero lift below
     * {@code takeOffSpeed * stallSpeedFactor} = 0.165 blocks/tick, so this is the stall speed with a
     * healthy margin. Recovery has to be immediate rather than one notch per
     * {@link #THROTTLE_INTERVAL} ticks: at one notch per 5 ticks a stalled aircraft needs 25 ticks
     * to reach full power, and it is on the ground long before that.
     */
    public static final double MIN_FLYING_SPEED = 0.32;

    /**
     * Throttle never goes below this while airborne.
     * <p>
     * Closing the throttle completely is not "no thrust", it is an airbrake:
     * {@code PlaneEntity#tickMotion} multiplies the whole drag polynomial by
     * {@code brakesMul = 5} at throttle 0. Leaving one notch in keeps that off, which is the
     * difference between a descent and a deceleration. Only {@code FLARE} and {@code ROLLOUT} —
     * where stopping is the point, and the ground is right there — are allowed to close it.
     */
    public static final int MIN_AIRBORNE_THROTTLE = 1;

    /**
     * Largest angle the nose is ever allowed to sit off the velocity vector, in degrees.
     *
     * <p>This is the anti-stall limiter, and it is the fix for aircraft pancaking out of a turn or a
     * landing descent. {@code PlaneEntity#tickRotateMotion} computes an angle-of-attack efficiency
     * {@code d = 1 - min(1, aoa/60)^2} and multiplies <i>both</i> the wing lift and the rate at
     * which the velocity vector follows the nose by it. At 60 degrees of angle of attack {@code d}
     * is exactly zero: the wings stop working, the velocity vector stops following the nose, and the
     * aircraft falls with the nose up and no way out. The altitude cascade then reads the growing
     * sink rate as "too low" and commands even more nose-up, which is a divergence, not an
     * oscillation — measured in the field at 104 degrees of angle of attack, 1.09 blocks/tick of
     * sink and the nose 188 degrees off the commanded heading.
     *
     * <p>20 degrees keeps {@code d} at 0.89 or better, so the wings always work and the flight path
     * always follows the nose.
     */
    public static final double MAX_ANGLE_OF_ATTACK = 20.0;

    /**
     * Bank is given up when the aircraft is slow: a level turn needs {@code 1/cos(bank)} times the
     * lift of level flight, and lift is exactly what a slow aircraft has none of. Below
     * {@link #MIN_FLYING_SPEED} the wings are levelled outright.
     */
    public static final double BANK_LIMIT_SPEED = 0.55;

    /**
     * Bank angle, in degrees, past which the aircraft is treated as manoeuvring and the throttle
     * loop stops being allowed to reduce power. See {@code PlaneAutopilot#applyThrottle}.
     */
    public static final double MANOEUVRE_BANK = 8.0;
    /** Heading error, in degrees, that likewise counts as manoeuvring. */
    public static final double MANOEUVRE_HEADING_ERROR = 15.0;

    /**
     * Ticks an aircraft may sit on the ground in a mode that is supposed to be flying before the
     * flight is declared over. Without this a sortie that mushes into a field simply trundles along
     * the surface at taxi speed for the rest of the session, reporting {@code cruise}, and never
     * produces an outcome line for anyone to assert on.
     */
    public static final int GROUNDED_TIMEOUT = 60;

    // ---- ground / takeoff ----
    public static final double ROTATE_SPEED = 0.35;
    public static final double TAKEOFF_CLEAR_HEIGHT = 10.0;
    public static final double ROLLOUT_STOP_SPEED = 0.04;

    // ---- taxi ----
    /**
     * Ground speed held while taxiing, in blocks/tick (~4 blocks/s). Comfortably under
     * {@code takeOffSpeed} (0.3) so the aircraft cannot fly itself off the taxiway, and above the
     * 0.1 threshold at which {@code PlaneEntity#tickOnGround} applies its static-friction penalty.
     */
    public static final double TAXI_SPEED = 0.20;
    /** Throttle ceiling while taxiing, so the aircraft creeps rather than charging off. */
    public static final int TAXI_MAX_THROTTLE = 3;
    /** Distance from the lineup point at which the aircraft stops steering to it and aligns. */
    public static final double TAXI_LINEUP_RADIUS = 6.0;
    /** Heading error the aircraft must be inside, lined up on the threshold, before it may go. */
    public static final double TAXI_ALIGNED_ERROR = 8.0;
    /**
     * How far the parking spot sits to the side of the runway centreline, beyond the measured runway
     * half-width. Far enough to be off the strip, close enough that the taxi is a short one.
     */
    public static final double PARKING_LATERAL_OFFSET = 4.0;
    /** How far back from the threshold the parking spot sits, along the runway. */
    public static final double PARKING_BEHIND_THRESHOLD = 12.0;
    /** Ticks a taxi may take before the aircraft gives up and departs from where it stands. */
    public static final int TAXI_TIMEOUT = 900;

    // ---- chunk loading ----
    /**
     * Radius, in chunks, of the ticket the autopilot keeps around each aircraft.
     *
     * <p>Not a cosmetic number. {@code TicketStorage#addTicketWithRadius} gives the centre chunk
     * level {@code 33 - radius} and the level rises by one per chunk outwards, while
     * {@code ChunkLevel#isEntityTicking} needs level 31 or lower. So a ticket of radius {@code r}
     * only makes chunks within {@code r - 2} of the centre tick their entities: vanilla's ender
     * pearl radius of 2, which this used to copy, produces an entity-ticking area of exactly one
     * chunk. An aircraft at 3 blocks/tick leaves that chunk in five ticks and stops ticking.
     *
     * <p>4 gives an entity-ticking area two chunks (32 blocks) in every direction, which at
     * {@link #CHUNK_TICKET_INTERVAL} is several renewals' worth of travel.
     */
    public static final int CHUNK_TICKET_RADIUS = 4;
    /** Ticks between chunk-ticket renewals. Well inside the 40-tick ticket timeout. */
    public static final int CHUNK_TICKET_INTERVAL = 5;
    /**
     * How far ahead of the aircraft a second ticket is placed, in ticks of travel. Loading the
     * ground it is about to fly over both keeps it ticking and gives the terrain scanner real
     * heightmaps to read instead of the "unknown, hold altitude" fallback.
     */
    public static final int CHUNK_TICKET_LEAD_TICKS = 20;

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
