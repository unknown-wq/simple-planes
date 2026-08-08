package xyz.przemyk.simpleplanes.autopilot;

/**
 * Tuning for helipads and the rotorcraft flight director.
 *
 * <p><b>Why this is not in {@link AutopilotConfig}.</b> That file's promise is "all the fixed-wing
 * tuning in one place", and every number in it is joined to the others: the glide slope, the
 * intercept distance and the circuit height are one geometry, the deceleration table is fitted to
 * {@code PlaneEntity#tickMotion}, and the comments are a record of what each number cost to find.
 * A rotorcraft shares none of that — it has no glide slope, no intercept, no stall speed and no
 * turn radius worth the name — so mixing the two sets would make both harder to retune. They are
 * separate machines and they get separate files.
 *
 * <p><b>Almost nothing here is a gain.</b> The control loops in {@link HelicopterAutopilot} close on
 * measured quantities — vertical speed, ground speed, heading — and drive the same discrete controls
 * a player has, so what they need from this file is <em>demands</em> and <em>tolerances</em>, not
 * per-tick coefficients fitted to the current flight model. That is deliberate: the helicopter
 * flight model is being rewritten, and a controller tuned against today's numbers would have to be
 * re-tuned against tomorrow's. The two numbers here that <em>are</em> fitted to the airframe rather
 * than to the mission are marked as such.
 */
public final class RotorcraftConfig {

    private RotorcraftConfig() {}

    // ------------------------------------------------------------------ the pad

    /**
     * Smallest pad the survey will register, as a radius in blocks — 1 means a 3x3 square.
     *
     * <p>A single block is refused rather than clamped up. A one-block pad cannot be centred on
     * anything (its cross-section has no middle to find), it gives the arrival no lateral tolerance
     * at all, and a player who clicked the same block twice much more likely mis-clicked than meant
     * it.
     */
    public static final int MIN_PAD_RADIUS = 1;

    /**
     * Largest pad the survey will register, as a radius in blocks — 7 means a 15x15 square.
     *
     * <p>Not a limit on what a player may build; a limit on what this code will call a helipad. The
     * survey reads every column inside the pad, so the cost is {@code (2r+1)^2} heightmap lookups
     * and the same again for the column above it, and past this size "a point you land on" has
     * become "an area you land somewhere in", which is a runway's problem and not a pad's.
     */
    public static final int MAX_PAD_RADIUS = 7;

    /**
     * How much the pad surface may vary across the pad, in blocks, before the survey refuses it.
     *
     * <p>One, not two. A helicopter touches down on a point rather than rolling out over a strip, so
     * the tolerance that matters is the one under the skids, and a pad with a block sticking up in
     * the middle of it is a pad that puts a machine down on one corner.
     */
    public static final int PAD_MAX_ROUGHNESS = 1;

    /**
     * Height of clear air the survey requires directly above the pad, in blocks.
     *
     * <p>This is the "is the column above it clear" half of the obstacle question, and it is asked
     * about the pad plus {@link #PAD_CLEARANCE_MARGIN} of ring around it, every column, with no
     * sampling step at all. A departure is vertical, so anything at all over the pad is on the flight
     * path.
     */
    public static final int PAD_CLEAR_HEIGHT = 24;

    /** Blocks of ring outside the pad that must be as clear as the pad itself. */
    public static final int PAD_CLEARANCE_MARGIN = 2;

    // ------------------------------------------------------------------ approach sectors

    /**
     * How many bearings the survey checks for a usable approach. Eight, i.e. every 45 degrees.
     *
     * <p>A runway has one approach direction per end and an obstacle in it is fatal. A pad has as
     * many as the terrain allows, and the question the survey answers is not "is the funnel clear"
     * but "is there <em>a</em> way in". Eight is enough to find a gap between two buildings and
     * cheap enough to check every column of every sector.
     */
    public static final int APPROACH_SECTORS = 8;

    /** How far out each approach sector is checked, in blocks. */
    public static final int APPROACH_LENGTH = 64;

    /**
     * Gradient of the approach path a sector is checked against, in degrees below the horizontal.
     *
     * <p>Steep, because a helicopter approach is. The fixed-wing figure is 8 degrees and is bounded
     * by what a wing can fly; this one is bounded by what a survey should promise, and 25 degrees
     * over 64 blocks puts the top of the sector 30 blocks above the pad — level with the vertical
     * clearance above it, so the two checks meet rather than leaving a wedge neither of them saw.
     */
    public static final double APPROACH_SLOPE_DEGREES = 25.0;

    /**
     * Widest an approach corridor gets, in blocks either side of its centre line.
     *
     * <p>There is no sampling step to go with this, and that is the whole point.
     * {@link Helipad} walks <b>every block column</b> inside the corridor, because a step of any
     * size hides an obstacle thinner than the step. The fixed-wing funnel learned that at 10 blocks
     * — a 20-block wall between two stations counted as no obstacle at all — and this file learned
     * it again at <em>two</em>: measured on the rig, a closed one-block-thick stone ring 9 blocks
     * out from a pad left two of the eight sectors reading clear, because 9 is not a multiple of 2
     * and the wall sat exactly between two samples. A step small enough for a stone wall is still
     * too big for a fence post. Every column, or the number is a guess.
     *
     * <p>The corridor is a wedge of half-angle 22.5 degrees — so the eight sectors tile the compass
     * with no gaps between them — until it reaches this width, and a parallel-sided strip after
     * that. Without the cap a sector at 64 blocks would be 53 blocks wide, which is more clear
     * ground than any helicopter needs and more than most real terrain has.
     */
    public static final double APPROACH_MAX_HALF_WIDTH = 8.0;

    /**
     * How much clear air the approach sector wants above the sloped path, in blocks.
     *
     * <p>The same idea as {@code AutopilotConfig.SURVEY_OBSTACLE_MARGIN} and the same trap: near the
     * pad the sloped path is barely above the ground it starts on, so the margin has to be floored
     * at the pad elevation or the pad's own surface counts as an obstacle in every sector.
     */
    public static final double APPROACH_MARGIN = 2.0;

    /**
     * Sectors that must be clear for the survey to register the pad at all.
     *
     * <p>One. A pad in a courtyard with a single way in is a real helipad and refusing it would be
     * wrong; a pad with no way in at all is a hole in the ground and registering it would produce a
     * helicopter that arrives overhead and cannot get down.
     */
    public static final int MIN_CLEAR_SECTORS = 1;

    // ------------------------------------------------------------------ the flight

    /**
     * How far a helicopter climbs above the pad before it goes anywhere, in blocks.
     *
     * <p>Comfortably above {@link #PAD_CLEAR_HEIGHT}, so the transition to forward flight starts
     * above everything the survey promised was not there.
     */
    public static final double DEPARTURE_HEIGHT = 30.0;

    /** Clearance held above the highest terrain on the leg, in blocks. */
    public static final double CRUISE_CLEARANCE = 30.0;

    /** Default en-route speed, in blocks per tick. */
    public static final double CRUISE_SPEED = 1.20;

    /** Bounds on the commanded en-route speed. A helicopter is not a fast aircraft. */
    public static final double MIN_CRUISE_SPEED = 0.20;
    public static final double MAX_CRUISE_SPEED = 2.00;

    public static double clampCruiseSpeed(double requested) {
        return Math.max(MIN_CRUISE_SPEED, Math.min(MAX_CRUISE_SPEED, requested));
    }

    /**
     * Horizontal distance from the pad at which the machine stops flying forward and starts
     * descending, in blocks.
     *
     * <p>The whole of the arrival geometry, and it is one number rather than the fixed-wing
     * arrival's dozen because a rotorcraft arrival has no shape to get wrong: come to a hover over
     * the pad, then go down. What this number buys is the room to bleed the cruise speed off before
     * the hover, which is why it is sized on the deceleration rather than picked.
     */
    public static final double HOVER_CAPTURE_RADIUS = 12.0;

    /**
     * Distance at which the approach speed schedule starts, in blocks.
     *
     * <p>Generous: the drag on this airframe is high and overshooting the pad costs a whole
     * re-positioning, whereas arriving slow costs a few seconds of hover.
     */
    public static final double DECELERATION_DISTANCE = 90.0;

    /** Speed the machine is flown at once it is inside {@link #DECELERATION_DISTANCE}. */
    public static final double APPROACH_SPEED = 0.35;

    /** Horizontal speed at or below which the machine counts as stopped over the pad. */
    public static final double HOVER_SPEED = 0.08;

    /** Commanded rate of descent onto the pad, in blocks per tick. */
    public static final double DESCENT_RATE = 0.22;

    /**
     * Height above the pad at which the descent is slowed to {@link #TOUCHDOWN_RATE}, in blocks.
     * The rotorcraft equivalent of a flare, and the same idea: the last part of the descent is flown
     * slowly because whatever happens in it cannot be undone.
     */
    public static final double TOUCHDOWN_HEIGHT = 6.0;

    /** Commanded rate of descent below {@link #TOUCHDOWN_HEIGHT}. */
    public static final double TOUCHDOWN_RATE = 0.08;

    /** Commanded rate of climb on a vertical departure, in blocks per tick. */
    public static final double CLIMB_RATE = 0.30;

    /**
     * Altitude error to a commanded vertical speed, per block.
     *
     * <p>One cascade stage where the fixed-wing controller needs four, because rotor thrust is
     * already vertical: there is no flight path angle and no pitch attitude in between. 0.03 means
     * an error of 10 blocks asks for 0.3 blocks/tick, which the phase limits then clamp.
     */
    public static final double ALTITUDE_TO_VERTICAL_SPEED = 0.03;

    /**
     * Vertical-speed error, in blocks per tick, below which the collective is left alone.
     *
     * <p>One of the two numbers here that is about the airframe rather than the mission: it is a
     * deadband on a loop whose actuator is a throttle notch, so it has to be wider than the vertical
     * speed one notch produces or the lever chatters. Provisional against the new flight model.
     */
    public static final double VERTICAL_SPEED_DEADBAND = 0.02;

    /**
     * Vertical-speed error, in blocks per tick, at which the collective goes to its stop in one
     * step instead of one notch at a time.
     *
     * <p>The same rule and the same reason as {@code AutopilotConfig.THROTTLE_CUT_EXCESS}: a notch
     * every {@link #COLLECTIVE_INTERVAL} ticks is a slow lever, and a machine that is a third of a
     * block per tick away from the rate it wants near the ground does not have the ticks.
     */
    public static final double VERTICAL_SPEED_SLAM = 0.30;

    /** Ticks between collective notches. */
    public static final int COLLECTIVE_INTERVAL = 2;

    /**
     * Ground-speed error, in blocks per tick, below which the cyclic is left alone. The other
     * airframe-fitted number, and provisional for the same reason.
     */
    public static final double GROUND_SPEED_DEADBAND = 0.05;

    /**
     * Heading error, in degrees, that produces full pedal.
     *
     * <p>Proportional rather than bang-bang, because the yaw plant here is a rate plant rather than
     * the double integrator a plane's yaw is, and bang-bang on a rate plant limit-cycles about the
     * deadband instead of settling.
     */
    public static final double YAW_ERROR_SPAN = 12.0;

    /**
     * Ticks of yaw rate subtracted from the heading error before the pedal law sees it.
     *
     * <p>This is the whole of what makes the same law work on both plants. With a rate plant it is
     * a damping term that costs a little settling time; with an acceleration plant it is the lead
     * that stops the controller overshooting, which is what {@code AutopilotMath.bangBang} provides
     * for the fixed-wing controls. If the new flight model turns yaw into an acceleration, this is
     * the number that has to grow, and nothing else.
     */
    public static final double YAW_RATE_LEAD = 2.0;

    /**
     * How much closure speed the station-keeping law asks for per block of distance to the point.
     *
     * <p>The whole of the terminal geometry, and the number the arrival's accuracy hangs off. Too
     * high and the machine arrives faster than it can stop and spirals; too low and it takes minutes
     * to cover the last ten blocks. 0.02 asks for 0.24 blocks/tick at 12 blocks out and 0.10 at 5,
     * which is inside what thrust-along-the-velocity-error can bleed off in the distance remaining.
     */
    public static final double CLOSURE_GAIN = 0.02;

    /**
     * Velocity error, in blocks per tick, below which the station-keeping law does nothing.
     *
     * <p>Both a deadband on the cyclic and a guard on the heading: below it there is no meaningful
     * direction in the error vector, and pointing the nose at the direction of the rounding noise
     * makes the machine spin on the spot over its own pad.
     */
    public static final double STATION_DEADBAND = 0.03;

    /** Heading error, in degrees, below which no yaw input is given. */
    public static final double HEADING_DEADBAND = 3.0;

    /** Heading error, in degrees, above which the machine stops translating and turns first. */
    public static final double TURN_FIRST_ERROR = 60.0;

    /** How close to the pad centre a landing counts as on the pad, as a fraction of its radius. */
    public static final double LANDING_TOLERANCE_FRACTION = 1.0;

    /** Never demand better than this, in blocks, however small the pad. */
    public static final double LANDING_TOLERANCE_FLOOR = 2.0;

    /** Ticks of continuous ground contact after which the arrival is called finished. */
    public static final int SETTLED_TICKS = 20;

    /** Ticks a departure may take to leave the pad before it is called a failure. */
    public static final int DEPARTURE_TIMEOUT = 1200;

    /** Ticks the descent onto a pad may take before it is called a failure. */
    public static final int DESCENT_TIMEOUT = 2400;

    /** Ticks a machine may hold overhead an occupied pad before it gives up. */
    public static final int HOLD_TIMEOUT = 3600;

    /** Radius of the orbit flown while holding overhead an occupied pad, in blocks. */
    public static final double HOLD_RADIUS = 30.0;

    /**
     * How fast the point a holding machine chases walks round the pad, in degrees per tick.
     *
     * <p>Sized so the point moves slower than the machine can fly: 0.8 degrees on a 30-block radius
     * is 0.42 blocks per tick, comfortably inside {@link #APPROACH_SPEED}'s bigger cousin. Ask for
     * more and the machine falls behind its own hold and drifts to the middle of it, which is a hold
     * that separates nothing.
     */
    public static final double HOLD_TURN_RATE = 0.8;

    /** Extra height above the departure height at which a holding machine waits, in blocks. */
    public static final double HOLD_HEIGHT = 15.0;

    /**
     * Slack on the en-route timeout: the leg is allowed this many times the time a machine flying
     * the commanded speed in a straight line would need, plus {@link #TRANSIT_TIMEOUT_MARGIN}.
     *
     * <p>The whole reason there is a timeout at all is in {@code AUTOPILOT.md}: a cargo plane once
     * orbited a field for 24000 ticks without landing, without going around, and without a single
     * line in the log saying so. A leg that has taken three times as long as it should has failed,
     * and it is going to say so.
     */
    public static final double TRANSIT_TIMEOUT_FACTOR = 3.0;

    /** Fixed part of the en-route timeout, in ticks — covers the climb, the turn and the hover. */
    public static final int TRANSIT_TIMEOUT_MARGIN = 1200;

    /** Ticks between polls of an occupied pad. Matches the fixed-wing departure poll. */
    public static final int PAD_POLL_INTERVAL = 20;

    /** How far a machine may be from the pad centre and still be considered to be using it. */
    public static final double PAD_CLAIM_RADIUS = 6.0;
}
