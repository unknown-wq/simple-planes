package xyz.przemyk.simpleplanes.combat;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.autopilot.TerrainScanner;
import xyz.przemyk.simpleplanes.entities.HelicopterEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.Locale;

/**
 * The stopgap {@link HoverControl}: a collective loop and a pedal loop, and deliberately nothing
 * else.
 *
 * <h2>What it is allowed to touch</h2>
 * Two of the five helicopter controls, both of them things a pilot holds: the <b>collective</b>
 * ({@code setThrottle}, in notches) and the <b>pedals</b> ({@code setPedal}, −1/0/+1). It does not
 * touch the cyclic in either axis, so the aircraft never banks and never translates — see
 * {@link GunshipSortie} for why a gunship wants exactly that. It sets no position, no velocity and
 * no rotation.
 *
 * <h2>The collective: hill-climbing on a monotonic ladder, not a PID</h2>
 * The rewritten flight model gives every collective notch a hard vertical-speed <em>equilibrium</em>
 * — {@code HelicopterEntity#rotorThrust} scales thrust by {@code 1 - vy / ROTOR_INFLOW_LIMIT}, so a
 * notch does not accelerate for ever, it settles. The ladder is monotonic: more collective is never
 * less climb.
 *
 * <p>That makes a PID both unnecessary and wrong. What is written instead is a search:
 *
 * <pre>
 *   altitude error        -&gt;  commanded vertical speed   (proportional, clamped)
 *   measured vs too low   -&gt;  one notch up,   at most every {@link #NOTCH_INTERVAL} ticks
 *   measured vs too high  -&gt;  one notch down, same
 * </pre>
 *
 * The loop starts at {@code HelicopterEntity.HOVER_THROTTLE} — the airframe's own published hover
 * notch — so on station the search usually has nothing to do. It uses only the two facts the flight
 * model guarantees, monotonicity and the existence of an equilibrium, and <b>no equilibrium value is
 * written down here</b>. If the ladder were rescaled tomorrow the loop would find the new notch on
 * the way up and settle on it.
 *
 * <p>The interval matters more than any gain. The plant needs a handful of ticks to reach a notch's
 * equilibrium, so a loop that re-decides every tick judges a notch against a speed that has not
 * happened yet — the classic way to make a stable ladder oscillate. Waiting is the damping.
 *
 * <p>An earlier version of this file, written against the previous flight model, was a PI loop on a
 * fractional lever. It was needed then, because that model had no equilibrium: thrust was a constant
 * per notch and the vertical speed simply ran away until drag caught it. Its first form — a pure
 * integrator, no proportional term — hunted through ±1.6 blocks with a 180-tick period and never
 * once reported "on station" in two minutes. None of that machinery survives, and none of it is
 * needed.
 *
 * <h2>The pedals: bang-bang with a stopping-distance term</h2>
 * Yaw is a rate command on an integrator ({@code YAW_RAMP} 0.5 deg/tick² up to {@code MAX_YAW_RATE}
 * 3.0 deg/tick), i.e. a double integrator, on which a proportional controller oscillates for ever.
 * {@link #tickPedals} subtracts the angular stopping distance {@code rate * |rate| / (2 * ramp)}
 * from the heading error, so the pedal centres at the moment the nose will coast onto the commanded
 * heading — the idiom {@code AutopilotMath.bangBang} uses for the fixed-wing rudder, which the
 * flight model's author deliberately kept transferable.
 *
 * <p>This replaces a {@code setYRot} write in the previous version of this file. There was no yaw
 * control to move at the time; there is now, and the nose is turned by flying rather than by being
 * teleported round.
 *
 * <h2>What is provisional</h2>
 * {@link #ALTITUDE_GAIN}, {@link #MAX_CLIMB_RATE}, {@link #MAX_SINK_RATE}, {@link #DESCENT_RATE},
 * {@link #NOTCH_INTERVAL} and the tolerances. They are rates, gains and intervals rather than thrust
 * constants, so a further change to the flight model should change how fast this converges and not
 * whether it does.
 */
public final class CollectiveHover implements HoverControl {

    /** Blocks/tick of commanded climb per block of altitude error. */
    private static final double ALTITUDE_GAIN = 0.06;
    /** Ceiling on commanded climb, blocks/tick. */
    private static final double MAX_CLIMB_RATE = 0.20;
    /** Ceiling on commanded sink, blocks/tick. Lower than the climb rate: coming down is the risky half. */
    private static final double MAX_SINK_RATE = 0.15;
    /** Vertical-speed error under which the collective is left alone, blocks/tick. */
    private static final double VS_DEADBAND = 0.02;
    /**
     * Altitude error under which the commanded vertical speed is exactly zero, blocks.
     *
     * <p>Without it the loop hunts, and the reason is the ladder rather than the gain: an altitude
     * error of one block asks for 0.06 blocks/tick of climb, and no notch delivers that — notch 3
     * settles at 0.00 and notch 4 at +0.135, so the search flips between them for ever. Measured
     * on the rig with no deadband: the collective alternating 5 / 1 / 5 / 1 and the aircraft sawing
     * through about a block of altitude. A gunship a block off station is on station.
     */
    private static final double ALTITUDE_DEADBAND = 1.0;
    /** Ticks between collective changes, so each notch is judged on its settled speed. */
    private static final int NOTCH_INTERVAL = 5;

    /** How fast the commanded altitude walks down during a landing, blocks/tick. */
    private static final double DESCENT_RATE = 0.10;
    /** Altitude error and vertical speed under which the aircraft counts as on station. */
    private static final double ON_STATION_ALTITUDE = 1.5;
    private static final double ON_STATION_SPEED = 0.03;
    /** Consecutive ticks inside those tolerances before the platform is called stable. */
    private static final int ON_STATION_TICKS = 15;

    /** Vertical speed at touchdown under which the landing counts as controlled, blocks/tick. */
    private static final double TOUCHDOWN_SPEED = 0.45;
    /** Ticks after {@link #descendAndLand} before the attempt is declared a failure. */
    private static final int LANDING_TIMEOUT = 800;
    /** Heading error under which the pedals centre, degrees. */
    private static final double HEADING_TOLERANCE = 2.0;
    /**
     * Height above the ground at which a landing shuts the collective off instead of regulating,
     * blocks.
     *
     * <p>Without it the aircraft <b>hovers a few centimetres above the ground for ever and reports a
     * failed landing</b>, which is exactly what it did on the rig: two of three arrivals ended
     * {@code never settled: still moving at 0.00 blocks/tick after 801 ticks at 1201, -60, 1201} —
     * on the surface, motionless, and never touching it. The altitude loop was doing its job: with
     * the commanded altitude already at the ground, the error is inside {@link #ALTITUDE_DEADBAND},
     * so the commanded vertical speed is zero, so the search settles on the hover notch and holds
     * the machine off the ground. A landing is not an altitude hold at zero; it is the deliberate
     * end of one. The flight model makes this safe — the physics agent measured every collective
     * from 5 down to 0 landing inside the crash tolerance.
     */
    private static final double COLLECTIVE_CUT_HEIGHT = 1.5;

    private final HelicopterEntity helicopter;

    private double commandedY;
    private boolean landingRequested;
    private int landingTicks;
    private double landingFloor;
    private @Nullable Landing landing;
    /** Sink rate on the last airborne tick, so the touchdown can be reported honestly. */
    private double approachSink;

    private int collective = HelicopterEntity.HOVER_THROTTLE;
    private boolean collectiveCut;
    private int sinceNotchChange;
    private int settledTicks;
    private @Nullable Float commandedHeading;

    public CollectiveHover(HelicopterEntity helicopter) {
        this.helicopter = helicopter;
        this.commandedY = helicopter.getY();
    }

    @Override
    public void holdAltitude(double y) {
        if (!landingRequested) {
            commandedY = y;
        }
    }

    @Override
    public void faceTowards(double headingDegrees) {
        commandedHeading = (float) headingDegrees;
    }

    @Override
    public boolean onStation() {
        return settledTicks >= ON_STATION_TICKS;
    }

    @Override
    public double verticalSpeed() {
        return helicopter.getVerticalSpeed();
    }

    @Override
    public void descendAndLand() {
        if (landingRequested) {
            return;
        }
        landingRequested = true;
        landingTicks = 0;
        commandedY = helicopter.getY();
        // The floor the descent walks down to: the ground under the aircraft, taken once so a mob
        // wandering underneath or a block placed mid-descent cannot move the target.
        int surface = TerrainScanner.surfaceHeight(helicopter.level(), helicopter.getX(), helicopter.getZ());
        landingFloor = surface == TerrainScanner.UNKNOWN_HEIGHT ? helicopter.getY() : surface;
    }

    @Override
    public @Nullable Landing landing() {
        return landing;
    }

    @Override
    public void tick() {
        if (landingRequested && landing == null) {
            tickLanding();
        }
        tickCollective();
        tickPedals();
    }

    private void tickLanding() {
        landingTicks++;
        commandedY = Math.max(landingFloor, commandedY - DESCENT_RATE);

        double agl = heightAboveGround();
        if (agl < COLLECTIVE_CUT_HEIGHT) {
            collectiveCut = true;
        }

        // The sink rate carried into contact, sampled while the aircraft is still flying. Read after
        // the skids are down it is always ~0 and every arrival would report as feather-light.
        if (!helicopter.getOnGround()) {
            approachSink = -verticalSpeed();
        }

        // Down, and staying down. getOnGround() carries a coyote timer, so a single frame of contact
        // during a bounce is not a landing; the aircraft has to be low, slow and resting.
        if (helicopter.getOnGround() && agl < 1.0 && Math.abs(verticalSpeed()) < 0.05) {
            boolean gentle = approachSink <= TOUCHDOWN_SPEED;
            landing = new Landing(gentle, helicopter.position(), gentle
                ? "touched down at " + describeSpeed(approachSink) + " blocks/tick"
                : "arrived hard, " + describeSpeed(approachSink) + " blocks/tick on contact");
            helicopter.setThrottle(0);
            return;
        }

        if (landingTicks > LANDING_TIMEOUT) {
            landing = new Landing(false, helicopter.position(), landingFailureReason(agl));
        }
    }

    private String landingFailureReason(double agl) {
        if (helicopter.isOnWater()) {
            return "came down in water at " + position(helicopter.position());
        }
        if (agl > 1.0) {
            return "stopped " + describeSpeed(agl) + " blocks above the ground at "
                + position(helicopter.position()) + " - something is holding it up";
        }
        return "never settled: still moving at " + describeSpeed(Math.abs(verticalSpeed()))
            + " blocks/tick after " + landingTicks + " ticks at " + position(helicopter.position());
    }

    private void tickCollective() {
        if (collectiveCut) {
            helicopter.setCollectiveBoost(false);
            helicopter.setThrottle(0);
            return;
        }
        double error = commandedY - helicopter.getY();
        double commandedVs = Math.abs(error) < ALTITUDE_DEADBAND
            ? 0.0
            : Mth.clamp(error * ALTITUDE_GAIN, -MAX_SINK_RATE, MAX_CLIMB_RATE);
        double vsError = commandedVs - verticalSpeed();

        sinceNotchChange++;
        if (sinceNotchChange >= NOTCH_INTERVAL && Math.abs(vsError) > VS_DEADBAND) {
            collective = Mth.clamp(collective + (vsError > 0 ? 1 : -1), 0, maxCollective());
            sinceNotchChange = 0;
        }
        // Collective only. The boost flag adds two notches on top, which is a second way of saying
        // the same thing, and two levers for one quantity is how a search loop ends up hunting.
        helicopter.setCollectiveBoost(false);
        helicopter.setThrottle(collective);

        if (Math.abs(error) < ON_STATION_ALTITUDE && Math.abs(verticalSpeed()) < ON_STATION_SPEED) {
            settledTicks++;
        } else {
            settledTicks = 0;
        }
    }

    /**
     * Bang-bang on the pedals with an angular stopping-distance term, because yaw is a rate command
     * on an integrator and a proportional controller on a double integrator never settles.
     */
    private void tickPedals() {
        if (commandedHeading == null) {
            helicopter.setPedal(0);
            return;
        }
        double error = Mth.wrapDegrees(commandedHeading - helicopter.getYRot());
        double rate = Mth.wrapDegrees(helicopter.getYRot() - helicopter.yRotO);
        double stopping = rate * Math.abs(rate) / (2.0 * HelicopterEntity.YAW_RAMP);
        double effective = error - stopping;
        helicopter.setPedal(effective > HEADING_TOLERANCE ? 1 : effective < -HEADING_TOLERANCE ? -1 : 0);
    }

    /** Notches the collective may use. Read from the airframe's own constant, not written as a 5. */
    private int maxCollective() {
        return PlaneEntity.MAX_THROTTLE;
    }

    /** Height above the ground under the aircraft, or its height above the landing floor if unknown. */
    @Override
    public double heightAboveGround() {
        int surface = TerrainScanner.surfaceHeight(helicopter.level(), helicopter.getX(), helicopter.getZ());
        return helicopter.getY() - (surface == TerrainScanner.UNKNOWN_HEIGHT ? landingFloor : surface);
    }

    static String describeSpeed(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static String position(Vec3 position) {
        return Math.round(position.x) + ", " + Math.round(position.y) + ", " + Math.round(position.z);
    }
}
