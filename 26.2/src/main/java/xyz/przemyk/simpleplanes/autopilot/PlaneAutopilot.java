package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesRegistries;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.booster.BoosterUpgrade;

import java.util.Optional;

/**
 * Server-side flight director: every tick it decides where the aircraft should be going and then
 * moves the same four controls a player would move — throttle, pitch, yaw and roll. It never sets
 * the position, velocity or rotation of the plane, so the aircraft flies entirely on
 * {@code PlaneEntity}'s own physics and behaves like a plane, including stalling into the ground if
 * the guidance asks for something the airframe cannot do.
 *
 * <h2>Control laws</h2>
 * <ul>
 *   <li><b>Heading</b> — bang-bang on the yaw control with a rate term, see
 *       {@link AutopilotMath#bangBang}. Bank angle follows the heading error for looks and is forced
 *       to zero for landing.</li>
 *   <li><b>Altitude</b> — a cascade: altitude error becomes a commanded vertical speed, that becomes
 *       a commanded flight path angle, that becomes a commanded pitch attitude, which the pitch
 *       control tracks. Each stage is clamped, which is what keeps the aircraft inside a sane
 *       envelope instead of porpoising.</li>
 *   <li><b>Speed</b> — proportional on the throttle notch, adjusted every
 *       {@link AutopilotConfig#THROTTLE_INTERVAL} ticks so the lever does not chatter.</li>
 * </ul>
 */
public class PlaneAutopilot {

    private AutopilotMode mode = AutopilotMode.IDLE;
    private @Nullable FlightPlan plan;
    private boolean active;
    /** Aircraft conjured by a tool run on autopilot fuel; player-built planes still need an engine. */
    private boolean autopilotPowered;
    /** Strike aircraft are deliberately not written to disk; see {@link #save}. */
    private boolean persistent;
    private int goArounds;
    private boolean gatesDisabled;
    /** Set once the end-of-flight report has been sent, so it is never sent twice. */
    private boolean outcomeReported;
    /** Previous tick's range to the strike target, for closest-point-of-approach detection. */
    private double previousSlantRange = Double.MAX_VALUE;

    /** Distance from the aimpoint still counted as a hit rather than a miss. */
    public static final double STRIKE_HIT_RADIUS = 8.0;

    private final TerrainScanner scanner = new TerrainScanner();
    private @Nullable Airfield landingAirfield;
    private @Nullable RunwayEnd landingEnd;
    /** Runway this sortie departs from, resolved once at launch; null for an airborne launch. */
    private @Nullable RunwayEnd departureEnd;
    private @Nullable Vec3 holdFix;
    private double holdAngle;

    private int ticks;
    private int modeTicks;
    /** Consecutive ticks spent on the ground in a mode that is meant to be airborne. */
    private int groundedTicks;

    /** Only used for messages; never persisted, so a reloaded flight simply flies silently. */
    private @Nullable Player owner;

    // ---- control outputs read back by PlaneEntity ----
    private float moveStrafing;
    private float moveForward;

    // ---- rate estimation (the plane's own *Speed fields are protected) ----
    private double previousYaw;
    private double previousPitch;
    private double previousRoll;
    private boolean anglesInitialised;

    // ---- per-tick command produced by the active mode ----
    private double cmdHeading;
    private double cmdTargetAltitude;
    private double cmdSpeed;
    private double cmdBankLimit;
    private @Nullable Double cmdPitchOverride;
    private boolean cmdGroundSteer;
    private boolean cmdTerrainFollow;
    private double cmdMaxClimbAngle;
    private double cmdMaxDescentAngle;
    /** Lowest throttle this mode tolerates while airborne; 0 only where idling is the point. */
    private int cmdMinThrottle;
    /** Highest throttle this mode may command. Raised for a boosted strike, lowered for a taxi. */
    private int cmdMaxThrottle;
    /** Hold the elevator at neutral rather than tracking an attitude. Ground manoeuvring only. */
    private boolean cmdNeutralPitch;

    // ------------------------------------------------------------------ lifecycle

    public void start(PlaneEntity plane, FlightPlan flightPlan, boolean autopilotPowered, boolean persistent,
                      @Nullable Player owner) {
        this.plan = flightPlan;
        this.autopilotPowered = autopilotPowered;
        this.persistent = persistent;
        this.owner = owner;
        this.goArounds = 0;
        this.gatesDisabled = false;
        this.landingAirfield = null;
        this.landingEnd = null;
        this.departureEnd = null;
        this.holdFix = null;
        this.anglesInitialised = false;
        this.outcomeReported = false;
        active = true;
        AutopilotRegistry.register(plane);

        if (flightPlan.kind() == FlightPlan.Kind.STRIKE) {
            setMode(plane, AutopilotMode.STRIKE);
            return;
        }
        departureEnd = resolveDeparture(plane, flightPlan);
        if (departureEnd != null) {
            setMode(plane, AutopilotMode.TAXI);
        } else if (plane.getOnGround()) {
            setMode(plane, AutopilotMode.TAKEOFF);
        } else {
            setMode(plane, AutopilotMode.CLIMB);
        }
    }

    /** The runway a ground departure rolls from, or null when the flight starts in the air. */
    private static @Nullable RunwayEnd resolveDeparture(PlaneEntity plane, FlightPlan plan) {
        if (plan.departureAirfield() == null || !(plane.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        Airfield airfield = AutopilotSavedData.get(serverLevel).get(plan.departureAirfield());
        return airfield == null ? null : Airfield.departureEnd(serverLevel, airfield);
    }

    /**
     * Reports how a flight ended, so a launch never just goes quiet. Without this the only
     * observable difference between "hit the target", "hit a hillside on the way" and "never
     * spawned" is that the aircraft is no longer in the status list.
     */
    public void reportOutcome(PlaneEntity plane) {
        if (!active || plan == null || outcomeReported) {
            return;
        }
        outcomeReported = true;
        String where = Math.round(plane.getX()) + ", " + Math.round(plane.getY()) + ", " + Math.round(plane.getZ());
        Vec3 target = plan.strikeTargetVec();
        if (target == null) {
            AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " lost at " + where
                + " in " + mode.name().toLowerCase(java.util.Locale.ROOT) + ".");
            return;
        }
        long miss = Math.round(plane.position().distanceTo(target));
        if (miss <= STRIKE_HIT_RADIUS) {
            AutopilotFeedback.report(owner, "Strike #" + plane.getId() + " hit the target at " + where
                + " (" + miss + " blocks off).");
        } else {
            AutopilotFeedback.report(owner, "Strike #" + plane.getId() + " went down at " + where
                + ", " + miss + " blocks short of the target.");
        }
    }

    public void stop(PlaneEntity plane) {
        active = false;
        AutopilotRegistry.unregister(plane);
        RunwayOccupancy.releaseAll(plane);
        mode = AutopilotMode.IDLE;
        moveStrafing = 0;
        moveForward = 0;
        plane.setThrottle(0);
        plane.setPitchUp((byte) 0);
        plane.setYawRight((byte) 0);
    }

    public boolean isActive() {
        return active && plan != null;
    }

    public AutopilotMode getMode() {
        return mode;
    }

    public @Nullable FlightPlan getPlan() {
        return plan;
    }

    public void setOwner(@Nullable Player owner) {
        this.owner = owner;
    }

    /** True while this aircraft is entitled to keep a runway reservation. */
    public boolean holdsRunway(String airfieldName) {
        return active && landingAirfield != null && landingAirfield.name().equals(airfieldName) && mode.usesRunway();
    }

    /**
     * Whether the autopilot supplies its own power. Only aircraft conjured by the strike tool run on
     * autopilot fuel; a plane the player built still needs a working engine, so the autopilot cannot
     * be used to dodge the fuel economy.
     */
    public boolean providesPower() {
        return active && autopilotPowered;
    }

    public float getMoveStrafing() {
        return moveStrafing;
    }

    public float getMoveForward() {
        return moveForward;
    }

    // ------------------------------------------------------------------ main loop

    public void tick(PlaneEntity plane) {
        if (!isActive()) {
            return;
        }
        Level level = plane.level();
        if (plane.isRemoved() || !plane.isAlive()) {
            stop(plane);
            return;
        }
        ticks++;
        modeTicks++;
        if (ticks % 40 == 0) {
            RunwayOccupancy.prune();
        }
        // An autopilot aircraft routinely flies hundreds of blocks from any player, and entities in
        // chunks nobody is keeping loaded simply stop ticking. Re-requesting a short-lived ticket
        // every 20 ticks keeps a small bubble of chunks alive around the aircraft — exactly what
        // vanilla does for a thrown ender pearl (ServerPlayer#placeEnderPearlTicket).
        if (ticks % 20 == 0 && level instanceof ServerLevel serverLevel) {
            keepChunksLoaded(serverLevel, plane);
        }

        Vec3 position = plane.position();
        scanner.scan(level, position, plane.getYRot());

        // Defaults; each mode overrides what it cares about.
        cmdHeading = plane.getYRot();
        cmdTargetAltitude = position.y;
        cmdSpeed = AutopilotConfig.CRUISE_SPEED;
        cmdBankLimit = AutopilotConfig.MAX_BANK;
        cmdPitchOverride = null;
        cmdGroundSteer = false;
        cmdTerrainFollow = true;
        cmdMaxClimbAngle = AutopilotConfig.MAX_CLIMB_ANGLE;
        cmdMaxDescentAngle = AutopilotConfig.MAX_DESCENT_ANGLE;
        cmdMinThrottle = AutopilotConfig.MIN_AIRBORNE_THROTTLE;
        cmdMaxThrottle = PlaneEntity.MAX_THROTTLE;
        cmdNeutralPitch = false;

        switch (mode) {
            case TAXI -> tickTaxi(plane);
            case TAKEOFF -> tickTakeoff(plane);
            case CLIMB -> tickClimb(plane);
            case CRUISE -> tickCruise(plane);
            case STRIKE -> tickStrike(plane);
            case DESCENT -> tickDescent(plane);
            case APPROACH -> tickApproach(plane, false);
            case FINAL -> tickApproach(plane, true);
            case FLARE -> tickFlare(plane);
            case ROLLOUT -> tickRollout(plane);
            case HOLD -> tickHold(plane);
            case GO_AROUND -> tickGoAround(plane);
            case IDLE -> stop(plane);
        }

        if (!isActive()) {
            return;
        }

        if (checkGrounded(plane)) {
            return;
        }

        applyTerrainFollowing(plane);
        applyControls(plane);
    }

    /**
     * Ends a flight that has quietly arrived on the ground in a mode that is supposed to be in the
     * air.
     *
     * <p>An aircraft that mushes into a field does not stop: the flight director keeps commanding a
     * cruise altitude it can no longer reach, the aircraft trundles along the surface at taxi speed,
     * and {@code /autopilot status} goes on reporting {@code cruise} indefinitely. That is both
     * useless to watch and impossible to assert on, so it is called what it is.
     *
     * @return true when the flight has been terminated and nothing else should run this tick
     */
    private boolean checkGrounded(PlaneEntity plane) {
        if (mode.isGroundPhase() || mode == AutopilotMode.FLARE || !plane.getOnGround()) {
            groundedTicks = 0;
            return false;
        }
        if (++groundedTicks < AutopilotConfig.GROUNDED_TIMEOUT) {
            return false;
        }
        outcomeReported = true;
        AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " came down at "
            + Math.round(plane.getX()) + ", " + Math.round(plane.getY()) + ", " + Math.round(plane.getZ())
            + " in " + mode.getName() + ".");
        stop(plane);
        return true;
    }

    /**
     * Raises the commanded altitude to clear the terrain profile ahead and, if the ridge cannot be
     * out-climbed in the distance available, nudges the heading towards the lower side. This is the
     * whole obstacle-avoidance story: no search, no pathfinding, just "climb over it, and if you
     * cannot, go around the low end".
     */
    private void applyTerrainFollowing(PlaneEntity plane) {
        if (!cmdTerrainFollow) {
            return;
        }
        double safeAltitude = scanner.safeAltitude();
        if (safeAltitude != TerrainScanner.UNKNOWN_HEIGHT && safeAltitude > cmdTargetAltitude) {
            cmdTargetAltitude = safeAltitude;
        }
        int bias = scanner.avoidanceBias(plane.position().y);
        if (bias != 0) {
            cmdHeading += bias * AutopilotConfig.AVOID_HEADING_BIAS;
        }
    }

    // ------------------------------------------------------------------ modes

    /**
     * Ground manoeuvring from the parking spot to the departure threshold.
     *
     * <p>The only phase that steers the aircraft on the ground without trying to fly it. It is
     * deliberately two-stage: track to a lineup point on the extended centreline behind the
     * threshold, then stop tracking a point and simply hold the runway heading, because chasing a
     * point the aircraft is nearly on top of makes the nosewheel hunt. The take-off is released only
     * once the aircraft is genuinely straight, which is what makes the departure roll usable.
     *
     * <p>Nothing here moves the aircraft: it is throttle, the same nosewheel steering the roll-out
     * uses, and the elevator held at neutral.
     */
    private void tickTaxi(PlaneEntity plane) {
        if (departureEnd == null) {
            setMode(plane, AutopilotMode.TAKEOFF);
            return;
        }
        cmdGroundSteer = true;
        cmdBankLimit = 0;
        cmdTerrainFollow = false;
        cmdSpeed = AutopilotConfig.TAXI_SPEED;
        cmdMinThrottle = 0;
        cmdMaxThrottle = AutopilotConfig.TAXI_MAX_THROTTLE;
        // Elevator strictly neutral, and this is not cosmetic. A parked plane rests at
        // PlaneEntity#getGroundPitch (5 degrees nose-up), so commanding a level attitude leaves the
        // pitch controller permanently holding nose-down — and PlaneEntity#tickOnGround reads a
        // negative pitch input as reverse thrust (push = -groundPush). The aircraft taxied smoothly
        // backwards away from the runway at 0.13 blocks/tick, facing the right way the whole time.
        cmdNeutralPitch = true;

        double runwayHeading = departureEnd.landingHeading();
        Vec3 lineup = departureEnd.threshold();
        double distance = AutopilotMath.horizontalDistance(plane.position(), lineup);

        if (distance > AutopilotConfig.TAXI_LINEUP_RADIUS) {
            cmdHeading = AutopilotMath.headingTo(plane.position(), lineup);
            return;
        }

        // On the threshold: stop chasing the point, line up on the runway and wait until straight.
        cmdHeading = runwayHeading;
        double headingError = Math.abs(AutopilotMath.angleDelta(plane.getYRot(), runwayHeading));
        if (headingError <= AutopilotConfig.TAXI_ALIGNED_ERROR) {
            AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " lined up on "
                + departureEnd.airfield().name() + "/" + departureEnd.designator() + ", departing.");
            setMode(plane, AutopilotMode.TAKEOFF);
        } else if (modeTicks > AutopilotConfig.TAXI_TIMEOUT) {
            // Never leave an aircraft creeping round a threshold forever; take the runway as it is.
            AutopilotFeedback.report(owner, "Plane #" + plane.getId()
                + " could not line up cleanly, departing anyway.");
            setMode(plane, AutopilotMode.TAKEOFF);
        }
    }

    private void tickTakeoff(PlaneEntity plane) {
        RunwayEnd runway = departureEnd != null ? departureEnd : landingEnd;
        double heading = runway != null ? runway.landingHeading() : plane.getYRot();
        if (runway == null && plan != null && plan.hasWaypoints()) {
            Vec3 waypoint = plan.currentWaypoint();
            if (waypoint != null) {
                heading = AutopilotMath.headingTo(plane.position(), waypoint);
            }
        }
        cmdHeading = heading;
        cmdGroundSteer = true;
        cmdBankLimit = 0;
        cmdSpeed = AutopilotConfig.STRIKE_SPEED;
        cmdTerrainFollow = false;
        // The whole lever, booster included. The ground roll to ROTATE_SPEED is 3.8 blocks at
        // throttle 5 and 1.9 at throttle 10, and the shorter figure is the one the runway-length
        // check in AutopilotConfig is derived from, so the departure has to actually fly it.
        cmdMaxThrottle = maxThrottle(plane);

        // Elevator aft for the whole roll, exactly as a real departure is flown, and not only for
        // looks. PlaneEntity#tickOnGround reads the pitch input three ways: a positive input levels
        // the aircraft on its wheels (which removes the static-friction penalty that divides the
        // thrust by five while the nose sits at the 5-degree resting attitude) and guarantees at
        // least groundPush, while a negative input is reverse thrust.
        //
        // The previous "hold the nose down until flying speed, then rotate" did the opposite of what
        // it says: the resting ground attitude is +5 degrees, so commanding 0 held the elevator
        // permanently forward and the take-off roll never accelerated past 0.13 blocks/tick against
        // a 0.35 rotate speed. Nothing caught it because nothing had ever departed from a standstill
        // before — routes and strikes are both launched in the air.
        //
        // Holding it aft cannot rotate the aircraft early: tickOnGround returns speedingUp = false
        // below takeOffSpeed, and PlaneEntity#tick only runs tickPitch when it is true.
        cmdPitchOverride = 12.0;

        double agl = plane.position().y - groundBelow(plane);
        if (!plane.getOnGround() && agl > AutopilotConfig.TAKEOFF_CLEAR_HEIGHT) {
            setMode(plane, AutopilotMode.CLIMB);
        }
    }

    private void tickClimb(PlaneEntity plane) {
        Vec3 waypoint = plan == null ? null : plan.currentWaypoint();
        double cruiseAltitude = plan == null ? AutopilotConfig.DEFAULT_CRUISE_ALTITUDE : plan.cruiseAltitude();
        if (waypoint != null) {
            cmdHeading = AutopilotMath.headingTo(plane.position(), waypoint);
        }
        cmdTargetAltitude = cruiseAltitude;
        // Never slower than climb speed, and never slower than the cruise that was ordered.
        //
        // The climb used to be flown at CLIMB_SPEED flat, on the reasoning that accelerating and
        // climbing at once does both badly. That reasoning does not survive a commanded cruise
        // faster than the climb speed: the aircraft would spend the climb braking against a speed it
        // is about to be told to fly again, and the climb rate is capped by MAX_CLIMB_RATE and
        // MAX_CLIMB_ANGLE rather than by thrust anyway, so there is nothing to trade. The max()
        // keeps the floor for a slow cruise — a route ordered at MIN_CRUISE_SPEED still climbs away
        // at 0.70 rather than wallowing off the runway at 0.40.
        cmdSpeed = plan == null
            ? AutopilotConfig.CLIMB_SPEED
            : Math.max(AutopilotConfig.CLIMB_SPEED, plan.cruiseSpeed());
        cmdMaxThrottle = maxThrottle(plane);
        if (Math.abs(plane.position().y - cmdTargetAltitude) < 8) {
            setMode(plane, AutopilotMode.CRUISE);
        }
    }

    private void tickCruise(PlaneEntity plane) {
        if (plan == null) {
            stop(plane);
            return;
        }
        Vec3 waypoint = plan.currentWaypoint();
        if (waypoint == null) {
            beginLanding(plane);
            return;
        }
        cmdHeading = AutopilotMath.headingTo(plane.position(), waypoint);
        cmdTargetAltitude = waypoint.y;
        // A route aircraft now carries a booster like a strike does, so the cruise may use the whole
        // throttle range. Without this the loop would quietly hold the lever at 5 and a fast cruise
        // would never be reached.
        cmdMaxThrottle = maxThrottle(plane);

        double distanceToWaypoint = AutopilotMath.horizontalDistance(plane.position(), waypoint);
        cmdSpeed = cruiseSpeedSchedule(plan, distanceToWaypoint);

        // Braking needs the airbrake, and the airbrake is throttle 0: tickMotion multiplies the whole
        // drag polynomial by brakesMul = 5 there and by 1 everywhere else.
        //
        // applyThrottle already drops the floor whenever the aircraft is above its commanded speed,
        // so this is not what lets the bleed reach idle. What it does is settle the argument with
        // the manoeuvre rule: that rule holds the power the loop had rather than letting it be
        // reduced, and it keys off cmdMinThrottle > 0. Zeroing the floor for the duration of the
        // bleed says braking wins over turn protection here — the descent is committed and the
        // aircraft has to be slow for it, turn or no turn.
        //
        // The floor really does have to reach 0, and by more than a factor of five. With one notch
        // left in, the boosted airframe does not decelerate to APPROACH_SPEED slowly: it does not
        // get there at all, because throttle 1 balances the drag curve at about 1.0 blocks/tick and
        // simply holds it. (This used to be written down as "800 blocks instead of 158", which is
        // the drag-only figure with the thrust left out.)
        if (cmdSpeed < plan.cruiseSpeed() - 1.0E-3) {
            cmdMinThrottle = 0;
        }

        if (distanceToWaypoint < arrivalRadius(plane)) {
            boolean routeComplete = plan.advance();
            AutopilotFeedback.overlay(owner, "Plane #" + plane.getId() + ": waypoint reached ("
                + plan.legsFlown() + "/" + plan.maxLegs() + " legs)");
            if (routeComplete) {
                beginLanding(plane);
            }
        }
    }

    /**
     * The commanded speed for this point on the cruise leg: full cruise speed for most of it, then
     * a deceleration profile that lands on {@link AutopilotConfig#APPROACH_SPEED} at the last
     * waypoint, which is where the descent starts.
     *
     * <p><b>Why this is a schedule and not a clamp.</b> The approach geometry — an 8-degree glide
     * slope, the landing gates and a 4-degree flare — is tuned around arriving at approach speed.
     * Cutting the commanded speed at the moment the mode changes does not produce that: the
     * aircraft is still doing cruise speed when the glide slope begins and needs roughly
     * {@link AutopilotMath#decelerationDistance} blocks to shed it, which at 2.80 blocks/tick is
     * 158 blocks — half the 300-block final intercept, flown high and fast on the slope. So the
     * energy is shed on the cruise leg, before the descent, over the distance the drag curve
     * actually needs. Same shape as the strike's dive point, which is derived from the height still
     * to be lost rather than being a fixed number.
     *
     * <p>Only the final leg brakes. An intermediate waypoint is a turn, not an arrival, and slowing
     * for it would just make the turn worse.
     */
    private static double cruiseSpeedSchedule(FlightPlan plan, double distanceToWaypoint) {
        if (!plan.onFinalLeg()) {
            return plan.cruiseSpeed();
        }
        return AutopilotMath.speedSchedule(plan.cruiseSpeed(), AutopilotConfig.APPROACH_SPEED,
            distanceToWaypoint / AutopilotConfig.DECELERATION_MARGIN);
    }

    /**
     * How close to a waypoint counts as having reached it.
     *
     * <p>Fixed at {@link AutopilotConfig#WAYPOINT_ARRIVAL_RADIUS} this was fine at cruise speed and
     * wrong at attack speed: an aircraft cannot turn inside its own turn radius, which is
     * {@code v / yawRate}, and {@code tickYaw} clamps the yaw rate to
     * {@value AutopilotConfig#MAX_YAW_RATE} degrees per tick. At 0.80 blocks/tick that radius is 18
     * blocks and the fixed 30 covers it; at 2.80 it is 64 blocks, so the aircraft physically cannot
     * get within 30 of the waypoint and orbits it instead of sequencing. Deriving the radius from
     * the speed makes the turn possible at any speed and changes nothing at the old one.
     */
    private static double arrivalRadius(PlaneEntity plane) {
        double speed = plane.getDeltaMovement().horizontalDistance();
        double yawRate = Math.toRadians(AutopilotConfig.MAX_YAW_RATE * plane.autopilotRotationSpeedMultiplier());
        double turnRadius = speed / Math.max(yawRate, 1.0E-4);
        return Math.max(AutopilotConfig.WAYPOINT_ARRIVAL_RADIUS, turnRadius);
    }

    /**
     * Highest throttle notch this airframe has. A booster raises the ceiling from 5 to 10, and the
     * autopilot fits one to every aircraft it creates, so this is normally 10 — but a plan loaded
     * back off disk onto a plane whose booster was removed has to see the real number.
     */
    private static int maxThrottle(PlaneEntity plane) {
        return plane.upgrades.containsKey(
            SimplePlanesRegistries.UPGRADE_TYPE.getKey(SimplePlanesUpgrades.BOOSTER.get()))
            ? BoosterUpgrade.MAX_THROTTLE
            : PlaneEntity.MAX_THROTTLE;
    }

    private void tickStrike(PlaneEntity plane) {
        if (plan == null) {
            stop(plane);
            return;
        }
        Vec3 target = plan.strikeTargetVec();
        if (target == null) {
            stop(plane);
            return;
        }
        Vec3 position = plane.position();
        cmdHeading = AutopilotMath.headingTo(position, target);
        cmdSpeed = AutopilotConfig.STRIKE_SPEED;
        // A strike aircraft carries a booster, which raises the throttle ceiling from 5 to 10. The
        // throttle loop clamps to this, so without it the loop would quietly pull the lever back to
        // 5 and the run would arrive slow.
        cmdMaxThrottle = BoosterUpgrade.MAX_THROTTLE;

        double distance = AutopilotMath.horizontalDistance(position, target);

        // Where to stop cruising and start diving. Derived from the height still to be lost rather
        // than fixed, so the dive point moves with the run-in altitude and with the terrain: hold
        // the height until the target is STRIKE_DIVE_ANGLE degrees below the nose, then go for it.
        double heightToLose = Math.max(position.y - target.y, 0.0);
        double diveEntry = Mth.clamp(
            heightToLose / Math.tan(Math.toRadians(AutopilotConfig.STRIKE_DIVE_ANGLE)),
            AutopilotConfig.STRIKE_MIN_DIVE_DISTANCE, AutopilotConfig.STRIKE_MAX_DIVE_DISTANCE);

        if (distance > diveEntry) {
            // Run-in: hold height above the ground, not above the target. A target in a valley is
            // no reason to fly the whole approach down in the valley with it.
            cmdTargetAltitude = Math.max(groundBelow(plane), target.y) + AutopilotConfig.STRIKE_RUN_IN_AGL;
            cmdTerrainFollow = true;
            // Never trade height for speed on the way in; the dive is where height gets spent.
            cmdMaxDescentAngle = 4.0;
        } else {
            // Committed: point the nose straight at the aim point.
            //
            // Tracking an altitude here does not work at strike speed. The cascade converts an
            // altitude error into a vertical speed and then into a flight path angle, and by the
            // time the aircraft has flown that gently sloping profile it is over the target still
            // high and goes in well beyond it - measured twice, 57 and 54 blocks long. Commanding
            // the elevation angle to the target instead makes the run self-correcting: the further
            // behind the profile it falls, the steeper the commanded dive becomes.
            cmdTargetAltitude = target.y;
            cmdTerrainFollow = false;
            cmdBankLimit = 10;
            cmdMaxDescentAngle = 80.0;
            double horizontalToTarget = Math.max(AutopilotMath.horizontalDistance(position, target), 1.0);
            cmdPitchOverride = Mth.clamp(
                Math.toDegrees(Math.atan2(target.y - position.y, horizontalToTarget)),
                -80.0, AutopilotConfig.MAX_PITCH);
        }

        // Proximity fuse. A fixed 3-block sphere is not enough at attack speed: the aircraft covers
        // most of 3 blocks in a single tick and can step straight over the sphere between two
        // samples. Scale the radius with the speed, and fall back to detecting the closest point of
        // approach — if the range starts opening again the aircraft is already past the target.
        double slantRange = position.distanceTo(target);
        double closureSpeed = plane.getDeltaMovement().length();
        boolean atTarget = slantRange < Math.max(3.0, closureSpeed * 1.2)
            || (slantRange < 24.0 && slantRange > previousSlantRange);
        previousSlantRange = slantRange;
        if (atTarget) {
            plane.crash(16);
            stop(plane);
            return;
        }

        // Hit something that was not the target. A strike aircraft is a one-shot weapon carrying a
        // warhead, so it goes off wherever it stops — otherwise a run that clips a tree or a ridge
        // leaves an intact, permanently stationary aircraft parked in the scenery with the autopilot
        // still running, which is exactly what was being reported.
        boolean stalled = plane.getDeltaMovement().length() < AutopilotConfig.STRIKE_STALLED_SPEED;
        if (modeTicks > 20 && (plane.getOnGround() || stalled)) {
            reportOutcome(plane);
            plane.crash(16);
            stop(plane);
        }
    }

    private void beginLanding(PlaneEntity plane) {
        resolveLanding(plane);
        setMode(plane, AutopilotMode.DESCENT);
    }

    private void tickDescent(PlaneEntity plane) {
        if (!resolveLanding(plane)) {
            stop(plane);
            return;
        }
        Vec3 initialFix = landingEnd.approachPoint(
            AutopilotConfig.FINAL_INTERCEPT_DISTANCE, AutopilotConfig.PATTERN_HEIGHT);
        cmdHeading = AutopilotMath.headingTo(plane.position(), initialFix);
        cmdTargetAltitude = initialFix.y;
        cmdSpeed = AutopilotConfig.APPROACH_SPEED;
        // Idle is allowed from here to the ground: throttle 0 puts brakesMul = 5 on the drag
        // polynomial, and that airbrake is the only way to slow down on an 8-degree slope. It is
        // safe now that the throttle loop regulates horizontal speed rather than total speed, so a
        // sink rate can no longer masquerade as airspeed and latch the lever shut.
        cmdMinThrottle = 0;

        if (AutopilotMath.horizontalDistance(plane.position(), initialFix) < 50) {
            if (RunwayOccupancy.tryOccupy(plane.level(), landingAirfield.name(), plane)) {
                setMode(plane, AutopilotMode.APPROACH);
            } else {
                holdFix = initialFix;
                AutopilotFeedback.overlay(owner, "Plane #" + plane.getId() + ": runway occupied, holding");
                setMode(plane, AutopilotMode.HOLD);
            }
        }
    }

    /**
     * Extended-centreline tracking with a glide slope. {@code isFinal} enables the landing gates —
     * the checks that decide whether this approach is good enough to touch down on.
     */
    private void tickApproach(PlaneEntity plane, boolean isFinal) {
        if (!resolveLanding(plane)) {
            stop(plane);
            return;
        }
        if (!RunwayOccupancy.tryOccupy(plane.level(), landingAirfield.name(), plane)) {
            holdFix = landingEnd.approachPoint(AutopilotConfig.FINAL_INTERCEPT_DISTANCE, AutopilotConfig.PATTERN_HEIGHT);
            setMode(plane, AutopilotMode.HOLD);
            return;
        }

        Vec3 position = plane.position();
        double runwayHeading = landingEnd.landingHeading();
        Vec3 threshold = landingEnd.threshold();
        double distanceToThreshold = -AutopilotMath.alongTrack(threshold, runwayHeading, position);
        double lateral = AutopilotMath.lateralOffset(threshold, runwayHeading, position);

        // Intercept the centreline: bigger offset means a bigger cut, capped so it never turns away.
        double interceptCut = Mth.clamp(-lateral * 1.2, -40.0, 40.0);
        cmdHeading = runwayHeading + interceptCut;
        cmdTargetAltitude = Math.min(landingEnd.glideSlopeAltitude(distanceToThreshold),
            threshold.y + AutopilotConfig.PATTERN_HEIGHT);
        cmdSpeed = isFinal ? AutopilotConfig.FINAL_SPEED : AutopilotConfig.APPROACH_SPEED;
        cmdBankLimit = isFinal ? 8 : 18;
        cmdTerrainFollow = false;
        cmdMaxDescentAngle = AutopilotConfig.GLIDE_SLOPE_DEGREES * 2.0;
        // As in DESCENT: the approach needs to be able to close the throttle, or it arrives fast,
        // floats down the whole strip and goes around. Measured before this: 0.94 blocks/tick on
        // short final against a commanded 0.40, three go-arounds, no landing.
        cmdMinThrottle = 0;

        double agl = position.y - groundBelow(plane);

        // Flew past the threshold without getting down: go around.
        if (distanceToThreshold < -5) {
            goAround(plane, "crossed the threshold still airborne");
            return;
        }

        // Real raycast down the approach corridor, so a hill in the way is caught even when the
        // heightmap profile looks fine. Skipped close in, where the runway itself is the hit.
        if (!gatesDisabled && agl > 15 && ticks % 20 == 0) {
            Vec3 aim = landingEnd.aimPoint();
            if (!TerrainScanner.pathClear(plane.level(), plane, position, new Vec3(aim.x, aim.y + 2, aim.z))) {
                goAround(plane, "terrain in the approach corridor");
                return;
            }
        }

        if (!isFinal) {
            if (distanceToThreshold < 150) {
                setMode(plane, AutopilotMode.FINAL);
            }
            return;
        }

        if (!gatesDisabled) {
            String failure = gateFailure(plane, lateral, agl);
            if (failure != null) {
                goAround(plane, failure);
                return;
            }
        }
        if (agl <= AutopilotConfig.FLARE_HEIGHT || plane.getOnGround()) {
            setMode(plane, AutopilotMode.FLARE);
        }
    }

    /**
     * The "is this a landing or a crash" test, applied only once the aircraft is low enough for it
     * to mean anything. Any failure sends it around rather than letting it touch down skewed —
     * which {@code PlaneEntity#causeFallDamage} would turn into an explosion anyway.
     */
    private @Nullable String gateFailure(PlaneEntity plane, double lateral, double agl) {
        if (agl > AutopilotConfig.GATE_CHECK_HEIGHT) {
            return null;
        }
        double headingError = Math.abs(AutopilotMath.angleDelta(plane.getYRot(), landingEnd.landingHeading()));
        if (headingError > AutopilotConfig.GATE_HEADING_ERROR) {
            return String.format("heading %.0f deg off the runway", headingError);
        }
        double allowedLateral = Math.max(AutopilotConfig.GATE_LATERAL_OFFSET, landingAirfield.width());
        if (Math.abs(lateral) > allowedLateral) {
            return String.format("%.0f blocks off the centreline", Math.abs(lateral));
        }
        double bank = Math.abs(Mth.wrapDegrees(plane.rotationRoll));
        if (bank > AutopilotConfig.GATE_BANK) {
            return String.format("banked %.0f deg", bank);
        }
        double sink = -plane.getDeltaMovement().y;
        if (sink > AutopilotConfig.GATE_SINK_RATE) {
            return String.format("sinking %.2f blocks/tick", sink);
        }
        return null;
    }

    private void tickFlare(PlaneEntity plane) {
        if (landingEnd == null) {
            stop(plane);
            return;
        }
        cmdHeading = landingEnd.landingHeading();
        cmdPitchOverride = AutopilotConfig.FLARE_PITCH;
        cmdBankLimit = 0;
        cmdSpeed = 0;
        cmdTerrainFollow = false;
        // The flare is the one place a closed throttle is wanted: the aircraft is a few blocks up,
        // over the runway, and meant to stop flying.
        cmdMinThrottle = 0;
        cmdMaxThrottle = 0;
        plane.setThrottle(0);

        if (plane.getOnGround()) {
            setMode(plane, AutopilotMode.ROLLOUT);
            return;
        }
        double agl = plane.position().y - groundBelow(plane);
        if (agl > AutopilotConfig.FLARE_HEIGHT * 4 && modeTicks > 20) {
            // Ballooned back up — re-establish the approach.
            setMode(plane, AutopilotMode.FINAL);
        }
    }

    private void tickRollout(PlaneEntity plane) {
        if (landingEnd == null) {
            stop(plane);
            return;
        }
        cmdHeading = landingEnd.landingHeading();
        cmdGroundSteer = true;
        cmdBankLimit = 0;
        cmdSpeed = 0;
        cmdPitchOverride = 0.0;
        cmdTerrainFollow = false;
        cmdMinThrottle = 0;
        cmdMaxThrottle = 0;
        plane.setThrottle(0);

        if (plane.getDeltaMovement().length() < AutopilotConfig.ROLLOUT_STOP_SPEED && plane.getOnGround()) {
            // report(), not overlay(): a sortie flown from the console has no owning player, and
            // overlay() no-ops when there is none — which is why a headless landing used to end in
            // silence with no way to tell it from an aircraft that simply vanished.
            outcomeReported = true;
            long down = Math.round(Math.abs(AutopilotMath.alongTrack(
                landingEnd.threshold(), landingEnd.landingHeading(), plane.position())));
            AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " landed at "
                + landingAirfield.name() + "/" + landingEnd.designator() + ", "
                + Math.round(plane.getX()) + ", " + Math.round(plane.getY()) + ", " + Math.round(plane.getZ())
                + " (" + down + (down == 1 ? " block" : " blocks") + " down the runway).");
            stop(plane);
        }
    }

    private void tickHold(PlaneEntity plane) {
        Vec3 fix = holdFix != null ? holdFix : plane.position();
        holdAngle += AutopilotConfig.HOLD_TURN_RATE;
        Vec3 orbitPoint = AutopilotMath.pointAlong(fix, holdAngle, AutopilotConfig.HOLD_RADIUS);
        cmdHeading = AutopilotMath.headingTo(plane.position(), orbitPoint);
        cmdTargetAltitude = fix.y;
        cmdSpeed = AutopilotConfig.APPROACH_SPEED;
        cmdBankLimit = AutopilotConfig.MAX_BANK;

        if (ticks % 20 == 0 && landingAirfield != null
            && RunwayOccupancy.isFree(plane.level(), landingAirfield.name(), plane)) {
            setMode(plane, AutopilotMode.DESCENT);
        }
    }

    private void tickGoAround(PlaneEntity plane) {
        if (landingEnd == null) {
            setMode(plane, AutopilotMode.CLIMB);
            return;
        }
        cmdHeading = landingEnd.landingHeading();
        cmdSpeed = AutopilotConfig.STRIKE_SPEED;
        cmdTargetAltitude = landingEnd.threshold().y + AutopilotConfig.PATTERN_HEIGHT;
        cmdBankLimit = 15;

        if (plane.position().y >= cmdTargetAltitude - 6) {
            holdFix = landingEnd.approachPoint(AutopilotConfig.FINAL_INTERCEPT_DISTANCE, AutopilotConfig.PATTERN_HEIGHT);
            setMode(plane, AutopilotMode.HOLD);
        }
    }

    private void goAround(PlaneEntity plane, String reason) {
        goArounds++;
        // report(), not overlay(): a headless sortie has no owning player, and a landing that never
        // happens is exactly the thing that needs to say why.
        AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " going around ("
            + goArounds + "/" + AutopilotConfig.MAX_GO_AROUNDS + "): " + reason + ".");
        if (landingAirfield != null) {
            RunwayOccupancy.release(plane.level(), landingAirfield.name(), plane);
        }
        if (goArounds == AutopilotConfig.MAX_GO_AROUNDS && landingEnd != null) {
            // Try the other direction once before giving up on a clean approach.
            landingEnd = landingEnd.opposite();
            AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " switching to runway " + landingEnd.designator() + ".");
        } else if (goArounds > AutopilotConfig.MAX_GO_AROUNDS) {
            // Out of patience: commit to the next approach even if it is untidy.
            gatesDisabled = true;
            AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " committing to the landing.");
        }
        setMode(plane, AutopilotMode.GO_AROUND);
    }

    // ------------------------------------------------------------------ control laws

    private void applyControls(PlaneEntity plane) {
        Vec3 position = plane.position();
        Vec3 velocity = plane.getDeltaMovement();
        double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        double yaw = plane.getYRot();
        double pitch = plane.getXRot();
        double roll = plane.rotationRoll;
        boolean onGround = plane.getOnGround();
        double rotationMultiplier = Math.max(0.1, plane.autopilotRotationSpeedMultiplier());

        if (!anglesInitialised) {
            previousYaw = yaw;
            previousPitch = pitch;
            previousRoll = roll;
            anglesInitialised = true;
        }
        double yawRate = Mth.wrapDegrees(yaw - previousYaw);
        double pitchRate = pitch - previousPitch;
        double rollRate = Mth.wrapDegrees(roll - previousRoll);

        double headingError = AutopilotMath.angleDelta(yaw, cmdHeading);

        // ---- yaw
        plane.setYawRight(AutopilotMath.bangBang(headingError, yawRate,
            AutopilotConfig.YAW_ACCEL * rotationMultiplier, AutopilotConfig.YAW_DEADBAND));

        // Flight path angle: the direction the aircraft is actually going, as opposed to the
        // direction it is pointing. The difference between the two is the angle of attack, and it is
        // what the whole anti-stall limiter below is about.
        double flightPathAngle = horizontalSpeed > 0.02
            ? Math.toDegrees(Math.atan2(velocity.y, horizontalSpeed))
            : pitch;

        // ---- pitch: altitude -> vertical speed -> flight path angle -> attitude
        double desiredPitch;
        if (cmdPitchOverride != null) {
            desiredPitch = cmdPitchOverride;
        } else {
            double altitudeError = cmdTargetAltitude - position.y;
            double desiredVerticalSpeed = Mth.clamp(altitudeError * AutopilotConfig.ALTITUDE_TO_VSPEED,
                -AutopilotConfig.MAX_SINK_RATE, AutopilotConfig.MAX_CLIMB_RATE);
            double desiredAngle = Math.toDegrees(Math.atan2(desiredVerticalSpeed, Math.max(horizontalSpeed, 0.05)));
            desiredAngle = Mth.clamp(desiredAngle, -cmdMaxDescentAngle, cmdMaxClimbAngle);
            desiredPitch = Mth.clamp(pitch + (desiredAngle - flightPathAngle) * AutopilotConfig.FPA_TO_PITCH,
                -AutopilotConfig.MAX_PITCH, AutopilotConfig.MAX_PITCH);
        }

        // ---- angle of attack limiter: the one rule that stops the aircraft falling out of the sky
        //
        // The cascade above is written in terms of altitude, and an aircraft that is sinking because
        // it has stalled looks exactly like an aircraft that is sinking because it is too high — so
        // the cascade answers both with nose-up. That is a divergence: more nose-up is more angle of
        // attack, PlaneEntity#tickRotateMotion scales lift by 1 - (aoa/60)^2, the lift goes to zero
        // at 60 degrees, the sink rate grows, and the cascade asks for more nose-up again. Field
        // telemetry of the failure: pitch +25 against a flight path of -79 degrees, 104 degrees of
        // angle of attack, no lift at all, 1.09 blocks/tick of sink, 33 blocks above the ground.
        //
        // Referencing the commanded attitude to the *current flight path* instead of to the horizon
        // makes the same limiter serve as the stall recovery: deep in a stall the flight path is
        // steeply down, so the clamp forces the nose down with it, the wings start working again,
        // and the aircraft flies out. Not applied on the ground, where the flight path angle is
        // meaningless and the take-off rotation legitimately holds the nose off the velocity vector.
        if (!onGround && velocity.lengthSqr() > 0.01) {
            desiredPitch = Mth.clamp(desiredPitch,
                flightPathAngle - AutopilotConfig.MAX_ANGLE_OF_ATTACK,
                flightPathAngle + AutopilotConfig.MAX_ANGLE_OF_ATTACK);
        }

        if (cmdNeutralPitch) {
            plane.setPitchUp((byte) 0);
        } else {
            plane.setPitchUp(AutopilotMath.bangBang(desiredPitch - pitch, pitchRate,
                AutopilotConfig.PITCH_ACCEL * rotationMultiplier, AutopilotConfig.PITCH_DEADBAND));
        }

        // ---- roll in the air, nosewheel steering on the ground
        if (onGround || cmdGroundSteer) {
            // PlaneEntity#tickRoll turns the strafe input into a yaw change of -3 degrees per tick
            // while on the ground, so a positive strafe steers left: use the opposite sign.
            moveStrafing = headingError > 1.0 ? -1f : headingError < -1.0 ? 1f : 0f;
        } else {
            // A banked turn needs 1/cos(bank) times the lift of level flight, and a slow aircraft has
            // no lift to spare — rolling into a hard turn at low speed is the other way into the
            // stall above. Give the bank up as the speed decays, and level the wings outright at
            // stall speed.
            double bankLimit = cmdBankLimit;
            if (horizontalSpeed < AutopilotConfig.BANK_LIMIT_SPEED) {
                bankLimit *= Mth.clamp(
                    (horizontalSpeed - AutopilotConfig.MIN_FLYING_SPEED)
                        / (AutopilotConfig.BANK_LIMIT_SPEED - AutopilotConfig.MIN_FLYING_SPEED),
                    0.0, 1.0);
            }
            // Positive rotationRoll is a left bank (positive strafe is the player's left input),
            // so a right turn wants a negative roll.
            double desiredRoll = Mth.clamp(-headingError * AutopilotConfig.BANK_PER_HEADING_ERROR,
                -bankLimit, bankLimit);
            double rollError = AutopilotMath.angleDelta(roll, desiredRoll);
            moveStrafing = AutopilotMath.bangBang(rollError, rollRate,
                AutopilotConfig.ROLL_ACCEL, AutopilotConfig.ROLL_DEADBAND);
        }
        moveForward = 0;

        applyThrottle(plane, horizontalSpeed, onGround, roll, headingError);

        previousYaw = yaw;
        previousPitch = pitch;
        previousRoll = roll;
    }

    /**
     * The engine lever.
     *
     * <p>Three things here are not what the first version did, and each of them was a way to lose an
     * aircraft.
     *
     * <p><b>It is flown on horizontal speed, not on total speed.</b> The loop used to compare
     * {@code getDeltaMovement().length()} against the commanded speed, which counts the rate of
     * falling as though it were progress. An aircraft dropping out of a descent at 1.09 blocks/tick
     * of sink therefore reads as <i>fast</i>, so the controller closes the throttle, so it gets
     * slower and falls faster, so it reads faster still. That is a latch, not a control loop, and it
     * was observed sitting in it at throttle 0 with 33 blocks of altitude left. What keeps the wings
     * working is airspeed along the wing, so that is what is regulated.
     *
     * <p><b>Idle is an airbrake, not neutral.</b> {@code PlaneEntity#tickMotion} multiplies the whole
     * drag polynomial by {@code brakesMul = 5} at throttle 0. Leaving
     * {@link AutopilotConfig#MIN_AIRBORNE_THROTTLE} in while airborne is the difference between
     * descending and decelerating — but only while the aircraft still <em>needs</em> the power. See
     * the overspeed branch below: on a boosted airframe that floor is by itself a cruise setting.
     *
     * <p><b>Stall recovery cannot wait for the next scheduled adjustment.</b> Below
     * {@link AutopilotConfig#MIN_FLYING_SPEED} the lever goes fully open on the spot rather than one
     * notch per {@link AutopilotConfig#THROTTLE_INTERVAL} ticks, which would take 25 ticks to reach
     * full power — longer than the aircraft has.
     */
    private void applyThrottle(PlaneEntity plane, double horizontalSpeed, boolean onGround,
                               double roll, double headingError) {
        int throttle = plane.getThrottle();
        int floor = onGround ? 0 : cmdMinThrottle;

        // Stall recovery is armed wherever there is an engine to open — including the descent and
        // approach phases, which are allowed to idle. Only the flare and the roll-out, which set
        // cmdMaxThrottle to 0 because stopping is the whole point, opt out.
        if (!onGround && cmdMaxThrottle > 0 && horizontalSpeed < AutopilotConfig.MIN_FLYING_SPEED) {
            plane.setThrottle(cmdMaxThrottle);
            return;
        }

        boolean manoeuvring = Math.abs(Mth.wrapDegrees(roll)) > AutopilotConfig.MANOEUVRE_BANK
            || Math.abs(headingError) > AutopilotConfig.MANOEUVRE_HEADING_ERROR;
        boolean overspeed = horizontalSpeed > cmdSpeed + AutopilotConfig.SPEED_DEADBAND;

        if (!onGround && cmdMinThrottle > 0 && manoeuvring) {
            // Turning costs energy, so a turn is the last moment to be closing the throttle — and it
            // was exactly where the throttle was being closed. Measured on a 200-block out-and-back:
            // rolling into the 180 at the far waypoint briefly pushed the speed above the cruise
            // target (the nose comes off the velocity vector, which un-fades the thrust in
            // PlaneEntity#tickMotion), the loop obediently wound the lever back from 5 to 1, and the
            // aircraft came out of the turn at 0.23 blocks/tick — below flying speed — and mushed 60
            // blocks into the ground.
            //
            // The rule is "do not reduce power", and it has to be written as exactly that. It used
            // to raise the floor to cmdMaxThrottle, which on the unboosted airframe of the day was
            // the same thing as holding station at 5 and on a boosted one is a command for full
            // power. Measured on an argument-free sortie after the booster was fitted: the 93-degree
            // turn off the departure runway put the lever on 10 and the aircraft climbed away at
            // 2.18 blocks/tick against a commanded 0.70, then took another 45 ticks to wind back
            // down. Holding whatever the loop had already chosen keeps the turn protection and
            // cannot invent thrust nothing asked for.
            floor = Math.max(floor, Math.min(throttle, cmdMaxThrottle));
        } else if (!onGround && overspeed) {
            // Above the commanded speed, so there is no power to protect: let the lever reach idle.
            //
            // MIN_AIRBORNE_THROTTLE assumes the notch it keeps in is a trickle. On the boosted
            // airframe every autopilot aircraft now carries it is not: setMaxSpeed(3.0) moves the
            // thrust fade-out in PlaneEntity#tickMotion to maxSpeed * 10 * (push + 0.05), which at
            // throttle 1 is 1.6875, and the drag curve balances that at 0.93 blocks/tick. Measured
            // on the rig: a cruise commanded at 0.80 sat at 0.93 with the lever on its floor of 1
            // for the whole 2000-block leg, because 0.93 *is* what that floor flies. An aircraft
            // that cannot be asked to go slower than its own minimum notch is not being regulated.
            //
            // Safe because the loop regulates horizontal speed, so a sink rate can no longer read as
            // airspeed and latch the lever shut, and because the stall recovery above re-opens it
            // the moment the speed falls below MIN_FLYING_SPEED.
            floor = 0;
        }

        if (ticks % AutopilotConfig.THROTTLE_INTERVAL == 0) {
            if (horizontalSpeed < cmdSpeed - AutopilotConfig.SPEED_DEADBAND && throttle < cmdMaxThrottle) {
                throttle++;
            } else if (horizontalSpeed > cmdSpeed + AutopilotConfig.THROTTLE_CUT_EXCESS) {
                // Far too fast, not merely a little fast: shut the lever now rather than walking it
                // down a notch every five ticks. See AutopilotConfig#THROTTLE_CUT_EXCESS for the
                // measurement — the walk down is worth over a hundred blocks of extra braking
                // distance, which is exactly the error the deceleration schedule cannot absorb.
                throttle = floor;
            } else if (overspeed && throttle > floor) {
                throttle--;
            }
        }

        int clamped = Mth.clamp(throttle, floor, cmdMaxThrottle);
        if (clamped != plane.getThrottle()) {
            plane.setThrottle(clamped);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Keeps a chunk bubble loaded around an aircraft so it goes on ticking far from any player, and
     * a second one on the ground it is about to fly over.
     *
     * <p>Two things here are not obvious and were both measured wrong before.
     *
     * <p><b>The radius is not the bubble.</b> {@code addTicketWithRadius(type, pos, r)} sets the
     * centre chunk to level {@code 33 - r} and the level climbs by one per chunk outwards, while
     * entities only tick at level 31 or below. A ticket of radius {@code r} therefore ticks entities
     * within {@code r - 2} chunks of the centre — vanilla's ender-pearl radius of 2 ticks exactly
     * one chunk. That is fine for a pearl, which is re-ticketed by the player, and useless for an
     * aircraft covering 3 blocks a tick.
     *
     * <p><b>The lead ticket is not an optimisation.</b> Without it the aircraft is permanently
     * flying at the edge of its own loaded area, so {@link TerrainScanner} reads
     * {@link TerrainScanner#UNKNOWN_HEIGHT} for most of its forward profile and the terrain
     * following degrades to "hold altitude".
     *
     * <p>The ticket still expires on its own ({@link TicketType#ENDER_PEARL} times out after 40
     * ticks), so nothing is leaked when the aircraft is destroyed. Renewal is driven from
     * {@link AutopilotRegistry} on the level tick, not from this aircraft's own tick, because an
     * aircraft that has stopped ticking cannot renew anything.
     */
    static void keepChunksLoaded(ServerLevel level, PlaneEntity plane) {
        ChunkPos here = ChunkPos.containing(plane.blockPosition());
        level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, here,
            AutopilotConfig.CHUNK_TICKET_RADIUS);

        Vec3 ahead = plane.position().add(plane.getDeltaMovement().scale(AutopilotConfig.CHUNK_TICKET_LEAD_TICKS));
        ChunkPos next = new ChunkPos(Mth.floor(ahead.x) >> 4, Mth.floor(ahead.z) >> 4);
        if (!next.equals(here)) {
            level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, next,
                AutopilotConfig.CHUNK_TICKET_RADIUS);
        }
    }

    private double groundBelow(PlaneEntity plane) {
        Vec3 position = plane.position();
        int surface = TerrainScanner.surfaceHeight(plane.level(), position.x, position.z);
        return surface == TerrainScanner.UNKNOWN_HEIGHT ? position.y : surface;
    }

    /** Resolves (once) which runway this flight is going to land on. */
    private boolean resolveLanding(PlaneEntity plane) {
        if (landingEnd != null) {
            return true;
        }
        if (plan == null) {
            return false;
        }
        Level level = plane.level();
        Airfield airfield = null;
        if (level instanceof ServerLevel serverLevel) {
            AutopilotSavedData data = AutopilotSavedData.get(serverLevel);
            if (plan.airfieldName() != null) {
                airfield = data.get(plan.airfieldName());
            }
            if (airfield == null) {
                Vec3 area = plan.fallbackLandingArea();
                if (area == null) {
                    area = plane.position();
                }
                airfield = data.nearest(area.x, area.z, 512);
            }
        }
        if (airfield == null) {
            // No surveyed runway anywhere near: put it down in the flattest strip of terrain we can
            // find at the first waypoint. Named per aircraft so two planes never share the "runway".
            Vec3 area = plan.fallbackLandingArea();
            if (area == null) {
                area = plane.position();
            }
            double heading = Airfield.flattestHeading(level, area, 80);
            airfield = Airfield.improvise(level, "field-" + plane.getId(), area, heading, 80);
            AutopilotFeedback.overlay(owner, "Plane #" + plane.getId() + ": no airfield, improvising a landing");
        }
        landingAirfield = airfield;
        landingEnd = airfield.bestEnd(level);
        plan.setAirfieldName(airfield.name());
        return true;
    }

    private void setMode(PlaneEntity plane, AutopilotMode next) {
        if (mode == next) {
            return;
        }
        mode = next;
        modeTicks = 0;
        if (landingAirfield != null && !next.usesRunway()) {
            RunwayOccupancy.release(plane.level(), landingAirfield.name(), plane);
        }
        AutopilotFeedback.mode(owner, plane, next);
    }

    /**
     * One-line telemetry for {@code /autopilot status}: everything needed to tell "flying the
     * approach correctly" apart from "stuck in mid-air" without a game client. Prints the actual
     * state and the commanded state side by side, so a controller that is not tracking is obvious.
     */
    public String statusLine(PlaneEntity plane) {
        Vec3 position = plane.position();
        Vec3 velocity = plane.getDeltaMovement();
        StringBuilder builder = new StringBuilder();
        builder.append('#').append(plane.getId())
            .append(' ').append(mode.getName())
            .append(String.format(" pos=%.0f,%.0f,%.0f", position.x, position.y, position.z))
            .append(String.format(" agl=%.0f", position.y - groundBelow(plane)))
            .append(String.format(" hdg=%03d", AutopilotMath.compassDisplay(plane.getYRot())))
            .append(String.format(" pitch=%+.0f roll=%+.0f", plane.getXRot(), Mth.wrapDegrees(plane.rotationRoll)))
            .append(String.format(" spd=%.2f vs=%+.2f", velocity.length(), velocity.y))
            .append(" thr=").append(plane.getThrottle())
            .append(String.format(" want[hdg=%03d alt=%.0f spd=%s]",
                AutopilotMath.compassDisplay(cmdHeading), cmdTargetAltitude,
                cmdSpeed >= AutopilotConfig.STRIKE_SPEED ? "MAX" : String.format("%.2f", cmdSpeed)));

        Vec3 target = currentTarget();
        if (target != null) {
            builder.append(String.format(" tgt=%.0f,%.0f,%.0f dist=%.0f",
                target.x, target.y, target.z, AutopilotMath.horizontalDistance(position, target)));
        }
        if (landingEnd != null) {
            builder.append(" rwy=").append(landingAirfield == null ? "?" : landingAirfield.name())
                .append('/').append(landingEnd.designator());
        }
        if (goArounds > 0) {
            builder.append(" go-arounds=").append(goArounds);
        }
        if (plan != null && plan.kind() == FlightPlan.Kind.ROUTE) {
            builder.append(" legs=").append(plan.legsFlown()).append('/').append(plan.maxLegs());
        }
        return builder.toString();
    }

    /** Whatever the current mode is actually steering towards, for the status readout. */
    private @Nullable Vec3 currentTarget() {
        if (plan == null) {
            return null;
        }
        if (plan.kind() == FlightPlan.Kind.STRIKE) {
            return plan.strikeTargetVec();
        }
        if (landingEnd != null && (mode == AutopilotMode.DESCENT || mode == AutopilotMode.HOLD
            || mode == AutopilotMode.GO_AROUND || mode.usesRunway())) {
            return landingEnd.threshold();
        }
        return plan.currentWaypoint();
    }

    public String describe(PlaneEntity plane) {
        StringBuilder builder = new StringBuilder();
        builder.append("Plane #").append(plane.getId()).append(" mode=").append(mode.getName());
        if (plan != null) {
            builder.append(", ").append(plan.describe());
        }
        if (landingEnd != null) {
            builder.append(", runway ").append(landingEnd.designator());
        }
        if (goArounds > 0) {
            builder.append(", go-arounds ").append(goArounds);
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Writes the flight into the plane's save data, so a route survives a restart.
     *
     * <p>Strike aircraft are deliberately <em>not</em> written: the same method backs
     * {@code PlaneEntity#getItemStack}, so persisting them would let a destroyed strike plane drop
     * an item that launches a fresh attack run when placed.
     */
    public void save(ValueOutput output) {
        if (!isActive() || !persistent) {
            return;
        }
        ValueOutput child = output.child("autopilot");
        child.putString("mode", mode.getName());
        child.store("plan", FlightPlan.CODEC, plan);
        child.putInt("go_arounds", goArounds);
        child.putBoolean("gates_disabled", gatesDisabled);
        child.putBoolean("powered", autopilotPowered);
    }

    /** Restores a saved flight onto a freshly loaded plane. */
    public static void load(PlaneEntity plane, ValueInput input) {
        // readAdditionalSaveData also backs PlaneEntity#loadFromItemTag, which runs on both logical
        // sides. The autopilot is server-only state, and in single player the client and server
        // share a JVM, so attaching one here would double-count the traffic slot.
        if (plane.level().isClientSide()) {
            return;
        }
        Optional<ValueInput> childOptional = input.child("autopilot");
        if (childOptional.isEmpty()) {
            return;
        }
        ValueInput child = childOptional.get();
        Optional<FlightPlan> planOptional = child.read("plan", FlightPlan.CODEC);
        if (planOptional.isEmpty()) {
            return;
        }
        if (!AutopilotRegistry.canActivateAnother()) {
            return;
        }
        PlaneAutopilot autopilot = new PlaneAutopilot();
        autopilot.plan = planOptional.get();
        autopilot.mode = AutopilotMode.byName(child.getStringOr("mode", AutopilotMode.CRUISE.getName()));
        autopilot.goArounds = child.getIntOr("go_arounds", 0);
        autopilot.gatesDisabled = child.getBooleanOr("gates_disabled", false);
        autopilot.autopilotPowered = child.getBooleanOr("powered", true);
        autopilot.persistent = true;
        autopilot.active = true;
        // A reloaded flight resumes in the air; a half-finished taxi is not worth restoring, and
        // TAXI without a departure runway would sit on the threshold forever.
        if (autopilot.mode == AutopilotMode.TAXI) {
            autopilot.mode = AutopilotMode.TAKEOFF;
        }
        plane.setAutopilot(autopilot);
        AutopilotRegistry.register(plane);
    }
}
