package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.entities.HelicopterEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesRegistries;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.booster.BoosterUpgrade;

/**
 * The rotorcraft flight director: pad to pad, vertically off and vertically on.
 *
 * <h2>Why this is beside {@link PlaneAutopilot} and not inside it</h2>
 * {@code PlaneAutopilot} is 2400 lines and about nine tenths of it describes things a helicopter
 * does not have. The arrival planner reasons in turn radii, intercept distances and extended
 * centrelines; the throttle loop is fitted to a drag polynomial and a stall speed; there is a
 * departure plan that chooses which end of a strip to roll down, a taxi, a taxi-in, a hold pattern
 * sized on a bank angle, a go-around counter and a glide slope. A machine that can stop in the air
 * needs none of it. Adding a helicopter branch to each of those would mean touching the arrival
 * planner — the part of this codebase that took three agents to get right and that is working,
 * tested and shipped — in order to teach it about an aircraft it will never fly.
 *
 * <p>So the fixed-wing state machine is not modified at all. {@code PlaneAutopilot} gains one field,
 * one guard at the top of {@code tick} and a handful of one-line delegations, and everything a
 * rotorcraft does lives here. The two share what is genuinely shared: the registry and its chunk
 * tickets, the terrain scanner, the geometry helpers, the persistence hook and the reporting.
 *
 * <h2>What the control loops are written against</h2>
 * <b>Quantities, not constants.</b> This controller was written while {@code HelicopterEntity} was
 * being replaced underneath it, so every loop closes on something <em>measurable</em> — vertical
 * speed, velocity error, heading — and drives the same controls a player has until the measurement
 * matches the demand:
 *
 * <ul>
 *   <li><b>Collective</b> ({@code setThrottle}) is a search for the notch whose <em>equilibrium</em>
 *       vertical speed is the one demanded. Whatever thrust a notch is worth, the search finds the
 *       notch that holds the demanded rate, so a model that changes the thrust per notch changes
 *       only how long it takes to settle. See {@link #collective}.</li>
 *   <li><b>Cyclic</b> ({@code setCyclicForward} / {@code setCyclicRight}) is proportional-plus-
 *       integral on the <em>velocity error</em>, integrated in the <b>world</b> frame and resolved
 *       into the two sticks every tick. See {@link #trim} for the two ways this was got wrong
 *       first.</li>
 *   <li><b>Pedal</b> ({@code setPedal}) is {@link AutopilotMath#bangBang} on the heading error,
 *       unchanged from the fixed-wing rudder, because the pedal is a rate command on an integrator
 *       with a ramp — the same double-integrator shape {@code PlaneEntity#tickYaw} has.</li>
 * </ul>
 *
 * <p><b>The flight model shows through in exactly three methods</b> — {@link #collective},
 * {@link #cyclic} and {@link #pedal}, at the bottom of the class — and nowhere above them.
 * Everything above is written in blocks per tick and degrees. That was the whole bet while the
 * airframe was in flux, and it paid: when the rewrite landed, the profile, the timeouts, the survey
 * and the reporting did not move at all.
 *
 * <p>{@code setPitchUp} is never called on a helicopter. It does nothing on this airframe
 * <em>and</em> its sign convention is the opposite of the cyclic's, so a controller reaching for it
 * out of fixed-wing habit would be writing into a control that is both dead and backwards.
 */
public final class HelicopterAutopilot {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-autopilot");

    private static final boolean TRACE = Boolean.getBoolean("simpleplanes.autopilot.trace");

    /** The flight director this one hangs off, for the owner, the plan and the shared lifetime. */
    private final PlaneAutopilot host;

    private AutopilotMode mode = AutopilotMode.IDLE;

    private @Nullable Helipad departure;
    private @Nullable Helipad destination;

    private double cruiseSpeed = RotorcraftConfig.CRUISE_SPEED;
    private int cruiseAltitude;
    private int departureDelayTicks;

    private int ticks;
    private int modeTicks;
    private int transitTicks;
    private int liftOffTick = -1;
    private int overheadTick = -1;

    /** Whether the outcome line has already been printed, so it is printed exactly once. */
    private boolean reported;

    private int settledTicks;
    private boolean padWaitReported;

    /**
     * The bearing the run-in was flown on, kept for the let-down to hold the nose on.
     *
     * <p>Decided once, when the machine leaves the cruise, rather than re-derived every tick from
     * where it currently is: {@link Helipad#arrivalHeading} answers "which clear sector is nearest
     * the side I am coming from", and asking that from a position that is nearly on top of the pad
     * gets a different answer every few blocks.
     */
    private double runInHeading;

    /** True while the cyclic is on its stop, i.e. the machine is flying as fast as it can. */
    private boolean cyclicSaturated;

    /** Whether the "cannot make that speed good" line has been printed for this flight. */
    private boolean speedShortfallReported;

    /**
     * Yaw last tick, for the rate term in the pedal law.
     *
     * <p>Kept here rather than read off {@code Entity#yRotO}: the flight director runs at the top of
     * {@code PlaneEntity#tick}, and whether {@code yRotO} has been refreshed by then is a detail of
     * the entity's tick order — which is the file being rewritten beside this one. A rate this
     * controller measured itself cannot be wrong by one tick's worth of somebody else's ordering.
     */
    private double previousYaw;
    private boolean yawInitialised;

    /**
     * The commanded disc tilt, as a vector in <b>world</b> coordinates, scaled in percent of full
     * cyclic deflection.
     *
     * <p>Held across ticks because the cyclic is a <em>position</em> command and the loop that drives
     * it is an integrator — see {@link #trim}. In world coordinates rather than body coordinates
     * because the body is turning underneath it: measured on the rig with the integrator held in the
     * body frame, an arrival over a pad went into a limit cycle at 4 to 7 blocks and spun through
     * 200 degrees of heading doing it, because a stick position that meant "forward" a second ago
     * meant "sideways" by the time the pedal had finished with it. Integrating in the world frame and
     * resolving into the two sticks every tick makes the loop independent of what the nose is doing.
     */
    private double cyclicWorldX;
    private double cyclicWorldZ;

    /** The integral part of it, kept separately so the proportional part can be recomputed. */
    private double cyclicTrimX;
    private double cyclicTrimZ;

    // What the loops are asking for this tick, kept for the status line and the trace.
    private double cmdHeading;
    private double cmdAltitude;
    private double cmdVerticalSpeed;
    private double cmdGroundSpeed;

    private final TerrainScanner scanner = new TerrainScanner();

    HelicopterAutopilot(PlaneAutopilot host) {
        this.host = host;
    }

    // ------------------------------------------------------------------ lifetime

    /**
     * Resolves the two pads and puts the machine into its first mode.
     *
     * <p>A sortie starts on the departure pad and an inbound flight starts in the air, exactly as
     * the fixed-wing {@code flight} and {@code inbound} do, and the difference is whether the plan
     * names a departure.
     */
    void start(PlaneEntity plane, FlightPlan plan) {
        start(plane, plan, false);
    }

    /**
     * @param resume true when the flight is being restored from disk rather than ordered, in which
     *               case the pad phases are skipped: the machine is somewhere in the air with no
     *               record of how high it had got, and re-entering the vertical departure would
     *               climb it another 30 blocks for no reason. The same judgement the fixed-wing
     *               {@code load} makes about a half-finished taxi.
     */
    void start(PlaneEntity plane, FlightPlan plan, boolean resume) {
        Level level = plane.level();
        if (level instanceof ServerLevel serverLevel) {
            AutopilotSavedData data = AutopilotSavedData.get(serverLevel);
            departure = plan.departureAirfield() == null ? null : data.helipad(plan.departureAirfield());
            destination = plan.airfieldName() == null ? null : data.helipad(plan.airfieldName());
        }
        cruiseSpeed = RotorcraftConfig.clampCruiseSpeed(plan.cruiseSpeed());
        cruiseAltitude = plan.cruiseAltitude();
        departureDelayTicks = plan.departureDelayTicks();
        if (destination == null) {
            // Nothing to fly to. Said out loud rather than flown at, because an aircraft with no
            // destination is the failure that used to look like a launch.
            report(plane, "has no destination pad registered; nothing to fly to");
            host.stop(plane);
            return;
        }
        setMode(plane, departure != null && !resume ? AutopilotMode.PARKED : AutopilotMode.CRUISE);
        if (departure == null || resume) {
            liftOffTick = 0;
        }
    }

    AutopilotMode mode() {
        return mode;
    }

    /**
     * The pad this machine is using, as a block, so {@link Helipad#free} can ask the other live
     * autopilots whether it is spoken for. Reuses {@code claimsStand} rather than inventing a second
     * registry for the same question — a pad and a parking stand are both "one square, one aircraft".
     *
     * <p><b>A destination is claimed from the run-in, not from the launch, and that distinction is a
     * deadlock.</b> Claimed from the launch it deadlocks any two machines bound for the same pad:
     * each sees the other's claim on a pad that is standing empty, each holds, and neither ever
     * arrives to release it. Measured on the rig with three machines sent to one pad from 600, 1000
     * and 1600 blocks out — every one of them reported "the pad is occupied" while it was bare
     * ground, two of them ran the full {@link RotorcraftConfig#HOLD_TIMEOUT} out and gave up, and the
     * third only landed because the other two had by then stopped being live autopilots:
     *
     * <pre>
     * Helicopter #78 holding over helipad-4: the pad is occupied.     &lt;- pad empty
     * Helicopter #78 helipad-4 never became free - held over it for 3601 ticks
     * Helicopter #77 helipad-4 never became free - held over it for 3601 ticks
     * </pre>
     *
     * <p>So a machine in {@code CRUISE} or {@code HOLD} claims nothing: it is going there, which is
     * not the same as being there, and the pad is a square of ground rather than a slot in a queue.
     * From {@code DESCENT} onwards it is committed — it is inside the run-in, on the way down, and
     * anything else arriving has to wait — so it claims. The transition into {@code DESCENT} tests
     * the pad first and entities tick one at a time, so two machines cannot both commit in one tick.
     *
     * <p>The departure end is the other way round and always claimed, because a machine sitting on a
     * pad is on it whatever its mode says.
     */
    boolean claims(BlockPos spot) {
        BlockPos pad = claimedPad();
        return pad != null && pad.equals(spot);
    }

    @Nullable BlockPos claimedPad() {
        Helipad pad = switch (mode) {
            case PARKED, TAKEOFF -> departure;
            case DESCENT, FINAL, ROLLOUT -> destination;
            // CRUISE and HOLD claim nothing. See claims(BlockPos).
            default -> null;
        };
        return pad == null ? null : pad.centre();
    }

    @Nullable Helipad destination() {
        return destination;
    }

    // ------------------------------------------------------------------ the tick

    void tick(PlaneEntity plane) {
        ticks++;
        modeTicks++;
        Level level = plane.level();
        scanner.scan(level, plane.position(), plane.getYRot());

        switch (mode) {
            case PARKED -> tickOnPad(plane);
            case TAKEOFF -> tickLiftOff(plane);
            case CRUISE -> tickTransit(plane);
            case HOLD -> tickHold(plane);
            case DESCENT -> tickRunIn(plane);
            case FINAL -> tickLetDown(plane);
            case ROLLOUT -> tickSettle(plane);
            default -> host.stop(plane);
        }
        trace(plane);
    }

    /** Sitting on the departure pad: run the clock down, then lift. */
    private void tickOnPad(PlaneEntity plane) {
        hold(plane);
        if (departureDelayTicks > 0) {
            departureDelayTicks--;
            return;
        }
        report(plane, "lifting off from " + name(departure) + ".", false);
        liftOffTick = ticks;
        setMode(plane, AutopilotMode.TAKEOFF);
    }

    /**
     * Straight up, off the pad, to {@link RotorcraftConfig#DEPARTURE_HEIGHT}.
     *
     * <p>No translation at all until that height is reached. The survey guarantees the column above
     * the pad and a ring around it, and nothing else, so a departure that starts moving sideways
     * early is a departure over ground nobody measured.
     */
    private void tickLiftOff(PlaneEntity plane) {
        if (departure == null) {
            setMode(plane, AutopilotMode.CRUISE);
            return;
        }
        double target = departure.elevation() + RotorcraftConfig.DEPARTURE_HEIGHT;
        double heading = destination == null ? plane.getYRot()
            : AutopilotMath.headingTo(plane.position(), destination.touchdown());
        fly(plane, heading, target, 0.0, RotorcraftConfig.CLIMB_RATE, true);
        if (plane.getY() >= target - 1.0) {
            setMode(plane, AutopilotMode.CRUISE);
            return;
        }
        if (modeTicks > RotorcraftConfig.DEPARTURE_TIMEOUT) {
            fail(plane, String.format("could not get off %s - %.0f blocks up after %d ticks,"
                + " against the %.0f it needs", name(departure), plane.getY() - departure.elevation(),
                modeTicks, RotorcraftConfig.DEPARTURE_HEIGHT));
        }
    }

    /** En route: fly to the hover point at cruise altitude, terrain-following. */
    private void tickTransit(PlaneEntity plane) {
        transitTicks++;
        Vec3 position = plane.position();
        Vec3 hover = destination.approachPoint(position, RotorcraftConfig.HOVER_CAPTURE_RADIUS, 0);
        double distance = AutopilotMath.horizontalDistance(position, hover);

        double altitude = Math.max(cruiseAltitude, terrainFloor());
        double speed = distance < RotorcraftConfig.DECELERATION_DISTANCE
            ? Mth.lerp(distance / RotorcraftConfig.DECELERATION_DISTANCE,
                RotorcraftConfig.APPROACH_SPEED, cruiseSpeed)
            : cruiseSpeed;
        fly(plane, AutopilotMath.headingTo(position, hover), altitude, speed,
            RotorcraftConfig.CLIMB_RATE, false);
        checkSpeedShortfall(plane, speed);

        if (distance <= RotorcraftConfig.DECELERATION_DISTANCE) {
            if (!padAvailable(plane)) {
                setMode(plane, AutopilotMode.HOLD);
                return;
            }
            overheadTick = ticks;
            runInHeading = destination.arrivalHeading(position);
            report(plane, "running in to " + name(destination) + " on "
                + String.format("%03d", AutopilotMath.compassDisplay(runInHeading))
                + " deg, " + Math.round(distance) + " blocks out.", false);
            setMode(plane, AutopilotMode.DESCENT);
            return;
        }
        checkTransitTimeout(plane, distance);
    }

    /**
     * The run-in: down to the departure height above the pad while closing on the hover point.
     *
     * <p>This is the whole of the arrival geometry. A fixed-wing arrival has to arrive on a line,
     * at a height, at a speed, all three at once, which is why it needs a planner; a rotorcraft only
     * has to end up somewhere above the pad slow enough to stop, and the three demands are
     * independent.
     */
    private void tickRunIn(PlaneEntity plane) {
        Vec3 position = plane.position();
        Vec3 hover = destination.approachPoint(position, RotorcraftConfig.HOVER_CAPTURE_RADIUS, 0);
        double distance = AutopilotMath.horizontalDistance(position, hover);
        double target = Math.max(destination.elevation() + RotorcraftConfig.DEPARTURE_HEIGHT,
            terrainFloor());
        station(plane, hover, target, RotorcraftConfig.APPROACH_SPEED,
            RotorcraftConfig.DESCENT_RATE);

        double toPad = AutopilotMath.horizontalDistance(position, destination.touchdown());
        if (distance <= RotorcraftConfig.HOVER_CAPTURE_RADIUS
            || toPad <= RotorcraftConfig.HOVER_CAPTURE_RADIUS) {
            setMode(plane, AutopilotMode.FINAL);
            return;
        }
        if (modeTicks > RotorcraftConfig.DESCENT_TIMEOUT) {
            fail(plane, String.format("could not reach the hover point over %s - still %.0f blocks"
                + " short after %d ticks", name(destination), distance, modeTicks));
        }
    }

    /**
     * Overhead the pad: stop, then go down.
     *
     * <p>The descent is <em>gated on the lateral error</em> rather than being started once and
     * flown to the ground. A machine that drifts off the pad while descending stops descending and
     * re-centres, which is what a pilot does and what makes the touchdown coordinate agree with the
     * pad the survey registered. Without the gate the arrival lands wherever the drift left it and
     * reports it as a landing — the fixed-wing equivalent of that is written up in
     * {@code AUTOPILOT.md} as an aircraft reporting {@code landed} after ditching in the sea.
     */
    private void tickLetDown(PlaneEntity plane) {
        Vec3 position = plane.position();
        Vec3 pad = destination.touchdown();
        double lateral = AutopilotMath.horizontalDistance(position, pad);
        double height = position.y - destination.elevation();
        double tolerance = destination.landingTolerance();

        boolean centred = lateral <= tolerance;
        boolean slow = plane.getDeltaMovement().horizontalDistance() <= RotorcraftConfig.HOVER_SPEED;
        double descentRate = height <= RotorcraftConfig.TOUCHDOWN_HEIGHT
            ? RotorcraftConfig.TOUCHDOWN_RATE : RotorcraftConfig.DESCENT_RATE;
        // Hold height until the machine is over the pad and has stopped; then let down, and stop
        // letting down again if it drifts back off. Asking for one block *below* the pad rather than
        // for the pad itself is what makes the last block of the descent happen at all: the altitude
        // loop's demand fades to zero as the error does, and a demand that ends exactly on the
        // surface leaves the machine hovering a fraction of a block above it for ever.
        double targetAltitude = centred && slow
            ? destination.elevation() - 1.0
            : destination.elevation() + Math.max(height, RotorcraftConfig.TOUCHDOWN_HEIGHT + 1.0);
        // The nose stays on the bearing the run-in was flown on for the whole let-down. See
        // station(..., holdHeading).
        station(plane, pad, targetAltitude, RotorcraftConfig.APPROACH_SPEED, descentRate,
            runInHeading);

        if (plane.getOnGround() || plane.isOnWater()) {
            setMode(plane, AutopilotMode.ROLLOUT);
            return;
        }
        if (modeTicks > RotorcraftConfig.DESCENT_TIMEOUT) {
            fail(plane, String.format("could not settle onto %s - %.1f blocks off the pad centre and"
                + " %.1f blocks up after %d ticks", name(destination), lateral, height, modeTicks));
        }
    }

    /** On the ground. Shut the throttle, wait for it to stop moving, then say what happened. */
    private void tickSettle(PlaneEntity plane) {
        hold(plane);
        if (!plane.getOnGround() && !plane.isOnWater()) {
            settledTicks = 0;
            if (modeTicks > RotorcraftConfig.SETTLED_TICKS * 4) {
                setMode(plane, AutopilotMode.FINAL);
            }
            return;
        }
        if (++settledTicks < RotorcraftConfig.SETTLED_TICKS) {
            return;
        }
        finish(plane);
    }

    /**
     * Orbiting above an occupied pad.
     *
     * <p>A circle rather than a hover, and above the departure height rather than at it, so two
     * machines waiting for the same pad are not in the same block of air. It is the thinnest
     * possible version of the fixed-wing hold and it is here for one reason: without it, an arrival
     * onto an occupied pad lands on top of whatever is standing there.
     */
    private void tickHold(PlaneEntity plane) {
        transitTicks++;
        Vec3 pad = destination.touchdown();
        // Separated in the stack by entity id, exactly as the fixed-wing hold is, and for the same
        // reason: helicopters are hard-colliding entities and PlaneCollisions reads a blocked move()
        // as an impact. Without this two machines waiting for one pad chase the same walking point at
        // the same height and converge on it — measured at 1618.3, 17.1 for both, 0.3 blocks apart
        // vertically, which is not separation from anything. The slot sets both the level and the
        // starting angle, so two machines are 10 blocks apart vertically and a quarter of the orbit
        // apart horizontally before either has moved.
        int slot = Math.floorMod(plane.getId(), RotorcraftConfig.HOLD_STACK_SLOTS);
        double altitude = pad.y + RotorcraftConfig.DEPARTURE_HEIGHT + RotorcraftConfig.HOLD_HEIGHT
            + slot * RotorcraftConfig.HOLD_LEVEL_SPACING;
        // Chase a point walking round the pad, on the station-keeping law rather than the bearing
        // one. With the bearing law the machine cannot keep up with a moving target and simply drifts
        // to the middle: measured, a hold that should have been 30 blocks out sat 8.9 blocks from the
        // pad centre for the whole wait, which is not a separation from anything. The orbit rate is
        // sized so the point moves slower than the machine can fly - 0.8 deg/tick on a 30-block
        // radius is 0.42 blocks/tick.
        double angle = (modeTicks * RotorcraftConfig.HOLD_TURN_RATE
            + slot * (360.0 / RotorcraftConfig.HOLD_STACK_SLOTS)) % 360.0;
        Vec3 point = AutopilotMath.pointAlong(pad, angle, RotorcraftConfig.HOLD_RADIUS);
        station(plane, point, Math.max(altitude, terrainFloor()), RotorcraftConfig.APPROACH_SPEED,
            RotorcraftConfig.CLIMB_RATE);

        if (!padWaitReported) {
            padWaitReported = true;
            report(plane, "holding over " + name(destination) + ": the pad is occupied.", false);
        }
        if (modeTicks % RotorcraftConfig.PAD_POLL_INTERVAL == 0 && padAvailable(plane)) {
            overheadTick = ticks;
            runInHeading = destination.arrivalHeading(plane.position());
            setMode(plane, AutopilotMode.DESCENT);
            return;
        }
        if (modeTicks > RotorcraftConfig.HOLD_TIMEOUT) {
            fail(plane, name(destination) + " never became free - held over it for "
                + modeTicks + " ticks");
        }
    }

    /**
     * Says so, once, when the machine is being asked for a cruise it physically cannot hold.
     *
     * <p>Level flight at full forward cyclic wants collective 3.31 and the collective is an integer,
     * so the loop dithers 3/4 and the machine tops out around 1.10 blocks/tick
     * ({@code HELICOPTER-PHYSICS.md} §3). {@code /autopilot heliflight … 1.75} is inside the
     * argument's range, is accepted, is echoed back in the launch line, and then flies 1.10 — and
     * before this method existed nothing anywhere said so. Measured: a 2200-block leg ordered at
     * 1.75 and one ordered at 1.20 took 2553 and 2549 ticks, i.e. the same flight with two different
     * numbers printed on it.
     *
     * <p>The test is deliberately "the stick is on its stop <em>and</em> the speed is short", not
     * "the speed is short": a machine that is short because it is climbing over a ridge, turning, or
     * still accelerating out of the departure is not being lied to about anything, and its stick is
     * not saturated. {@link #SHORTFALL_SETTLE_TICKS} keeps the acceleration out of it as well.
     */
    private void checkSpeedShortfall(PlaneEntity plane, double demanded) {
        if (speedShortfallReported || modeTicks < SHORTFALL_SETTLE_TICKS || demanded <= 0) {
            return;
        }
        double made = plane.getDeltaMovement().horizontalDistance();
        if (!cyclicSaturated || made >= demanded * SHORTFALL_FRACTION) {
            return;
        }
        speedShortfallReported = true;
        report(plane, String.format("cannot make good %.2f blocks/tick in level flight - full"
            + " forward cyclic is holding %.2f. The leg will take that much longer.",
            demanded, made), false);
    }

    /** Ticks of cruise before the speed shortfall is judged, so acceleration is not reported as one. */
    private static final int SHORTFALL_SETTLE_TICKS = 200;

    /** Fraction of the demand below which the shortfall is worth a line. */
    private static final double SHORTFALL_FRACTION = 0.95;

    private boolean padAvailable(PlaneEntity plane) {
        return destination == null || destination.free(plane.level(), plane);
    }

    private void checkTransitTimeout(PlaneEntity plane, double remaining) {
        double legTicks = AutopilotMath.horizontalDistance(
            departure == null ? plane.position() : departure.touchdown(), destination.touchdown())
            / Math.max(cruiseSpeed, 0.05);
        int allowed = (int) (legTicks * RotorcraftConfig.TRANSIT_TIMEOUT_FACTOR)
            + RotorcraftConfig.TRANSIT_TIMEOUT_MARGIN;
        if (transitTicks > allowed) {
            fail(plane, String.format("gave up en route to %s - %.0f blocks still to run after %d"
                + " ticks, against the %d a straight leg at %.2f blocks/tick needs",
                name(destination), remaining, transitTicks, allowed, cruiseSpeed));
        }
    }

    // ------------------------------------------------------------------ outcomes

    /**
     * The end of a flight that reached the ground, told truthfully.
     *
     * <p>Two outcomes, and the difference between them is <em>measured</em> rather than the mode the
     * machine happened to be in. "It came down" and "it landed on the pad" are not the same event and
     * this feature is not going to print the second when the first is what happened.
     */
    private void finish(PlaneEntity plane) {
        Vec3 position = plane.position();
        Vec3 pad = destination.touchdown();
        double miss = AutopilotMath.horizontalDistance(position, pad);
        double tolerance = destination.landingTolerance();
        String where = String.format("%.1f, %.1f, %.1f", position.x, position.y, position.z);
        String timings = (liftOffTick >= 0 ? (ticks - liftOffTick) + " ticks from lift-off, " : "")
            + (overheadTick >= 0 ? (ticks - overheadTick) + " ticks from the run-in" : ticks + " ticks");
        String problem = landingProblem(plane, miss, tolerance);
        reported = true;
        if (problem == null) {
            AutopilotFeedback.report(host.owner(), "Helicopter #" + plane.getId() + " landed at "
                + destination.name() + ", " + where + String.format(" (%.2f blocks from the pad centre "
                + "%.1f, %.1f, %.1f, tolerance %.1f; ", miss, pad.x, pad.y, pad.z, tolerance)
                + timings + ").");
            if (plane.level() instanceof ServerLevel serverLevel) {
                StandOccupancy.take(serverLevel, destination.name(), destination.centre(), plane);
            }
        } else {
            AutopilotFeedback.report(host.owner(), "Helicopter #" + plane.getId()
                + " did not land on " + destination.name() + ": came to rest " + problem
                + ", at " + where
                + String.format(" (pad centre %.1f, %.1f, %.1f, tolerance %.1f). ",
                    pad.x, pad.y, pad.z, tolerance)
                + timings + ".");
        }
        host.stop(plane);
    }

    /**
     * Why this is not a landing on the pad, or null when it is.
     *
     * <p><b>The height check is the whole reason this method exists</b>, and it is here because the
     * version without it printed a false landing on the rig. A pad was surveyed clear, a stone roof
     * was then built 16 blocks over it, and the arrival flew a perfect approach, settled on the roof
     * and reported:
     *
     * <pre>Helicopter #1 landed at helipad-6, 2800.5, -44.0, 0.5 (0.03 blocks from the pad centre 2800.5, -60.0, 0.5, …)</pre>
     *
     * <p>0.03 blocks from the centre, standing on something sixteen blocks above it, and the word in
     * the line is "landed". The two coordinates in that sentence contradict each other and nothing
     * was looking at the pair. This is the rotorcraft form of the plane that reported {@code landed}
     * after ditching in the sea: a horizontal test passed and there was no vertical one.
     *
     * <p>The fixed-wing side has had {@code PlaneAutopilot#landingProblem} — with exactly this
     * elevation term — since that bug was fixed there, so this is the same rule and the same
     * tolerance ({@link AutopilotConfig#LANDING_ELEVATION_TOLERANCE}) rather than a second opinion
     * about what "on the surface" means.
     */
    private @Nullable String landingProblem(PlaneEntity plane, double miss, double tolerance) {
        if (plane.isOnWater()) {
            return "in the water";
        }
        if (!plane.getOnGround()) {
            return "in the air";
        }
        double drop = plane.getY() - destination.elevation();
        if (Math.abs(drop) > AutopilotConfig.LANDING_ELEVATION_TOLERANCE) {
            return String.format("%.0f blocks %s the pad surface - on something the survey did not"
                + " measure", Math.abs(drop), drop < 0 ? "below" : "above");
        }
        if (miss > tolerance) {
            return String.format("%.1f blocks from the pad centre", miss);
        }
        return null;
    }

    /** A flight that failed in the air, with the reason and the position. */
    private void fail(PlaneEntity plane, String reason) {
        reported = true;
        Vec3 position = plane.position();
        AutopilotFeedback.report(host.owner(), "Helicopter #" + plane.getId() + " "
            + reason + ", at " + String.format("%.1f, %.1f, %.1f", position.x, position.y, position.z)
            + " in " + mode.getName() + ".");
        host.stop(plane);
    }

    /**
     * The outcome when the machine goes away without any of the above running — destroyed, killed,
     * stopped by hand.
     *
     * <p>Called from {@code PlaneEntity#remove} through {@code PlaneAutopilot#reportOutcome}. Without
     * it the only observable difference between "landed", "flew into a hill" and "never spawned" is
     * that the machine is no longer in the status list, which is exactly the complaint the
     * fixed-wing reporting was rewritten to answer.
     */
    void reportOutcome(PlaneEntity plane) {
        if (reported) {
            return;
        }
        reported = true;
        Vec3 position = plane.position();
        double remaining = destination == null ? -1
            : AutopilotMath.horizontalDistance(position, destination.touchdown());
        AutopilotFeedback.report(host.owner(), "Helicopter #" + plane.getId() + " lost at "
            + String.format("%.1f, %.1f, %.1f", position.x, position.y, position.z)
            + " in " + mode.getName()
            + (remaining < 0 ? "" : String.format(", %.0f blocks short of %s",
                remaining, destination.name())) + ".");
    }

    // ------------------------------------------------------------------ the control loops

    /**
     * The transit law: point the nose where you are going and ask for a speed.
     *
     * <p>Right while the target is hundreds of blocks away, and only then — see {@link #station} for
     * what replaces it once the target is close.
     *
     * @param heading       Minecraft yaw the machine should point along
     * @param altitude      altitude it should hold
     * @param groundSpeed   speed it should be making good along the nose, blocks per tick
     * @param verticalLimit largest vertical rate this phase may command, blocks per tick
     * @param boost         true while the collective boost is wanted, i.e. on a vertical departure
     */
    private void fly(PlaneEntity plane, double heading, double altitude, double groundSpeed,
                     double verticalLimit, boolean boost) {
        cmdHeading = heading;
        cmdAltitude = altitude;
        cmdVerticalSpeed = verticalDemand(plane, altitude, verticalLimit);

        // Do not try to translate while the nose is a long way off. Not because the airframe cannot
        // — this one accelerates in whatever direction it is pointing — but because a machine that
        // accelerates through a 120-degree turn arrives at the turn's far side rather than at the
        // point it was aiming at.
        double headingError = AutopilotMath.angleDelta(plane.getYRot(), heading);
        cmdGroundSpeed = Math.abs(headingError) > RotorcraftConfig.TURN_FIRST_ERROR ? 0.0 : groundSpeed;

        collective(plane, cmdVerticalSpeed, boost);
        pedal(plane, headingError);
        // The velocity this leg wants, as a world vector along the commanded heading. Trimmed on the
        // same integrator the arrival uses — one law, one frame — and applied with the lateral stick
        // suppressed, because at transit speed a bank is a turn rather than a sidestep
        // (HelicopterEntity gates the bank-to-turn term on the forward component of the velocity)
        // and the pedal is this airframe's turn control.
        Vec3 wanted = AutopilotMath.pointAlong(Vec3.ZERO, heading, cmdGroundSpeed);
        Vec3 velocity = plane.getDeltaMovement();
        trim(wanted.x - velocity.x, wanted.z - velocity.z);
        cyclic(plane, false);
    }

    /**
     * Station keeping: get to a point and stop there, on both body axes at once.
     *
     * <h2>Why this is a different law from {@link #fly}, and not a slower version of it</h2>
     * {@code fly} points the nose at the target and asks for a speed, which is exactly right while
     * the target is a long way off and completely wrong once it is not. Measured on the rig against
     * the <em>previous</em> flight model: a machine arriving over a pad on the "point at it and fly
     * 0.35" law never got closer than 10.5 blocks. It orbited the pad for the whole 2400-tick
     * descent timeout and reported, correctly, that it could not settle.
     *
     * <p>What made that an orbit rather than a wobble was that the only translational control was
     * "accelerate along the nose", so correcting a lateral error meant turning — and the machine kept
     * its old velocity while it turned. The rewritten airframe has a second axis:
     * {@code setCyclicRight} tips the disc sideways, and below
     * {@code HelicopterEntity.TURN_COORDINATION_SPEED} (0.80 b/t of <em>forward</em> speed) that is a
     * pure sidestep with no turn in it at all. An arrival flown at
     * {@link RotorcraftConfig#APPROACH_SPEED} is comfortably inside that band.
     *
     * <p>So the law is: take the velocity the machine wants — towards the point, at a speed
     * proportional to how far away it is — subtract the velocity it has, resolve the difference into
     * the two body axes, and put each axis on its own cyclic. The nose is left pointing at the target
     * and never has to be turned to correct a drift. Braking is the same command with the sign
     * reversed, which on a position-command cyclic is simply a negative stick: full aft is 24
     * blocks/s to a stop in 60 ticks and 43 blocks (HELICOPTER-PHYSICS.md §3), so there is no
     * separate deceleration schedule anywhere in this arrival.
     */
    private void station(PlaneEntity plane, Vec3 target, double altitude, double maxSpeed,
                         double verticalLimit) {
        station(plane, target, altitude, maxSpeed, verticalLimit, Double.NaN);
    }

    /**
     * @param holdHeading the heading to keep the nose on, or {@link Double#NaN} to point it at the
     *                    target. <b>Not a refinement — the let-down does not work without it.</b>
     *                    Chasing the bearing to a point the machine is nearly standing over means
     *                    that the moment it overshoots by a block, the bearing reverses and the
     *                    controller asks for a 180-degree turn. Measured on the rig with the run-in
     *                    bearing not held: an arrival overshot the pad by 3.2 blocks (the stopping
     *                    distance from {@link RotorcraftConfig#APPROACH_SPEED}), the nose then swung
     *                    through 180 degrees at the pedal's 3 deg/tick, and the machine wandered
     *                    603.7 -> 598.0 -> 601.3 on the x axis before it settled — 298 ticks in
     *                    {@code FINAL} against the 146 the commanded descent rates need.
     *                    {@link RotorcraftConfig#STATION_POINT_RADIUS} was supposed to stop that and
     *                    cannot: it silences the nose only inside 2.5 blocks, and the overshoot that
     *                    starts the spin is bigger than that. Holding the run-in bearing instead
     *                    removes the turn entirely, and costs nothing, because the lateral cyclic
     *                    corrects the drift without the nose having to move at all.
     */
    private void station(PlaneEntity plane, Vec3 target, double altitude, double maxSpeed,
                         double verticalLimit, double holdHeading) {
        Vec3 position = plane.position();
        cmdAltitude = altitude;
        cmdVerticalSpeed = verticalDemand(plane, altitude, verticalLimit);

        double dx = target.x - position.x;
        double dz = target.z - position.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        // Constant-deceleration closure: the fastest the machine may be going at this distance if it
        // is to stop on the point. See RotorcraftConfig#CLOSURE_BRAKING for the 2x2 that chose this
        // shape over the proportional one it replaced.
        double wanted = Math.min(maxSpeed,
            Math.sqrt(2.0 * RotorcraftConfig.CLOSURE_BRAKING * distance));
        cmdGroundSpeed = wanted;
        double wx = distance > 1.0E-4 ? dx / distance * wanted : 0;
        double wz = distance > 1.0E-4 ? dz / distance * wanted : 0;

        Vec3 velocity = plane.getDeltaMovement();
        double ex = wx - velocity.x;
        double ez = wz - velocity.z;

        // Point at the target while there is a direction to point in. A nose chasing a point it is
        // standing over hunts, which is the same reason the fixed-wing taxi stops chasing its lineup
        // point once it is nearly on it — and inside the let-down the caller hands over a fixed
        // bearing instead, because that deadband alone is not enough. See the parameter.
        double heading = !Double.isNaN(holdHeading) ? holdHeading
            : distance > RotorcraftConfig.STATION_POINT_RADIUS
                ? AutopilotMath.headingTo(position, target) : plane.getYRot();
        cmdHeading = heading;
        double headingError = AutopilotMath.angleDelta(plane.getYRot(), heading);

        collective(plane, cmdVerticalSpeed, false);
        pedal(plane, headingError);
        trim(ex, ez);
        cyclic(plane, true);
    }

    /** Altitude error to a vertical-speed demand, clamped by the phase. */
    private double verticalDemand(PlaneEntity plane, double altitude, double limit) {
        double error = altitude - plane.getY();
        return Mth.clamp(error * RotorcraftConfig.ALTITUDE_TO_VERTICAL_SPEED, -limit, limit);
    }

    /** Everything shut: on the pad, or settling onto it. */
    private void hold(PlaneEntity plane) {
        cmdHeading = plane.getYRot();
        cmdAltitude = plane.getY();
        cmdVerticalSpeed = 0;
        cmdGroundSpeed = 0;
        plane.setThrottle(0);
        plane.setYawRight((byte) 0);
        host.setRotorControls(0, 0);
        cyclicWorldX = 0;
        cyclicWorldZ = 0;
        cyclicTrimX = 0;
        cyclicTrimZ = 0;
        if (plane instanceof HelicopterEntity helicopter) {
            helicopter.setCyclicForward(0);
            helicopter.setCyclicRight(0);
            helicopter.setCollectiveBoost(false);
        }
    }

    // ------------------------------------------------------------------ the three actuators

    /*
     * Everything below here is what knows about the flight model, and nothing above it is. The laws
     * are written in blocks per tick and degrees; these methods are the only place that knows what a
     * notch, a percent of cyclic or a pedal sign is.
     *
     * setPitchUp is never called on a helicopter. It does nothing on this airframe and its sign
     * convention is the opposite of the cyclic's, so a controller that reached for it out of
     * fixed-wing habit would be writing into a control that is both dead and backwards.
     */

    /**
     * Collective: find the notch whose equilibrium vertical speed is the one being asked for.
     *
     * <p><b>A search, not a PID — and not a table either.</b> HELICOPTER-PHYSICS.md §2 measures the
     * ladder: notches 0 to 5 settle at −8.6, −6.2, −3.5, 0.0, +2.7, +4.8 blocks per second, exactly,
     * with 0.000 blocks of drift in 400 ticks at the hover notch. So "pick the notch nearest the
     * demand" is the whole vertical controller. Copying that table in here would be the wrong way to
     * use it: those are the equilibria at a <em>level</em> disc, and level flight at 25 degrees of
     * tilt wants notch 3.31, which is not a notch at all. Searching for the notch instead finds 3 in
     * a hover, dithers 3/4 in the cruise — which is what §3 says to do and what the fixed-wing
     * throttle loop already does — and needs no revision if the ladder moves.
     *
     * <p>The search is one notch every {@link RotorcraftConfig#COLLECTIVE_INTERVAL} ticks, which
     * reaches any notch from any other inside 10 ticks, with a one-step slam at
     * {@link RotorcraftConfig#VERTICAL_SPEED_SLAM} for the case that does not have 10 ticks.
     */
    private void collective(PlaneEntity plane, double demand, boolean boost) {
        if (plane instanceof HelicopterEntity helicopter) {
            helicopter.setCollectiveBoost(boost);
        }
        int ceiling = maxThrottle(plane);
        int throttle = plane.getThrottle();
        double error = demand - verticalSpeed(plane);
        if (error > RotorcraftConfig.VERTICAL_SPEED_SLAM) {
            throttle = ceiling;
        } else if (error < -RotorcraftConfig.VERTICAL_SPEED_SLAM) {
            throttle = 0;
        } else if (ticks % RotorcraftConfig.COLLECTIVE_INTERVAL == 0) {
            if (error > RotorcraftConfig.VERTICAL_SPEED_DEADBAND) {
                throttle++;
            } else if (error < -RotorcraftConfig.VERTICAL_SPEED_DEADBAND) {
                throttle--;
            }
        }
        plane.setThrottle(Mth.clamp(throttle, 0, ceiling));
    }

    /**
     * Pedal: bang-bang with the angular stopping distance, unchanged from the fixed-wing rudder.
     *
     * <p>{@code setPedal} is a sign rather than a proportion, and deliberately: it drives an
     * integrator with a {@code YAW_RAMP} of 0.5 deg/tick squared, which is the same double-integrator
     * shape {@code PlaneEntity#tickYaw} has. So {@link AutopilotMath#bangBang} — which subtracts
     * {@code rate * |rate| / (2 * accel)} from the error so the controller starts braking at exactly
     * the right moment — is the correct controller for it with not a line of change. A proportional
     * law here would be the wrong shape and would hunt.
     *
     * <p>The rate is measured here rather than read off the entity: the flight director runs at the
     * top of {@code PlaneEntity#tick}, and whether {@code yRotO} has been refreshed by then is a
     * detail of the entity's tick order.
     */
    private void pedal(PlaneEntity plane, double headingError) {
        double rate = yawInitialised ? Mth.wrapDegrees(plane.getYRot() - previousYaw) : 0.0;
        previousYaw = plane.getYRot();
        yawInitialised = true;
        byte command = AutopilotMath.bangBang(headingError, rate, HelicopterEntity.YAW_RAMP,
            RotorcraftConfig.HEADING_DEADBAND);
        if (plane instanceof HelicopterEntity helicopter) {
            helicopter.setPedal(command);
        } else {
            plane.setYawRight(command);
        }
    }

    /**
     * Cyclic: a position command on both axes, so the demand is a stick position and not a nudge.
     *
     * <p>This is the control that makes the arrival simple. Holding the stick buys a fixed disc
     * tilt, therefore a fixed thrust component, therefore — through the drag curve — a fixed speed;
     * so a speed error maps straight onto a stick position and the loop is proportional with no
     * integrator anywhere. Both axes are driven in {@link #station}; only the longitudinal one in
     * {@link #fly}.
     */
    private void cyclic(PlaneEntity plane, boolean lateral) {
        // moveForward/moveStrafing were the old helicopter's translation inputs and this airframe
        // does not read them at all. Zeroed rather than ignored, so nothing stale can leak through
        // the bridge PlaneEntity#tick still reads them from.
        host.setRotorControls(0, 0);
        if (!(plane instanceof HelicopterEntity helicopter)) {
            return;
        }
        // World into body. Minecraft yaw 0 is +Z, so the nose unit vector is (-sin yaw, cos yaw) and
        // "right", which is yaw + 90, is (-cos yaw, -sin yaw).
        double yaw = Math.toRadians(plane.getYRot());
        double along = cyclicWorldZ * Math.cos(yaw) - cyclicWorldX * Math.sin(yaw);
        double across = -cyclicWorldX * Math.cos(yaw) - cyclicWorldZ * Math.sin(yaw);
        helicopter.setCyclicForward((int) Math.round(along));
        helicopter.setCyclicRight(lateral ? (int) Math.round(across) : 0);
    }

    /**
     * Moves one cyclic stick to reduce a velocity error. <b>An integrator, not a gain.</b>
     *
     * <p>The first version of this was proportional — stick position straight from the speed error —
     * and it is worth recording why that is wrong, because the reasoning is the whole difference
     * between a rate command and a position command. Cyclic is a position command: hold the stick
     * and the machine settles at a speed. So a proportional law {@code stick = G * (demand - v)}
     * closes a loop whose plant already has a finite gain {@code v = k * stick}, and its equilibrium
     * is {@code v = demand * kG / (1 + kG)} — a permanent shortfall, not an offset that decays.
     * Measured on the rig with G = 160 and the airframe's k of about 0.0125: a cruise commanded at
     * 1.20 blocks/tick flew <b>0.815</b>, which is 0.68 of the demand, and the predicted ratio for
     * that loop gain is 0.67.
     *
     * <p>Integrating the error onto the tilt instead has its equilibrium where the error is zero,
     * whatever the plant gain is — so it holds the speed it was told, at whatever collective notch
     * the vertical loop happens to be dithering on, and it needs no revision if the drag curve or the
     * disc angle changes. It cannot wind up either, because the clamp is the actuator's own limit
     * rather than an arbitrary one, and the entity rate-limits the disc to
     * {@code MAX_CYCLIC_RATE} anyway, so an over-eager step is absorbed rather than flown.
     *
     * <p><b>And it cannot be only an integrator, which is the second thing this method got wrong.</b>
     * The chain from tilt to position is already two integrations — tilt sets an acceleration,
     * acceleration integrates to velocity, velocity integrates to position — and making the inner
     * loop a third put 270 degrees of phase lag round a loop that also has a proportional outer
     * position law. Measured: an arrival with the pure integrator held station to within 2 to 3.5
     * blocks of the pad and oscillated there at 0.1 to 0.2 blocks/tick for the whole 2400-tick
     * descent timeout, never slow enough to be allowed to let down. The proportional term is a
     * velocity damper and is what stops that; the integral is left in, small, purely to remove the
     * shortfall on a constant demand.
     *
     * <p>The deadband stops the integration and centres the proportional part, so a machine holding a
     * cruise speed correctly keeps the trim it has found and adds nothing to it.
     */
    private void trim(double errorX, double errorZ) {
        double magnitude = Math.sqrt(errorX * errorX + errorZ * errorZ);
        double px = 0;
        double pz = 0;
        if (magnitude >= RotorcraftConfig.STATION_DEADBAND) {
            px = errorX * RotorcraftConfig.CYCLIC_SPEED_GAIN;
            pz = errorZ * RotorcraftConfig.CYCLIC_SPEED_GAIN;
            cyclicTrimX += errorX * RotorcraftConfig.CYCLIC_TRIM_GAIN;
            cyclicTrimZ += errorZ * RotorcraftConfig.CYCLIC_TRIM_GAIN;
            double trim = Math.sqrt(cyclicTrimX * cyclicTrimX + cyclicTrimZ * cyclicTrimZ);
            if (trim > HelicopterEntity.CYCLIC_FULL) {
                cyclicTrimX *= HelicopterEntity.CYCLIC_FULL / trim;
                cyclicTrimZ *= HelicopterEntity.CYCLIC_FULL / trim;
            }
        }
        cyclicWorldX = cyclicTrimX + px;
        cyclicWorldZ = cyclicTrimZ + pz;
        // Clamp the vector, not each axis: the disc has one tilt and it is bounded by its magnitude,
        // so clamping x and z separately would let a diagonal command ask for 1.41 times full stick.
        double stick = Math.sqrt(cyclicWorldX * cyclicWorldX + cyclicWorldZ * cyclicWorldZ);
        cyclicSaturated = stick >= HelicopterEntity.CYCLIC_FULL;
        if (stick > HelicopterEntity.CYCLIC_FULL) {
            double scale = HelicopterEntity.CYCLIC_FULL / stick;
            cyclicWorldX *= scale;
            cyclicWorldZ *= scale;
        }
    }

    private static double verticalSpeed(PlaneEntity plane) {
        return plane instanceof HelicopterEntity helicopter
            ? helicopter.getVerticalSpeed() : plane.getDeltaMovement().y;
    }

    /**
     * Throttle ceiling for this machine. The spawner fits a booster to everything it creates, which
     * raises it from 5 to 10, but a flight reloaded onto a machine whose booster was removed has to
     * see the real number — the same reason {@code PlaneAutopilot} asks rather than assumes.
     */
    private static int maxThrottle(PlaneEntity plane) {
        return plane.upgrades.containsKey(SimplePlanesRegistries.UPGRADE_TYPE.getKey(
            SimplePlanesUpgrades.BOOSTER.get())) ? BoosterUpgrade.MAX_THROTTLE : PlaneEntity.MAX_THROTTLE;
    }

    /** Lowest altitude that clears the terrain the scanner can see, or the plan's when it sees none. */
    private double terrainFloor() {
        double safe = scanner.safeAltitude();
        return safe == TerrainScanner.UNKNOWN_HEIGHT ? cruiseAltitude
            : Math.max(safe - AutopilotConfig.TERRAIN_CLEARANCE + RotorcraftConfig.CRUISE_CLEARANCE,
                cruiseAltitude);
    }

    // ------------------------------------------------------------------ readouts

    private void setMode(PlaneEntity plane, AutopilotMode next) {
        if (mode == next) {
            return;
        }
        mode = next;
        modeTicks = 0;
        settledTicks = 0;
        AutopilotFeedback.mode(host.owner(), plane, next);
    }

    private static String name(@Nullable Helipad pad) {
        return pad == null ? "?" : pad.name();
    }

    private void report(PlaneEntity plane, String message) {
        report(plane, message, true);
    }

    private void report(PlaneEntity plane, String message, boolean terminal) {
        AutopilotFeedback.report(host.owner(), "Helicopter #" + plane.getId() + " " + message);
        if (terminal) {
            reported = true;
        }
    }

    String statusLine(PlaneEntity plane) {
        Vec3 position = plane.position();
        Vec3 velocity = plane.getDeltaMovement();
        StringBuilder builder = new StringBuilder();
        builder.append('#').append(plane.getId()).append(" helicopter ").append(mode.getName())
            .append(String.format(" pos=%.0f,%.0f,%.0f", position.x, position.y, position.z))
            .append(String.format(" hdg=%03d", AutopilotMath.compassDisplay(plane.getYRot())))
            .append(String.format(" spd=%.2f vs=%+.2f", velocity.horizontalDistance(), velocity.y))
            .append(" thr=").append(plane.getThrottle())
            .append(String.format(" want[hdg=%03d alt=%.0f spd=%.2f vs=%+.2f]",
                AutopilotMath.compassDisplay(cmdHeading), cmdAltitude, cmdGroundSpeed, cmdVerticalSpeed));
        if (destination != null) {
            Vec3 pad = destination.touchdown();
            builder.append(" pad=").append(destination.name())
                .append(String.format(" at=%.0f,%.0f,%.0f dist=%.1f agl=%.1f",
                    pad.x, pad.y, pad.z, AutopilotMath.horizontalDistance(position, pad),
                    position.y - pad.y));
        }
        if (mode == AutopilotMode.PARKED && departureDelayTicks > 0) {
            builder.append(" wait=clock ").append(TowerWatch.clock(departureDelayTicks));
        }
        return builder.toString();
    }

    Component planComponent() {
        if (destination == null) {
            return Component.literal("no destination");
        }
        return Component.literal(departure == null
            ? "inbound to " + destination.name()
            : name(departure) + " -> " + destination.name());
    }

    String describe(PlaneEntity plane) {
        return "Helicopter #" + plane.getId() + " mode=" + mode.getName()
            + ", " + planComponent().getString();
    }

    private void trace(PlaneEntity plane) {
        if (!TRACE) {
            return;
        }
        Vec3 position = plane.position();
        double distance = destination == null ? -1
            : AutopilotMath.horizontalDistance(position, destination.touchdown());
        // The two sticks are in the trace because every arrival defect this controller has had so
        // far was a stick that was somewhere other than where the numbers above it suggested — the
        // saturated cruise, the wound-up integrator and the limit cycle all read as ordinary
        // telemetry without them.
        int along = plane instanceof HelicopterEntity helicopter ? helicopter.getCyclicForward() : 0;
        int across = plane instanceof HelicopterEntity helicopter ? helicopter.getCyclicRight() : 0;
        LOGGER.info(String.format(
            "trace #%d t=%d %s pos=%.2f,%.2f,%.2f spd=%.3f vs=%+.3f thr=%d hdg=%.1f cmdhdg=%.1f"
                + " cmdalt=%.1f cmdvs=%+.3f cmdspd=%.2f cyc=%+d,%+d sat=%b dpad=%.2f og=%b",
            plane.getId(), ticks, mode.getName(), position.x, position.y, position.z,
            plane.getDeltaMovement().horizontalDistance(), plane.getDeltaMovement().y,
            plane.getThrottle(), Mth.wrapDegrees(plane.getYRot()), Mth.wrapDegrees(cmdHeading),
            cmdAltitude, cmdVerticalSpeed, cmdGroundSpeed, along, across, cyclicSaturated,
            distance, plane.getOnGround()));
    }
}
