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
 * <b>Quantities, not constants.</b> The helicopter flight model is being replaced, so anything tuned
 * against the numbers this airframe produces today would have to be re-tuned tomorrow. Every loop
 * here therefore closes on something measurable — vertical speed, ground speed, heading — and drives
 * the same controls a player has until the measurement matches the demand:
 *
 * <ul>
 *   <li><b>Collective / throttle</b> integrates the vertical-speed error. Whatever thrust a notch is
 *       worth, the integrator finds the notch that holds the demanded rate, so a model that changes
 *       the thrust per notch changes only how long it takes to settle.</li>
 *   <li><b>Cyclic / {@code moveForward}</b> is the forward-acceleration demand, switched with
 *       hysteresis on the ground-speed error.</li>
 *   <li><b>Pedals / {@code moveStrafing}</b> are the yaw demand, proportional to the heading error
 *       with a rate lead so the same law works whether yaw rate follows the input directly (as it
 *       does today) or through an acceleration (as a plane's does).</li>
 * </ul>
 *
 * <p><b>The one place the current model shows through</b> is {@link #actuate}, which maps those
 * three demands onto the entity's controls, and it is deliberately the only place. Today's
 * {@code HelicopterEntity} turns on {@code moveStrafing} and only while {@code MOVE_UP} is false, and
 * accelerates by pitching its nose down when {@code moveForward} is positive; the sign conventions
 * and that coupling are the merge point when the new model lands. Everything above {@code actuate}
 * is written in blocks per tick and degrees.
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

    /** Latched cyclic state, so the forward demand does not chatter across its deadband. */
    private boolean translating;

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
     */
    boolean claims(BlockPos spot) {
        Helipad pad = mode == AutopilotMode.PARKED || mode == AutopilotMode.TAKEOFF
            ? departure : destination;
        return pad != null && pad.centre().equals(spot);
    }

    @Nullable BlockPos claimedPad() {
        Helipad pad = mode == AutopilotMode.PARKED || mode == AutopilotMode.TAKEOFF
            ? departure : destination;
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

        if (distance <= RotorcraftConfig.DECELERATION_DISTANCE) {
            if (!padAvailable(plane)) {
                setMode(plane, AutopilotMode.HOLD);
                return;
            }
            overheadTick = ticks;
            report(plane, "running in to " + name(destination) + " on "
                + String.format("%03d", AutopilotMath.compassDisplay(destination.arrivalHeading(position)))
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
        station(plane, pad, targetAltitude, RotorcraftConfig.APPROACH_SPEED, descentRate);

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
        double altitude = pad.y + RotorcraftConfig.DEPARTURE_HEIGHT + RotorcraftConfig.HOLD_HEIGHT;
        // Chase a point walking round the pad, on the station-keeping law rather than the bearing
        // one. With the bearing law the machine cannot keep up with a moving target and simply drifts
        // to the middle: measured, a hold that should have been 30 blocks out sat 8.9 blocks from the
        // pad centre for the whole wait, which is not a separation from anything. The orbit rate is
        // sized so the point moves slower than the machine can fly - 0.8 deg/tick on a 30-block
        // radius is 0.42 blocks/tick.
        double angle = (modeTicks * RotorcraftConfig.HOLD_TURN_RATE) % 360.0;
        Vec3 point = AutopilotMath.pointAlong(pad, angle, RotorcraftConfig.HOLD_RADIUS);
        station(plane, point, Math.max(altitude, terrainFloor()), RotorcraftConfig.APPROACH_SPEED,
            RotorcraftConfig.CLIMB_RATE);

        if (!padWaitReported) {
            padWaitReported = true;
            report(plane, "holding over " + name(destination) + ": the pad is occupied.", false);
        }
        if (modeTicks % RotorcraftConfig.PAD_POLL_INTERVAL == 0 && padAvailable(plane)) {
            overheadTick = ticks;
            setMode(plane, AutopilotMode.DESCENT);
            return;
        }
        if (modeTicks > RotorcraftConfig.HOLD_TIMEOUT) {
            fail(plane, name(destination) + " never became free - held over it for "
                + modeTicks + " ticks");
        }
    }

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
     * <p>Two outcomes, and the difference between them is one measured distance rather than the
     * mode the machine happened to be in. "It came down" and "it landed on the pad" are not the same
     * event and this feature is not going to print the second when the first is what happened.
     */
    private void finish(PlaneEntity plane) {
        Vec3 position = plane.position();
        Vec3 pad = destination.touchdown();
        double miss = AutopilotMath.horizontalDistance(position, pad);
        double tolerance = destination.landingTolerance();
        String where = String.format("%.1f, %.1f, %.1f", position.x, position.y, position.z);
        String timings = (liftOffTick >= 0 ? (ticks - liftOffTick) + " ticks from lift-off, " : "")
            + (overheadTick >= 0 ? (ticks - overheadTick) + " ticks from the run-in" : ticks + " ticks");
        if (miss <= tolerance && plane.getOnGround() && !plane.isOnWater()) {
            reported = true;
            AutopilotFeedback.report(host.owner(), "Helicopter #" + plane.getId() + " landed at "
                + destination.name() + ", " + where + String.format(" (%.2f blocks from the pad centre "
                + "%.1f, %.1f, %.1f, tolerance %.1f; ", miss, pad.x, pad.y, pad.z, tolerance)
                + timings + ").");
            if (plane.level() instanceof ServerLevel serverLevel) {
                StandOccupancy.take(serverLevel, destination.name(), destination.centre(), plane);
            }
        } else {
            reported = true;
            AutopilotFeedback.report(host.owner(), "Helicopter #" + plane.getId()
                + " did not land on " + destination.name() + ": came down at " + where
                + String.format(", %.1f blocks from the pad centre %.1f, %.1f, %.1f (tolerance %.1f)",
                    miss, pad.x, pad.y, pad.z, tolerance)
                + (plane.isOnWater() ? ", in the water" : "") + ". " + timings + ".");
        }
        host.stop(plane);
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
     * One tick of the three loops.
     *
     * @param heading    Minecraft yaw the machine should point along
     * @param altitude   altitude it should hold
     * @param groundSpeed horizontal speed it should be making good, blocks per tick
     * @param verticalLimit largest vertical rate this phase may command, blocks per tick
     * @param vertical   true while the collective boost is wanted (a departure), which on the
     *                   current model costs the yaw control — see {@link #actuate}
     */
    private void fly(PlaneEntity plane, double heading, double altitude, double groundSpeed,
                     double verticalLimit, boolean vertical) {
        Vec3 velocity = plane.getDeltaMovement();
        cmdHeading = heading;
        cmdAltitude = altitude;

        // Altitude error to a vertical-speed demand, clamped by the phase. One cascade stage, where
        // the fixed-wing controller needs four, because thrust here is already vertical.
        double error = altitude - plane.getY();
        cmdVerticalSpeed = Mth.clamp(error * RotorcraftConfig.ALTITUDE_TO_VERTICAL_SPEED,
            -verticalLimit, verticalLimit);

        // Do not try to translate while the nose is a long way off: a machine that accelerates
        // through a 120-degree turn arrives at the turn's far side rather than at the point it was
        // aiming at.
        double headingError = AutopilotMath.angleDelta(plane.getYRot(), heading);
        cmdGroundSpeed = Math.abs(headingError) > RotorcraftConfig.TURN_FIRST_ERROR ? 0.0 : groundSpeed;

        // Hysteresis on the speed error, so the pitch attitude does not chatter across the deadband.
        double actual = velocity.horizontalDistance();
        translating = translating
            ? actual < cmdGroundSpeed + RotorcraftConfig.GROUND_SPEED_DEADBAND
            : actual < cmdGroundSpeed - RotorcraftConfig.GROUND_SPEED_DEADBAND;
        actuate(plane, headingError, cmdVerticalSpeed, velocity.y,
            translating && cmdGroundSpeed > 0, vertical);
    }

    /**
     * Station keeping: get to a point and stop there.
     *
     * <h2>Why this is a different law from {@link #fly}, and not a slower version of it</h2>
     * {@code fly} points the nose at the target and asks for a speed, which is exactly right while
     * the target is a long way off and completely wrong once it is not. Measured on the rig: a
     * machine arriving over a pad on the "point at it and fly 0.35" law never got closer than 10.5
     * blocks. It orbited the pad for the whole 2400-tick descent timeout and reported, correctly,
     * that it could not settle.
     *
     * <p>The cause is a property of the airframe rather than of the gains, and it is the property
     * that separates a rotorcraft from a plane here: {@code HelicopterEntity#tickRotateMotion}
     * returns the attitude unchanged, so <b>the velocity vector does not follow the nose</b>. A
     * plane's does — that is what a wing is for — which is why the fixed-wing controller can treat
     * "point at the target" and "go towards the target" as the same instruction. A helicopter that
     * turns is a helicopter still moving the way it was, and the only thing that changes its
     * velocity is thrust. So pointing at the target and opening the throttle accelerates <em>past</em>
     * it, and pointing at it again from the far side accelerates past it again: an orbit.
     *
     * <p>The law that works is the one that follows from that: <b>thrust along the velocity
     * error.</b> Take the velocity the machine wants — towards the point, at a speed proportional to
     * how far away it is — subtract the velocity it has, and point the nose at the difference. Far
     * out and slow, the difference points at the target and this reduces to the naive law. Closing
     * too fast, the difference points backwards and the machine turns round and brakes, which is
     * what a helicopter pilot does and what nothing else here can do: idle is not a brake in this
     * flight model, and neither is a negative cyclic.
     *
     * <p>It also survives the flight model being rewritten, because it is written in velocities. If
     * the new model does turn the velocity vector with the nose, the velocity error simply shrinks
     * faster and the same law converges sooner.
     */
    private void station(PlaneEntity plane, Vec3 target, double altitude, double maxSpeed,
                         double verticalLimit) {
        Vec3 position = plane.position();
        cmdAltitude = altitude;
        double error = altitude - plane.getY();
        cmdVerticalSpeed = Mth.clamp(error * RotorcraftConfig.ALTITUDE_TO_VERTICAL_SPEED,
            -verticalLimit, verticalLimit);

        double dx = target.x - position.x;
        double dz = target.z - position.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        // Proportional closure, so the machine is already slow when it arrives instead of having to
        // stop from cruise in the last few blocks.
        double wanted = Math.min(maxSpeed, distance * RotorcraftConfig.CLOSURE_GAIN);
        cmdGroundSpeed = wanted;
        double wx = distance > 1.0E-4 ? dx / distance * wanted : 0;
        double wz = distance > 1.0E-4 ? dz / distance * wanted : 0;

        Vec3 velocity = plane.getDeltaMovement();
        double ex = wx - velocity.x;
        double ez = wz - velocity.z;
        double magnitude = Math.sqrt(ex * ex + ez * ez);

        // Below the deadband there is nothing worth pointing at: hold the heading rather than
        // chasing the direction of the numerical noise in a velocity that is already right.
        double heading = magnitude > RotorcraftConfig.STATION_DEADBAND
            ? AutopilotMath.headingTo(Vec3.ZERO, new Vec3(ex, 0, ez))
            : plane.getYRot();
        cmdHeading = heading;
        double headingError = AutopilotMath.angleDelta(plane.getYRot(), heading);

        // The forward demand is on the velocity error, not on the speed, and it is gated on the nose
        // being roughly the right way round: thrusting through a 120-degree pointing error puts
        // energy into the wrong axis and is how the orbit above sustained itself.
        translating = magnitude > RotorcraftConfig.STATION_DEADBAND
            && Math.abs(headingError) < RotorcraftConfig.TURN_FIRST_ERROR;
        actuate(plane, headingError, cmdVerticalSpeed, velocity.y, translating, false);
    }

    /** Everything shut: on the pad, or settling onto it. */
    private void hold(PlaneEntity plane) {
        cmdHeading = plane.getYRot();
        cmdAltitude = plane.getY();
        cmdVerticalSpeed = 0;
        cmdGroundSpeed = 0;
        plane.setThrottle(0);
        plane.setPitchUp((byte) 0);
        plane.setYawRight((byte) 0);
        host.setRotorControls(0, 0);
        setCollective(plane, false);
    }

    /**
     * The three demands, onto the four controls this entity actually has.
     *
     * <p><b>This method is the merge point with the new flight model, and nothing above it is.</b>
     * What it assumes about {@code HelicopterEntity} today:
     *
     * <ul>
     *   <li>{@code moveStrafing} yaws the machine, and the sign is inverted — {@code tickRoll} does
     *       {@code setYRot(getYRot() - moveStrafing * 2)}, so a positive strafe is a <em>left</em>
     *       turn. {@code setYawRight} is driven with the same intent, so that a model which moves
     *       yaw onto the plane's own yaw control keeps turning the right way.</li>
     *   <li>{@code moveForward > 0} pitches the nose down and raises the thrust, which is how the
     *       machine translates; {@code moveForward = 0} levels it and adds horizontal drag, which is
     *       how it stops.</li>
     *   <li>{@code MOVE_UP} is a collective boost <em>and</em> it disables the yaw control while it
     *       is set. That coupling is why it is used only for the vertical departure, where the
     *       heading is already the one the machine wants.</li>
     *   <li>On the ground, thrust is zero unless {@code MOVE_UP} is set, so a lift-off cannot start
     *       without it.</li>
     * </ul>
     *
     * <p>If the sign of the yaw channel or the meaning of {@code moveForward} changes, this method
     * changes and the flight profile does not.
     */
    private void actuate(PlaneEntity plane, double headingError, double verticalDemand,
                         double verticalActual, boolean accelerate, boolean vertical) {
        // --- collective: an integrator on the vertical-speed error.
        int ceiling = maxThrottle(plane);
        int throttle = plane.getThrottle();
        double verticalError = verticalDemand - verticalActual;
        if (verticalError > RotorcraftConfig.VERTICAL_SPEED_SLAM) {
            throttle = ceiling;
        } else if (verticalError < -RotorcraftConfig.VERTICAL_SPEED_SLAM) {
            throttle = 0;
        } else if (ticks % RotorcraftConfig.COLLECTIVE_INTERVAL == 0) {
            if (verticalError > RotorcraftConfig.VERTICAL_SPEED_DEADBAND) {
                throttle++;
            } else if (verticalError < -RotorcraftConfig.VERTICAL_SPEED_DEADBAND) {
                throttle--;
            }
        }
        plane.setThrottle(Mth.clamp(throttle, 0, ceiling));

        // --- cyclic: a single bit, decided by whichever law is flying (see fly and station).
        float forward = accelerate ? 1.0f : 0.0f;

        // --- pedals: proportional on the heading error with a rate lead. The lead is what makes the
        // same law work on a plant whose rate follows the input (this model) and on one whose
        // acceleration does (a plane's yaw), so it survives the rewrite either way.
        double yawRate = yawInitialised ? Mth.wrapDegrees(plane.getYRot() - previousYaw) : 0.0;
        previousYaw = plane.getYRot();
        yawInitialised = true;
        double yawDemand = Math.abs(headingError) < RotorcraftConfig.HEADING_DEADBAND ? 0.0
            : Mth.clamp((headingError - yawRate * RotorcraftConfig.YAW_RATE_LEAD)
                / RotorcraftConfig.YAW_ERROR_SPAN, -1.0, 1.0);

        // Sign: moveStrafing is inverted on this entity (see the javadoc), setYawRight is not.
        host.setRotorControls((float) -yawDemand, forward);
        plane.setYawRight((byte) Math.signum(yawDemand));
        plane.setPitchUp((byte) 0);
        setCollective(plane, vertical);
    }

    private void setCollective(PlaneEntity plane, boolean up) {
        if (plane instanceof HelicopterEntity helicopter) {
            helicopter.setMoveUp(up);
        }
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
        LOGGER.info(String.format(
            "trace #%d t=%d %s pos=%.2f,%.2f,%.2f spd=%.3f vs=%+.3f thr=%d hdg=%.1f cmdhdg=%.1f"
                + " cmdalt=%.1f cmdvs=%+.3f cmdspd=%.2f dpad=%.2f og=%b",
            plane.getId(), ticks, mode.getName(), position.x, position.y, position.z,
            plane.getDeltaMovement().horizontalDistance(), plane.getDeltaMovement().y,
            plane.getThrottle(), Mth.wrapDegrees(plane.getYRot()), Mth.wrapDegrees(cmdHeading),
            cmdAltitude, cmdVerticalSpeed, cmdGroundSpeed, distance, plane.getOnGround()));
    }
}
