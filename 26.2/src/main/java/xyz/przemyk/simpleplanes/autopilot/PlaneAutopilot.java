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

    private final TerrainScanner scanner = new TerrainScanner();
    private @Nullable Airfield landingAirfield;
    private @Nullable RunwayEnd landingEnd;
    private @Nullable Vec3 holdFix;
    private double holdAngle;

    private int ticks;
    private int modeTicks;

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
        this.holdFix = null;
        this.anglesInitialised = false;
        if (!active) {
            active = true;
            RunwayOccupancy.onAutopilotActivated();
        }
        if (flightPlan.kind() == FlightPlan.Kind.STRIKE) {
            setMode(plane, AutopilotMode.STRIKE);
        } else if (plane.getOnGround()) {
            setMode(plane, AutopilotMode.TAKEOFF);
        } else {
            setMode(plane, AutopilotMode.CLIMB);
        }
    }

    public void stop(PlaneEntity plane) {
        if (active) {
            active = false;
            RunwayOccupancy.onAutopilotDeactivated();
        }
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

        switch (mode) {
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

        applyTerrainFollowing(plane);
        applyControls(plane);
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

    private void tickTakeoff(PlaneEntity plane) {
        double heading = landingEnd != null ? landingEnd.landingHeading() : plane.getYRot();
        if (landingEnd == null && plan != null && plan.hasWaypoints()) {
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

        double speed = plane.getDeltaMovement().length();
        // Hold the nose down until the aircraft is actually flying, then rotate.
        cmdPitchOverride = speed >= AutopilotConfig.ROTATE_SPEED ? 12.0 : 0.0;

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
        cmdSpeed = AutopilotConfig.CLIMB_SPEED;
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
        cmdSpeed = AutopilotConfig.CRUISE_SPEED;

        if (AutopilotMath.horizontalDistance(plane.position(), waypoint) < AutopilotConfig.WAYPOINT_ARRIVAL_RADIUS) {
            boolean routeComplete = plan.advance();
            AutopilotFeedback.overlay(owner, "Plane #" + plane.getId() + ": waypoint reached ("
                + plan.legsFlown() + "/" + plan.maxLegs() + " legs)");
            if (routeComplete) {
                beginLanding(plane);
            }
        }
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

        double distance = AutopilotMath.horizontalDistance(position, target);
        if (distance > AutopilotConfig.STRIKE_DIVE_DISTANCE) {
            // Cruise in high enough to clear the terrain between here and the target.
            cmdTargetAltitude = Math.max(target.y + 60, plan.cruiseAltitude());
            cmdTerrainFollow = true;
        } else {
            // Committed: dive at the aim point, terrain following off, steep descent allowed.
            cmdTargetAltitude = target.y;
            cmdTerrainFollow = false;
            cmdBankLimit = 10;
            cmdMaxDescentAngle = 55.0;
        }

        if (position.distanceTo(target) < 3.0) {
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

        double agl = position.y - groundBelow(plane);

        // Flew past the threshold without getting down: go around.
        if (distanceToThreshold < -5) {
            goAround(plane);
            return;
        }

        // Real raycast down the approach corridor, so a hill in the way is caught even when the
        // heightmap profile looks fine. Skipped close in, where the runway itself is the hit.
        if (!gatesDisabled && agl > 15 && ticks % 20 == 0) {
            Vec3 aim = landingEnd.aimPoint();
            if (!TerrainScanner.pathClear(plane.level(), plane, position, new Vec3(aim.x, aim.y + 2, aim.z))) {
                AutopilotFeedback.overlay(owner, "Plane #" + plane.getId() + ": terrain on approach, going around");
                goAround(plane);
                return;
            }
        }

        if (!isFinal) {
            if (distanceToThreshold < 150) {
                setMode(plane, AutopilotMode.FINAL);
            }
            return;
        }

        if (!gatesDisabled && !gatesSatisfied(plane, lateral, agl)) {
            goAround(plane);
            return;
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
    private boolean gatesSatisfied(PlaneEntity plane, double lateral, double agl) {
        if (agl > AutopilotConfig.GATE_CHECK_HEIGHT) {
            return true;
        }
        double headingError = Math.abs(AutopilotMath.angleDelta(plane.getYRot(), landingEnd.landingHeading()));
        if (headingError > AutopilotConfig.GATE_HEADING_ERROR) {
            return false;
        }
        double allowedLateral = Math.max(AutopilotConfig.GATE_LATERAL_OFFSET, landingAirfield.width());
        if (Math.abs(lateral) > allowedLateral) {
            return false;
        }
        if (Math.abs(Mth.wrapDegrees(plane.rotationRoll)) > AutopilotConfig.GATE_BANK) {
            return false;
        }
        return -plane.getDeltaMovement().y <= AutopilotConfig.GATE_SINK_RATE;
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
        plane.setThrottle(0);

        if (plane.getDeltaMovement().length() < AutopilotConfig.ROLLOUT_STOP_SPEED && plane.getOnGround()) {
            AutopilotFeedback.overlay(owner, "Plane #" + plane.getId() + ": landed at " + landingAirfield.name());
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

    private void goAround(PlaneEntity plane) {
        goArounds++;
        if (landingAirfield != null) {
            RunwayOccupancy.release(plane.level(), landingAirfield.name(), plane);
        }
        if (goArounds == AutopilotConfig.MAX_GO_AROUNDS && landingEnd != null) {
            // Try the other direction once before giving up on a clean approach.
            landingEnd = landingEnd.opposite();
            AutopilotFeedback.overlay(owner, "Plane #" + plane.getId() + ": switching to runway " + landingEnd.designator());
        } else if (goArounds > AutopilotConfig.MAX_GO_AROUNDS) {
            // Out of patience: commit to the next approach even if it is untidy.
            gatesDisabled = true;
            AutopilotFeedback.overlay(owner, "Plane #" + plane.getId() + ": committing to landing");
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
            double currentAngle = horizontalSpeed > 0.02
                ? Math.toDegrees(Math.atan2(velocity.y, horizontalSpeed))
                : pitch;
            desiredPitch = Mth.clamp(pitch + (desiredAngle - currentAngle) * AutopilotConfig.FPA_TO_PITCH,
                -AutopilotConfig.MAX_PITCH, AutopilotConfig.MAX_PITCH);
        }
        plane.setPitchUp(AutopilotMath.bangBang(desiredPitch - pitch, pitchRate,
            AutopilotConfig.PITCH_ACCEL * rotationMultiplier, AutopilotConfig.PITCH_DEADBAND));

        // ---- roll in the air, nosewheel steering on the ground
        if (onGround || cmdGroundSteer) {
            // PlaneEntity#tickRoll turns the strafe input into a yaw change of -3 degrees per tick
            // while on the ground, so a positive strafe steers left: use the opposite sign.
            moveStrafing = headingError > 1.0 ? -1f : headingError < -1.0 ? 1f : 0f;
        } else {
            // Positive rotationRoll is a left bank (positive strafe is the player's left input),
            // so a right turn wants a negative roll.
            double desiredRoll = Mth.clamp(-headingError * AutopilotConfig.BANK_PER_HEADING_ERROR,
                -cmdBankLimit, cmdBankLimit);
            double rollError = AutopilotMath.angleDelta(roll, desiredRoll);
            moveStrafing = AutopilotMath.bangBang(rollError, rollRate,
                AutopilotConfig.ROLL_ACCEL, AutopilotConfig.ROLL_DEADBAND);
        }
        moveForward = 0;

        // ---- throttle
        if (ticks % AutopilotConfig.THROTTLE_INTERVAL == 0) {
            double speed = velocity.length();
            int throttle = plane.getThrottle();
            if (speed < cmdSpeed - AutopilotConfig.SPEED_DEADBAND && throttle < PlaneEntity.MAX_THROTTLE) {
                plane.setThrottle(throttle + 1);
            } else if (speed > cmdSpeed + AutopilotConfig.SPEED_DEADBAND && throttle > 0) {
                plane.setThrottle(throttle - 1);
            }
        }

        previousYaw = yaw;
        previousPitch = pitch;
        previousRoll = roll;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Keeps a 5x5 chunk bubble loaded around an aircraft so it goes on ticking far from any player.
     * The ticket expires on its own ({@link TicketType#ENDER_PEARL} times out after 40 ticks), so
     * nothing is leaked if the aircraft is destroyed.
     */
    static void keepChunksLoaded(ServerLevel level, PlaneEntity plane) {
        level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, ChunkPos.containing(plane.blockPosition()), 2);
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
            .append(String.format(" hdg=%03.0f", AutopilotMath.compassHeading(plane.getYRot())))
            .append(String.format(" pitch=%+.0f roll=%+.0f", plane.getXRot(), Mth.wrapDegrees(plane.rotationRoll)))
            .append(String.format(" spd=%.2f vs=%+.2f", velocity.length(), velocity.y))
            .append(" thr=").append(plane.getThrottle())
            .append(String.format(" want[hdg=%03.0f alt=%.0f spd=%.2f]",
                AutopilotMath.compassHeading(cmdHeading), cmdTargetAltitude, cmdSpeed));

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
        if (!RunwayOccupancy.canActivateAnother()) {
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
        RunwayOccupancy.onAutopilotActivated();
        plane.setAutopilot(autopilot);
    }
}
