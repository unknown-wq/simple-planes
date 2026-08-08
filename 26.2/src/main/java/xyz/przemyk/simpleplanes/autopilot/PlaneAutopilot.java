package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesRegistries;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.booster.BoosterUpgrade;

import java.util.ArrayList;
import java.util.List;
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

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-autopilot");
    /** Per-tick flight telemetry to the server log; see {@link #trace}. */
    private static final boolean TRACE = Boolean.getBoolean("simpleplanes.autopilot.trace");

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
    /** Decides between climbing over the terrain ahead and going round it. See {@link RoutePlanner}. */
    private final RoutePlanner router = new RoutePlanner();
    /** How the arrival is being flown and why; null until the aircraft starts down. */
    private @Nullable ArrivalPlan arrival;
    private @Nullable Airfield landingAirfield;
    private @Nullable RunwayEnd landingEnd;
    /** Runway this sortie departs from, resolved once at launch; null for an airborne launch. */
    private @Nullable RunwayEnd departureEnd;
    /** Ticks still to sit on the parking spot before the runway is asked for. */
    private int departureHoldTicks;
    /** Set once "waiting for the runway" has been reported, so a long wait says it exactly once. */
    private boolean departureBlockedReported;
    /** Marked stand this arrival is taxiing to, and standing on once it gets there. */
    private Airfield.@Nullable ParkingSpot standTarget;
    /** Legs still to drive on the way to the stand; the last one is the stand itself. */
    private List<Vec3> taxiInRoute = List.of();
    /**
     * Whether the taxi in has left the surveyed rectangle yet.
     *
     * <p>A field rather than a test inside {@link #holdsRunway} because that method is called from
     * {@link RunwayOccupancy} without the aircraft's position to hand, and answering it from a stale
     * position would be worse than answering it from a flag written by the tick that measured it.
     */
    private boolean clearOfRunway;
    /** Consecutive ticks the taxi in has spent going nowhere. */
    private int taxiInStalledTicks;
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
        this.departureHoldTicks = 0;
        this.departureBlockedReported = false;
        this.standTarget = null;
        this.taxiInRoute = List.of();
        this.clearOfRunway = false;
        this.taxiInStalledTicks = 0;
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
            // Always through PARKED, even with no delay ordered: this is where the runway is asked
            // for, and a zero delay simply means the first tick asks for it.
            departureHoldTicks = flightPlan.departureDelayTicks();
            setMode(plane, AutopilotMode.PARKED);
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

    /** Designator of the runway end this arrival has settled on, or null before it has chosen one. */
    public @Nullable String landingDesignator() {
        return landingEnd == null ? null : landingEnd.designator();
    }

    /**
     * True while this aircraft is entitled to keep a runway reservation.
     *
     * <p>Two ways to be entitled to one, and they are at opposite ends of the flight. An arrival
     * holds the field it is landing at from the moment it commits to the approach; a departure holds
     * the field it is leaving from the moment it starts to roll until it is airborne and clear.
     * {@link RunwayOccupancy} validates every reservation against this method rather than trusting
     * its map, so a departure that is destroyed, despawned or switched off on the taxiway stops
     * holding the runway without anything having to notice.
     *
     * <p>The arrival's end of it is no longer "until the wheels stop". An aircraft that has landed
     * and is taxiing to a stand goes on holding the strip until it is physically off it — which is
     * later than the roll-out and much earlier than the end of the taxi, and neither of those is a
     * mode change. {@link #clearOfRunway} is written by {@code tickTaxiIn} from a real rectangle test
     * and read here.
     */
    public boolean holdsRunway(String airfieldName) {
        if (!active) {
            return false;
        }
        if (departureEnd != null && mode.holdsDepartureRunway()
            && departureEnd.airfield().name().equals(airfieldName)) {
            return true;
        }
        if (landingAirfield == null || !landingAirfield.name().equals(airfieldName)) {
            return false;
        }
        return mode.usesRunway() || (mode == AutopilotMode.TAXI_IN && !clearOfRunway);
    }

    /**
     * Whether this aircraft has spoken for a marked stand — either taxiing to it or standing on it.
     *
     * <p>The half of "is that stand free" that an entity search cannot see. A taxi in takes hundreds
     * of ticks, and for all of them the aircraft is somewhere between the runway and a square it
     * fully intends to occupy; without this a second arrival picks the same square and drives into
     * it. Asked of the live autopilots rather than of a reservation registry, so it cannot outlive
     * the aircraft — see {@link Airfield#standFree}.
     */
    public boolean claimsStand(BlockPos spot) {
        return active && standTarget != null && spot.equals(standTarget.marked());
    }

    /** The stand this arrival is taxiing to or standing on, for the status readout and the board. */
    public @Nullable BlockPos claimedStand() {
        return standTarget == null ? null : standTarget.marked();
    }

    /**
     * The field this aircraft is still on the ground at, or null once it is airborne and clear of it.
     *
     * <p>The tower board's way of telling a departure from an arrival: while this is non-null the
     * aircraft's business is with the runway it is leaving, not with the one it is going to.
     */
    public @Nullable String departureAirfieldName() {
        if (!active || departureEnd == null
            || !(mode == AutopilotMode.PARKED || mode.holdsDepartureRunway())) {
            return null;
        }
        return departureEnd.airfield().name();
    }

    /**
     * Ticks left on the departure clock while parked: positive when the aircraft is waiting for the
     * clock, 0 when it is parked waiting for the runway, and -1 when it is not parked at all.
     *
     * <p>Three states rather than two because "waiting" that cannot say <em>what for</em> is
     * indistinguishable from a hang, which is the whole reason this is exposed.
     */
    public int departureHoldTicks() {
        return mode == AutopilotMode.PARKED ? departureHoldTicks : -1;
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
            case PARKED -> tickParked(plane);
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
            case TAXI_IN -> tickTaxiIn(plane);
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
        trace(plane);
    }

    /**
     * Per-tick telemetry to the server log, off unless the JVM is started with
     * {@code -Dsimpleplanes.autopilot.trace=true}.
     *
     * <p>{@code /autopilot status} is a snapshot at whatever rate a shell can poll it, and the
     * events this feature gets wrong last a handful of ticks: the flare fires, the throttle shuts
     * and the aircraft is in the water forty ticks later. This prints every tick, which is what it
     * took to see that the flare was being entered 15 blocks short of a runway over a waterline the
     * heightmap was reporting as ground. It goes to the log rather than through
     * {@link AutopilotFeedback}, because a per-tick line sent to an owning player is unusable.
     */
    private void trace(PlaneEntity plane) {
        if (!TRACE) {
            return;
        }
        Vec3 position = plane.position();
        double ground = groundBelow(plane);
        String runway = "";
        if (landingEnd != null) {
            double runwayHeading = landingEnd.landingHeading();
            // daim is the one the arrival is actually flown to: the glide slope ends on it, the
            // flare is triggered relative to it and the "still airborne" go-around is measured from
            // it. dthr is kept beside it because the threshold is what the report and the survey
            // speak in, and watching the two diverge is how the aim rule is checked.
            double dthr = -AutopilotMath.alongTrack(landingEnd.threshold(), runwayHeading, position);
            runway = String.format(" thr_y=%.1f dthr=%.1f daim=%.1f lat=%.1f", landingEnd.threshold().y,
                dthr, dthr + landingEnd.aimOffset(),
                AutopilotMath.lateralOffset(landingEnd.threshold(), runwayHeading, position));
        }
        // hdg/cmdhdg/roll are here because every lateral defect this feature has had is invisible
        // without them. A heading used to be recoverable only by differencing two pos= samples, and
        // that gives the track rather than where the nose points — while the landing gates are
        // written about the nose. It also cannot separate "not tracking the command" from "tracking
        // a command that is wrong", which is exactly the distinction the cargo approach turned on:
        // the aircraft was holding its commanded heading to the degree, and the command was 40
        // degrees off the runway.
        double heading = Mth.wrapDegrees(plane.getYRot());
        LOGGER.info(String.format(
            "trace #%d t=%d %s pos=%.1f,%.2f,%.1f agl=%.2f gnd=%.1f landable=%b vs=%+.3f spd=%.3f"
                + " thr=%d og=%b water=%b hdg=%.1f cmdhdg=%.1f roll=%+.1f cmdalt=%.1f%s",
            plane.getId(), ticks, mode.getName(), position.x, position.y, position.z,
            position.y - ground, ground, landableBelow(plane),
            plane.getDeltaMovement().y, plane.getDeltaMovement().horizontalDistance(),
            plane.getThrottle(), plane.getOnGround(), plane.isOnWater(),
            heading < 0 ? heading + 360 : heading, Mth.wrapDegrees(cmdHeading) < 0
                ? Mth.wrapDegrees(cmdHeading) + 360 : Mth.wrapDegrees(cmdHeading),
            Mth.wrapDegrees(plane.rotationRoll), cmdTargetAltitude, runway));
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
        // Water gets its own word. "Came down at" reads as a heavy landing on a field, and an
        // aircraft that has stopped flying over an ocean has done something quite different — it is
        // on the sea floor. The report is the only place anyone finds out which of the two happened,
        // and getOnGround() is true for both, because tickOnGround treats water as ground.
        AutopilotFeedback.report(owner, "Plane #" + plane.getId()
            + (plane.isOnWater() ? " ditched in water at " : " came down at ")
            + Math.round(plane.getX()) + ", " + Math.round(plane.getY()) + ", " + Math.round(plane.getZ())
            + " in " + mode.getName() + ".");
        stop(plane);
        return true;
    }

    /**
     * Keeps the aircraft clear of the terrain ahead, by climbing over it or by going round it —
     * whichever is cheaper.
     *
     * <p>The climb half is unchanged: the commanded altitude is raised to the heightmap profile plus
     * {@link AutopilotConfig#TERRAIN_CLEARANCE}. What is new is that going round is now a decision
     * rather than a reflex. {@link RoutePlanner} scores a handful of candidate headings against
     * flying straight on and returns an offset only when the deviation genuinely costs less than the
     * climb; over open ground it returns zero and this is exactly the old behaviour.
     *
     * <p>The planner only runs when there is something to run for — terrain above the altitude this
     * leg would otherwise be flown at, or a deviation already in progress — so flat terrain costs
     * nothing at all. {@code TerrainScanner#avoidanceBias} remains as the fallback for the case the
     * planner declines to answer, which is ground it cannot see; it never over-rides a planned
     * deviation.
     */
    private void applyTerrainFollowing(PlaneEntity plane) {
        if (!cmdTerrainFollow) {
            router.reset();
            return;
        }
        double legAltitude = cmdTargetAltitude;
        double safeAltitude = scanner.safeAltitude();
        if (safeAltitude != TerrainScanner.UNKNOWN_HEIGHT && safeAltitude > cmdTargetAltitude) {
            cmdTargetAltitude = safeAltitude;
        }

        // Run the search when there is terrain to answer for — and also whenever a deviation is
        // already being flown, because that is the only thing that ever clears one. The scanner
        // profile follows the aircraft's actual heading, so once it is round the obstacle the ground
        // ahead looks clear, and "only plan when the ground ahead is high" would leave the offset
        // latched on for the rest of the leg.
        boolean terrainAhead = safeAltitude != TerrainScanner.UNKNOWN_HEIGHT && safeAltitude > legAltitude;
        if (terrainAhead || router.deviating()) {
            router.update(plane.level(), plane.position(), legAltitude, cmdHeading, ticks);
        }
        if (router.deviating()) {
            cmdHeading += router.headingOffset();
            return;
        }

        int bias = scanner.avoidanceBias(plane.position().y);
        if (bias != 0) {
            cmdHeading += bias * AutopilotConfig.AVOID_HEADING_BIAS;
        }
    }

    // ------------------------------------------------------------------ modes

    /**
     * Standing on the parking spot, waiting for the departure clock and then for the runway.
     *
     * <p>Two gates, in that order, and they are not the same kind of wait. The clock is what the
     * launch command asked for and runs down whatever else is happening. The runway is a fact about
     * the world at the moment the aircraft wants to move, so it is asked for only once the clock has
     * run out — asking earlier would reserve a strip for an aircraft that is not going to use it for
     * another five minutes, which is worse than not reserving one at all.
     *
     * <p>Nothing is commanded here. Throttle 0, no steering, elevator neutral — the same neutral the
     * taxi needs and for the same reason ({@code tickOnGround} reads a negative pitch input as
     * reverse thrust, see {@link #tickTaxi}), except that here it is the difference between an
     * aircraft that stands still and one that slowly reverses off its spot over five minutes of
     * waiting.
     *
     * <p>The reservation is taken <em>before</em> the mode changes, so the aircraft is never in
     * {@code TAXI} without holding the runway. {@link RunwayOccupancy#tryOccupy} is idempotent for
     * the aircraft that already owns the strip, so the poll is safe to repeat.
     */
    private void tickParked(PlaneEntity plane) {
        if (departureEnd == null) {
            setMode(plane, AutopilotMode.TAKEOFF);
            return;
        }
        cmdGroundSteer = true;
        cmdBankLimit = 0;
        cmdTerrainFollow = false;
        cmdSpeed = 0;
        cmdMinThrottle = 0;
        cmdMaxThrottle = 0;
        cmdNeutralPitch = true;
        plane.setThrottle(0);

        if (departureHoldTicks > 0) {
            departureHoldTicks--;
            return;
        }

        // Polled on the autopilot's own tick counter, exactly as tickHold polls for an arrival, so
        // aircraft launched at different moments are out of phase with each other and a departure
        // cannot poll a holding arrival out of the runway simply by asking more often.
        if (ticks % AutopilotConfig.DEPARTURE_POLL_INTERVAL != 0) {
            return;
        }
        String airfield = departureEnd.airfield().name();
        if (!RunwayOccupancy.tryOccupy(plane.level(), airfield, plane)) {
            if (!departureBlockedReported) {
                departureBlockedReported = true;
                PlaneEntity holder = RunwayOccupancy.holder(plane.level(), airfield);
                AutopilotFeedback.report(owner, "Plane #" + plane.getId()
                    + " holding on the parking spot at " + airfield + ": runway occupied"
                    + (holder == null ? "" : " by #" + holder.getId()) + ".");
            }
            return;
        }
        AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " cleared to taxi at "
            + airfield + "/" + departureEnd.designator() + " after " + modeTicks / 20
            + "s on the parking spot.");
        setMode(plane, AutopilotMode.TAXI);
    }

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

    /**
     * The run down to the point where the final approach is joined.
     *
     * <p>Two things here used to be constants and are now decisions, and both were costing whole
     * minutes on the clock the user actually watches — the one from arriving overhead to the wheels
     * stopping.
     *
     * <p><b>Where the final is joined.</b> Not a fixed 300 blocks at circuit height any more; see
     * {@link ArrivalPlan}. An aircraft that arrives high joins the same glide slope further out
     * instead of pressing on, crossing the threshold airborne and going around, which on the rig
     * turned a 66-second arrival into a 150-second one.
     *
     * <p><b>The speed.</b> This leg used to be flown at {@link AutopilotConfig#APPROACH_SPEED} from
     * the first tick, which is 0.5 blocks/tick for however many hundred blocks the fix happens to
     * be away — measured at 31 seconds of straight-line flying on an ordinary arrival, and it is
     * worse than merely slow: the flight path angle is capped, so the sink rate available is
     * {@code v * tan(12 deg)}, and flying slowly is what makes an aircraft unable to get down. It is
     * now the same deceleration schedule the cruise uses, aimed at
     * {@link AutopilotConfig#APPROACH_TRANSIT_SPEED} at the fix, so the aircraft arrives braked
     * rather than spending the whole leg braked.
     */
    private void tickDescent(PlaneEntity plane) {
        if (!resolveLanding(plane)) {
            stop(plane);
            return;
        }
        boolean free = RunwayOccupancy.isFree(plane.level(), landingAirfield.name(), plane);
        ArrivalPlan planned = ArrivalPlan.decide(landingEnd, plane.position(), free,
            plane.autopilotRotationSpeedMultiplier());
        announceArrival(plane, planned);

        Vec3 initialFix = planned.interceptFix();
        cmdHeading = AutopilotMath.headingTo(plane.position(), initialFix);
        cmdTargetAltitude = initialFix.y;
        double toFix = AutopilotMath.horizontalDistance(plane.position(), initialFix);
        // Two separate limits, and the aircraft flies the lower. The schedule says how fast it may
        // still be going by the time it reaches the fix; the turn limit says how fast it may be
        // going *now* and still be able to point at the fix at all. Without the second one a
        // slow-turning airframe never arrives, so the distance never closes, so the schedule never
        // brakes it — see turnLimitedSpeed.
        cmdSpeed = Math.min(
            AutopilotMath.speedSchedule(cruiseSpeed(),
                speedAtFix(cmdHeading, landingEnd.landingHeading()),
                toFix / AutopilotConfig.DECELERATION_MARGIN),
            turnLimitedSpeed(plane, cmdHeading, toFix));
        // Idle is allowed from here to the ground: throttle 0 puts brakesMul = 5 on the drag
        // polynomial, and that airbrake is the only way to slow down on an 8-degree slope. It is
        // safe now that the throttle loop regulates horizontal speed rather than total speed, so a
        // sink rate can no longer masquerade as airspeed and latch the lever shut.
        cmdMinThrottle = 0;

        if (planned.entry().circling()) {
            holdFix = initialFix;
            setMode(plane, AutopilotMode.HOLD);
            return;
        }

        if (toFix < arrivalRadius(plane) + 20) {
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
     * Records the arrival plan and says so once, when it changes. Reported rather than whispered:
     * "why is it circling" is precisely the question a headless log has to be able to answer, and
     * the reason phrase is the answer.
     */
    private void announceArrival(PlaneEntity plane, ArrivalPlan planned) {
        boolean changed = arrival == null || arrival.entry() != planned.entry()
            || Math.abs(arrival.interceptDistance() - planned.interceptDistance()) > 1.0;
        arrival = planned;
        if (changed) {
            AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " arrival at "
                + landingAirfield.name() + "/" + planned.end().designator() + ": " + planned.reason() + ".");
        }
    }

    /** The cruise speed this flight was ordered to fly, or the default for a plan-less aircraft. */
    private double cruiseSpeed() {
        return plan == null ? AutopilotConfig.CRUISE_SPEED : plan.cruiseSpeed();
    }

    /**
     * How fast the aircraft may arrive at the point where it joins the final, given the turn it has
     * to make there.
     *
     * <p>The turn radius is {@code v / yawRate}, so a 180-degree join — which is what an arrival
     * from the far side of the field always is — displaces the aircraft {@code 2v / yawRate}
     * sideways before it rolls out. Measured on the rig when the approach transit speed was applied
     * regardless: the aircraft reached the fix at 1.91 blocks/tick, swung 87 blocks off the
     * centreline coming round, was still 12 blocks off and 15 degrees skewed at the gate, and went
     * around — which cost far more than the speed saved. At approach speed the same turn displaces
     * 11 blocks and rolls out on the line.
     *
     * <p>An aircraft that is already pointing more or less down the runway has no such turn to fly,
     * which is the common case and the one the transit speed exists for.
     */
    private static double speedAtFix(double headingToFix, double runwayHeading) {
        double turn = Math.abs(AutopilotMath.angleDelta(headingToFix, runwayHeading));
        return turn > AutopilotConfig.APPROACH_TURN_SLOW_ANGLE
            ? AutopilotConfig.APPROACH_SPEED
            : AutopilotConfig.APPROACH_TRANSIT_SPEED;
    }

    /**
     * Fastest the aircraft may fly and still be able to turn onto a point {@code distance} away that
     * currently sits {@code headingToPoint} off the nose.
     *
     * <p>{@link #speedAtFix} answers how fast the aircraft may <em>arrive</em>; this answers how fast
     * it may be going on the way, and without it a slow-turning airframe never arrives at all.
     *
     * <p><b>The latch.</b> The descent commands the bearing to the fix and brakes on a schedule keyed
     * to the distance to it. A cargo plane leaving cruise turns at 0.30 deg/tick at 1.98 blocks/tick
     * — a 380-block radius — and the fix is typically 300-400 blocks away and often abeam or behind,
     * because the cruise leg ends over the field and the fix is on the far side of it. So the
     * aircraft could not turn tightly enough to reach the fix; the distance to the fix therefore
     * never fell; the schedule therefore never braked it; and it flew a circle around the fix at a
     * steady 24 degrees of bank with a heading error pinned between 73 and 101 degrees. Measured on
     * the rig, this ran for 24000 ticks and would have run for ever: no landing, no go-around, no
     * outcome line at all — the flight simply never ended. Speed was both the cause and the thing
     * the loop refused to give up.
     *
     * <p><b>The geometry.</b> An arc that leaves the current heading and passes through a point at
     * distance {@code d}, {@code theta} off the nose, has radius {@code d / (2 sin theta)} — the
     * inscribed-angle relation between a chord and its tangent. The aircraft's own radius is
     * {@code v / omega}, so it can make the point only while
     * {@code v <= omega * d / (2 sin theta)}. Past 90 degrees the turn is more than a half circle and
     * {@code sin} starts falling again, which would read as an easier turn, so the angle is clamped
     * there — beyond it the binding constraint is simply {@code r <= d / 2}.
     *
     * <p><b>Why the margin.</b> {@code omega} here is the nominal clamp from
     * {@code PlaneEntity#tickYaw}, and that is optimistic at speed. Measured peak sustained turn
     * rates against nominal: cargo 0.507 against 0.5 at 0.50 blocks/tick, but 0.296 against 0.5 at
     * 1.98 — the velocity vector follows the nose more slowly the faster the aircraft is going, so
     * the realised radius at cruise is nearly double the model's. {@link
     * AutopilotConfig#TURN_RATE_MARGIN} is that shortfall. It matters only while the aircraft is
     * fast, which is exactly where the latch lives; as the cap brings the speed down the model
     * becomes accurate again and the cap stops binding, so the loop is self-correcting rather than
     * permanently conservative.
     *
     * <p>Floored at {@link AutopilotConfig#APPROACH_SPEED}, because the descent has no business
     * commanding anything slower than the speed it is trying to arrive at, and an aircraft nearly on
     * top of a fix it is pointing away from would otherwise be told to fly at a stall.
     */
    private static double turnLimitedSpeed(PlaneEntity plane, double headingToPoint, double distance) {
        double turn = Math.abs(AutopilotMath.angleDelta(plane.getYRot(), headingToPoint));
        if (turn < 1.0) {
            return Double.MAX_VALUE;
        }
        double yawRate = Math.toRadians(AutopilotConfig.MAX_YAW_RATE
            * Math.max(plane.autopilotRotationSpeedMultiplier(), 0.05)
            * AutopilotConfig.TURN_RATE_MARGIN);
        double turnable = yawRate * distance / (2.0 * Math.sin(Math.toRadians(Math.min(turn, 90.0))));
        return Math.max(AutopilotConfig.APPROACH_SPEED, turnable);
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
            holdFix = interceptFix();
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
        // Levelled off at the height the plan joins the slope at, then down the slope. The cap used
        // to be the fixed circuit height, which is the same thing whenever the plan is a standard
        // 300-block final and is what stopped an extended one from working: an aircraft joining 700
        // blocks out was ordered down to circuit height immediately and then had to fly the rest of
        // the final level, arriving at the threshold exactly as high as before.
        cmdTargetAltitude = landingEnd.glideSlopeAltitude(
            Math.min(distanceToThreshold, interceptDistance()));
        // Slow enough to land, and no sooner than that. The approach was flown at APPROACH_SPEED end
        // to end, which is 0.5 blocks/tick for as much as 700 blocks of straight line; the schedule
        // is the same measured drag model the cruise brakes with, aimed at approach speed by
        // APPROACH_SETTLED_DISTANCE so the aircraft has stopped manoeuvring before the gates arm.
        //
        // The transit speed is for tracking the centreline, not for capturing it: while there is
        // still a real cut on, the turn radius is what decides whether the aircraft rolls out on the
        // line or overshoots it into a go-around. Same rule as the join, for the same reason.
        cmdSpeed = isFinal ? AutopilotConfig.FINAL_SPEED
            : AutopilotMath.speedSchedule(speedAtFix(cmdHeading, runwayHeading), AutopilotConfig.APPROACH_SPEED,
                (distanceToThreshold - AutopilotConfig.APPROACH_SETTLED_DISTANCE)
                    / AutopilotConfig.DECELERATION_MARGIN);
        cmdBankLimit = isFinal ? 8 : 18;
        cmdTerrainFollow = false;
        cmdMaxDescentAngle = AutopilotConfig.GLIDE_SLOPE_DEGREES * 2.0;
        // As in DESCENT: the approach needs to be able to close the throttle, or it arrives fast,
        // floats down the whole strip and goes around. Measured before this: 0.94 blocks/tick on
        // short final against a commanded 0.40, three go-arounds, no landing.
        cmdMinThrottle = 0;

        double agl = position.y - groundBelow(plane);
        // Height above the runway, which is not the same question as height above the ground. Every
        // check below is written about the runway — "the gates apply on short final", "the corridor
        // raycast is skipped close in, where the runway itself is the hit" — and on a flat field the
        // two numbers are identical, which is why the difference went unnoticed. They are nothing
        // like each other on an approach that crosses a valley or a coastline, and there the ground
        // reading is the wrong one: it starts the gates late over low ground and early over high.
        //
        // The datum is the aim point rather than the threshold, which is the same choice as the
        // glide slope's endpoint and has to be the same choice: the flare is triggered on this
        // number and the glide slope decides where the aircraft will be when it reaches it. Aiming
        // at one point and flaring relative to another is exactly the disagreement that produced
        // "landed 1 block down the runway" on a 183-block strip. Identical to the threshold on a
        // level runway, which is every runway the rig flies.
        double heightAboveRunway = position.y - landingEnd.touchdownElevation();
        double distanceToAim = distanceToThreshold + landingEnd.aimOffset();

        // Flew past the aim point without getting down: go around. Measured against the aim point
        // and not the threshold now, because the threshold is no longer where the aircraft is trying
        // to arrive — on a 183-block field it aims 37 blocks in, so a threshold-referenced version
        // of this check would send every single approach around 32 blocks before it flared.
        if (distanceToAim < -5) {
            goAround(plane, "crossed the touchdown point still airborne");
            return;
        }

        // Real raycast down the approach corridor, so a hill in the way is caught even when the
        // heightmap profile looks fine. Skipped close in, where the runway itself is the hit.
        if (!gatesDisabled && heightAboveRunway > 15 && ticks % 20 == 0) {
            Vec3 aim = landingEnd.aimPoint();
            if (!TerrainScanner.pathClear(plane.level(), plane, position, new Vec3(aim.x, aim.y + 2, aim.z))) {
                goAround(plane, "terrain in the approach corridor");
                return;
            }
        }

        if (!isFinal) {
            if (distanceToThreshold < AutopilotConfig.FINAL_HANDOVER_DISTANCE) {
                setMode(plane, AutopilotMode.FINAL);
            }
            return;
        }

        if (!gatesDisabled) {
            String failure = gateFailure(plane, lateral, heightAboveRunway);
            if (failure != null) {
                goAround(plane, failure);
                return;
            }
        }

        // The flare is a commitment rather than a manoeuvre: the throttle goes to zero and stays
        // there, so whatever is under the aircraft when it fires is what the aircraft is going to
        // come down on. That makes "am I four blocks up" the wrong question on its own.
        //
        // AGL is measured off MOTION_BLOCKING, whose predicate counts fluids, so a sea reports its
        // own waterline as ground: four blocks over an ocean and four blocks over a runway are
        // literally the same number, and the survey cannot tell them apart either. An aircraft that
        // closed the throttle over water stopped flying, PlaneEntity#tickOnGround took over the
        // moment it touched the surface (isOnWater puts it in ground mode, with the same 48x rolling
        // drag), it sank, and the roll-out then announced a landing. Requiring a landable surface is
        // the whole fix: over water the approach simply keeps flying the glide slope, which is
        // referenced to the threshold and therefore never goes below it, and the flare happens over
        // the runway where it was always meant to.
        //
        // getOnGround() needs the same qualification for the same reason — it is true while the
        // aircraft is floating in water, because tickOnGround sets the coyote timer from isOnWater.
        //
        // The height above the runway is required as well as the height above the ground, and that
        // is the second half of the same mistake: ground rising under the approach — a beach, a
        // ridge, a forest — brings AGL down to four blocks while the aircraft is still nine blocks
        // above the runway and fifty blocks short of it, and the flare fired there too. Both numbers
        // agree over the runway itself, which is the only place the flare is supposed to happen.
        //
        // gatesDisabled is the existing "out of patience, put it down as it is" state, and it has to
        // override the surface test too. A field whose approach really does end in water cannot be
        // landed on however many times it is tried, and without this the aircraft goes around, holds,
        // tries the other end, goes around again, for ever — holding the runway reservation the whole
        // time and never producing an outcome anyone can read. Committing here ends the flight, and
        // the roll-out now says what actually happened to it.
        boolean touchedDown = plane.getOnGround() && !plane.isOnWater();
        boolean readyToFlare = agl <= AutopilotConfig.FLARE_HEIGHT
            && heightAboveRunway <= AutopilotConfig.FLARE_HEIGHT
            && (landableBelow(plane) || gatesDisabled);
        if (touchedDown || readyToFlare) {
            setMode(plane, AutopilotMode.FLARE);
        }
    }

    /**
     * The "is this a landing or a crash" test, applied only once the aircraft is low enough for it
     * to mean anything. Any failure sends it around rather than letting it touch down skewed —
     * which {@code PlaneEntity#causeFallDamage} would turn into an explosion anyway.
     *
     * @param heightAboveRunway height above the threshold, not above the ground under the aircraft:
     *                          the gates are about how the runway is being arrived at, and on an
     *                          approach over a valley or a sea the two differ by the depth of it
     */
    private @Nullable String gateFailure(PlaneEntity plane, double lateral, double heightAboveRunway) {
        if (heightAboveRunway > AutopilotConfig.GATE_CHECK_HEIGHT) {
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
        // Height above the runway, for the same reason the flare is entered on it: over ground that
        // falls away past the threshold, AGL climbs on its own and the aircraft would abandon a
        // perfectly good flare for a balloon it never made. Same datum the flare was entered on —
        // the aim point — so the entry and the abort cannot disagree about how high the aircraft is.
        double heightAboveRunway = plane.position().y - landingEnd.touchdownElevation();
        if (heightAboveRunway > AutopilotConfig.FLARE_HEIGHT * 4 && modeTicks > 20) {
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
            String where = Math.round(plane.getX()) + ", " + Math.round(plane.getY())
                + ", " + Math.round(plane.getZ());
            String problem = landingProblem(plane);
            if (problem == null) {
                double along = Math.abs(AutopilotMath.alongTrack(
                    landingEnd.threshold(), landingEnd.landingHeading(), plane.position()));
                long down = Math.round(along);
                // How much of the strip the landing actually consumed, which is the thing a player
                // judges by eye and could not previously read anywhere. "3 blocks down the runway"
                // says nothing about whether that is a tidy arrival on a short field or an aircraft
                // that has parked itself on the very lip of a 183-block one; the percentage says
                // which, and it is what the aim point is tuned against.
                long used = Math.round(100.0 * along / Math.max(landingEnd.length(), 1.0E-3));
                AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " landed at "
                    + landingAirfield.name() + "/" + landingEnd.designator() + ", " + where
                    // "the N-block runway" rather than "a N-block runway": the indefinite article
                    // would need a/an chosen from how the number is pronounced ("an 18-block", "a
                    // 183-block"), which is not a rule worth writing.
                    + " (" + down + (down == 1 ? " block" : " blocks") + " down the "
                    + Math.round(landingEnd.length()) + "-block runway, " + used + "% used).");
                // The landing line is printed either way and is unchanged, because it is the
                // assertion every arrival in this feature is regressed against. What follows it is
                // the new half: leaving the strip. Only a real landing earns it — an aircraft that
                // came to rest in the water or fifty blocks off the centreline is not going to taxi
                // anywhere, and asking it to would turn a clean failure report into a hang.
                if (beginTaxiIn(plane)) {
                    return;
                }
            } else {
                AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " did not land at "
                    + landingAirfield.name() + "/" + landingEnd.designator() + ": came to rest "
                    + problem + ", at " + where + ".");
            }
            // stop() releases the reservation on both paths. A runway held for ever by an aircraft
            // that is on the sea floor is the second thing a false landing report used to hide.
            stop(plane);
        }
    }

    /**
     * Chooses a stand and starts the taxi in, or explains why the aircraft is staying where it is.
     *
     * <p><b>There are three honest outcomes and none of them is a wait.</b> An aircraft that has just
     * landed is standing on the one surface every other aircraft at the field needs, so "hold here
     * until something frees up" is the one answer that must never be given — it is the behaviour this
     * whole phase exists to remove. So either there is a stand it can reach right now and it goes, or
     * it stops where it stopped and says so, which is exactly what every build before this one did.
     *
     * @return true when the taxi has begun and the flight is continuing
     */
    private boolean beginTaxiIn(PlaneEntity plane) {
        if (landingAirfield == null) {
            return false;
        }
        if (landingAirfield.parkingSpots().isEmpty()) {
            // Not a failure, and deliberately not a fallback onto the derived apron either: see
            // Airfield#arrivalStand. Said out loud rather than passed over in silence, because "the
            // aircraft is sitting on the runway" now has two possible causes and they need telling
            // apart.
            AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " stopped on the runway at "
                + landingAirfield.name() + ": no marked parking. Mark a stand with the Runway Survey"
                + " Tool in parking mode, or /autopilot airfields park \""
                + landingAirfield.name() + "\" <x y z>.");
            return false;
        }
        // Every stand resident before any of them is judged. "Is that stand free" is answered partly
        // by a search for entities standing on it, and an unloaded chunk answers that question
        // "empty" whatever is parked there — see AutopilotSpawner#loadAirfield for the measured
        // failure. The aircraft is about to drive onto this ground anyway, and its own rolling ticket
        // only reaches it once it is nearly there, which is far too late to have decided with.
        if (plane.level() instanceof ServerLevel serverLevel) {
            AutopilotSpawner.loadAirfield(serverLevel, landingAirfield);
        }
        Airfield.TaxiIn taxi = Airfield.arrivalStand(plane.level(), landingAirfield,
            plane.position(), plane);
        if (taxi == null) {
            AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " stopped on the runway at "
                + landingAirfield.name() + ": no free stand it can reach from here.");
            return false;
        }
        standTarget = taxi.stand();
        taxiInRoute = new ArrayList<>(taxi.route());
        clearOfRunway = false;
        taxiInStalledTicks = 0;
        Vec3 stand = taxi.stand().position();
        AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " vacating "
            + landingAirfield.name() + "/" + landingEnd.designator() + ", taxiing to the stand at "
            + Math.round(stand.x) + ", " + Math.round(stand.y) + ", " + Math.round(stand.z)
            + " via " + taxiInRoute.size() + (taxiInRoute.size() == 1 ? " leg." : " legs."));
        setMode(plane, AutopilotMode.TAXI_IN);
        return true;
    }

    /**
     * Ground manoeuvring from where the aircraft stopped to the stand it is going to park on.
     *
     * <p>The mirror image of {@link #tickTaxi} and flown with the same controls for the same reasons
     * — throttle capped so it creeps, nosewheel steering, and the elevator held strictly neutral
     * because {@code tickOnGround} reads a negative pitch input as reverse thrust and a parked plane
     * rests at a nose-up attitude. There is no taxiway network here, which is why every line it is
     * about to drive down was checked for level ground before it set off.
     *
     * <p><b>Two legs, and the first one is the point of the whole phase.</b> The aircraft turns off
     * the side of the strip before it heads for the stand — see {@link Airfield#vacatePoint} for the
     * measurement that made the detour worth its 16 blocks. The runway is given back part way along
     * the first leg, on a rectangle test against the survey rather than on a distance from anything:
     * see {@link Airfield#isOnStrip(Vec3, double)} for why no distance answers that question.
     */
    private void tickTaxiIn(PlaneEntity plane) {
        if (standTarget == null || landingAirfield == null) {
            stop(plane);
            return;
        }
        cmdGroundSteer = true;
        cmdBankLimit = 0;
        cmdTerrainFollow = false;
        cmdSpeed = AutopilotConfig.TAXI_SPEED;
        cmdMinThrottle = 0;
        cmdMaxThrottle = AutopilotConfig.TAXI_MAX_THROTTLE;
        cmdNeutralPitch = true;

        Vec3 stand = standTarget.position();
        double distance = AutopilotMath.horizontalDistance(plane.position(), stand);

        if (!clearOfRunway
            && !landingAirfield.isOnStrip(plane.position(), AutopilotConfig.RUNWAY_CLEAR_MARGIN)) {
            clearOfRunway = true;
            RunwayOccupancy.release(plane.level(), landingAirfield.name(), plane);
            AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " is clear of "
                + landingAirfield.name() + "/" + landingEnd.designator() + " after " + modeTicks
                + " ticks, " + Math.round(distance) + " blocks still to taxi.");
        }

        // Sequence the legs. The last one is the stand itself and is never dropped here — reaching it
        // is what ends the taxi, below — so this only ever advances past the turn-off and the apron
        // run.
        while (taxiInRoute.size() > 1
            && AutopilotMath.horizontalDistance(plane.position(), taxiInRoute.get(0))
                <= AutopilotConfig.TAXI_IN_ARRIVED_RADIUS) {
            taxiInRoute.remove(0);
        }
        cmdHeading = AutopilotMath.headingTo(plane.position(),
            taxiInRoute.isEmpty() ? stand : taxiInRoute.get(0));

        if (distance <= AutopilotConfig.TAXI_IN_ARRIVED_RADIUS) {
            // On the stand. Stop chasing the square — the throttle goes to zero and the aircraft
            // rolls the last fraction of a block off its own momentum, exactly as the roll-out does.
            cmdSpeed = 0;
            cmdMaxThrottle = 0;
            plane.setThrottle(0);
            if (plane.getDeltaMovement().length() < AutopilotConfig.ROLLOUT_STOP_SPEED) {
                finishTaxiIn(plane, true, distance);
            }
            return;
        }

        // Stuck, or taking implausibly long. Both end the flight where it stands rather than leaving
        // an aircraft grinding against something for the rest of the session with a status line that
        // reads exactly like a healthy taxi.
        if (plane.getDeltaMovement().horizontalDistance() < AutopilotConfig.TAXI_IN_STALLED_SPEED) {
            taxiInStalledTicks++;
        } else {
            taxiInStalledTicks = 0;
        }
        if (taxiInStalledTicks > AutopilotConfig.TAXI_IN_STALLED_TICKS
            || modeTicks > AutopilotConfig.TAXI_IN_TIMEOUT) {
            // Stopped within a stand's own clearance counts as parked on it, not as stuck short of
            // it. PARKING_SPOT_CLEARANCE is what "occupying this stand" means everywhere else — it
            // is the box standFree searches and the spacing two marked spots must keep — so an
            // aircraft inside it is on the stand as far as anything else is concerned, and calling
            // that a failure would leave the square looking free while an aircraft sat on it.
            //
            // It is reachable because the turn-in is the shortest leg of the route and the nosewheel
            // is the slowest control: 90 degrees of ground steering takes 30 ticks and 6 blocks at
            // TAXI_SPEED, so on a 4-block final leg the aircraft swings past and hunts. Measured on
            // the rig, exactly once in a dozen arrivals: "stopped short of its stand, 3 blocks to
            // go", 3.8 blocks from the centre of a stand it had plainly reached.
            finishTaxiIn(plane, distance <= AutopilotConfig.PARKING_SPOT_CLEARANCE, distance);
        }
    }

    /** Ends a taxi in, on the stand or short of it, and says which. */
    private void finishTaxiIn(PlaneEntity plane, boolean onStand, double distance) {
        String where = Math.round(plane.getX()) + ", " + Math.round(plane.getY())
            + ", " + Math.round(plane.getZ());
        if (onStand) {
            // Remembered from here rather than from the start of the taxi, and the two halves are
            // deliberately different mechanisms: claimsStand covers an aircraft on its way and is
            // derived from the live set, this covers one that has arrived and outlives both the
            // flight director and the chunk. See StandOccupancy.
            if (standTarget.marked() != null) {
                StandOccupancy.take(plane.level(), landingAirfield.name(), standTarget.marked(), plane);
            }
            AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " parked at "
                + landingAirfield.name() + ", " + where + " (stand "
                + (standTarget.marked() == null ? "?" : standTarget.marked().toShortString())
                + ", " + modeTicks + " ticks from the runway).");
        } else {
            // Deliberately not "landed": the landing line has already been printed and was true. This
            // one is about the taxi, and an aircraft that stops short of its stand is still off the
            // runway, which is most of what the phase was for.
            AutopilotFeedback.report(owner, "Plane #" + plane.getId() + " stopped short of its stand at "
                + landingAirfield.name() + ", " + where + " (" + Math.round(distance)
                + " blocks to go, " + (clearOfRunway ? "clear of the runway" : "STILL ON THE RUNWAY")
                + ").");
        }
        stop(plane);
    }

    /**
     * Why the aircraft has <em>not</em> landed on the runway it was cleared for, or null when it
     * has. Three questions, and it has to answer all three: is it between the thresholds along the
     * strip, is it inside the strip across it, and is it standing at the runway's own elevation.
     *
     * <p>The roll-out used to declare a landing on nothing but {@code stopped && getOnGround()},
     * which is equally true of an aircraft resting on a sea floor a hundred blocks short of the
     * field — {@code getOnGround()} is true in water, and the number it printed was
     * {@code |alongTrack|}, which stays small and plausible whether the aircraft is on the strip,
     * short of it, or far out to one side. A report that says "landed" when the aircraft has drowned
     * is worse than the accident it hides: it is the line every other report in this feature is
     * trusted on the strength of.
     */
    private @Nullable String landingProblem(PlaneEntity plane) {
        if (plane.isOnWater()) {
            return "in the water";
        }
        Vec3 position = plane.position();
        double heading = landingEnd.landingHeading();
        double along = AutopilotMath.alongTrack(landingEnd.threshold(), heading, position);
        double length = landingEnd.length();
        if (along < -AutopilotConfig.LANDING_POSITION_TOLERANCE) {
            return String.format("%.0f blocks short of the threshold", -along);
        }
        if (along > length + AutopilotConfig.LANDING_POSITION_TOLERANCE) {
            return String.format("%.0f blocks past the far end", along - length);
        }
        double lateral = AutopilotMath.lateralOffset(landingEnd.threshold(), heading, position);
        double halfWidth = Math.max(landingAirfield.width() / 2.0, AutopilotConfig.LANDING_POSITION_TOLERANCE);
        if (Math.abs(lateral) > halfWidth) {
            return String.format("%.0f blocks off the centreline", Math.abs(lateral));
        }
        // Against the runway surface at this point along it, not against either threshold: a
        // surveyed strip is allowed to slope, and a 3-block tolerance against the low end would
        // reject a perfectly good landing at the high one.
        double runwayHere = Mth.lerp(Mth.clamp(along / Math.max(length, 1.0E-3), 0.0, 1.0),
            landingEnd.threshold().y, landingEnd.farEnd().y);
        double drop = position.y - runwayHere;
        if (Math.abs(drop) > AutopilotConfig.LANDING_ELEVATION_TOLERANCE) {
            return String.format("%.0f blocks %s the runway surface", Math.abs(drop),
                drop < 0 ? "below" : "above");
        }
        return null;
    }

    /**
     * Orbit the fix, either because someone else has the runway or because the aircraft is still too
     * high for any final. Leaves the moment that reason stops being true — an orbit is a way of
     * spending height or time, never a stage of the arrival.
     *
     * <p>Aircraft are separated in the stack by entity id: the level and the starting angle both
     * come from it. Planes are hard-colliding entities, so several of them orbiting one fix at one
     * altitude eventually block each other's {@code move()}, which {@code PlaneCollisions} correctly
     * reads as an impact — seen in the field as two aircraft destroyed three blocks apart, at the
     * same altitude, in the same tick, both in this mode. This is separation, not sequencing: there
     * is still no queue, and whichever aircraft next polls a free runway takes it.
     */
    private void tickHold(PlaneEntity plane) {
        Vec3 fix = holdFix != null ? holdFix : plane.position();
        if (modeTicks <= 1) {
            // Start each aircraft at its own point on the circle as well as at its own level, so two
            // that happen to share a level are on opposite sides of it rather than in formation.
            holdAngle = plane.getId() * 137.0;
        }
        holdAngle += AutopilotConfig.HOLD_TURN_RATE;
        Vec3 orbitPoint = AutopilotMath.pointAlong(fix, holdAngle, AutopilotConfig.HOLD_RADIUS);
        cmdHeading = AutopilotMath.headingTo(plane.position(), orbitPoint);
        int level = Math.floorMod(plane.getId(), AutopilotConfig.HOLD_LEVELS);
        cmdTargetAltitude = fix.y + level * AutopilotConfig.HOLD_LEVEL_SPACING;
        cmdSpeed = AutopilotConfig.APPROACH_SPEED;
        cmdBankLimit = AutopilotConfig.MAX_BANK;

        if (ticks % 20 != 0 || landingAirfield == null || landingEnd == null) {
            return;
        }
        if (!RunwayOccupancy.isFree(plane.level(), landingAirfield.name(), plane)) {
            return;
        }
        // Free runway. Rejoin as soon as some final — extended if need be — can absorb whatever
        // height is left, which for an aircraft that only ever held for traffic is immediately.
        ArrivalPlan planned = ArrivalPlan.decide(landingEnd, plane.position(), true,
            plane.autopilotRotationSpeedMultiplier());
        if (!planned.entry().circling()) {
            announceArrival(plane, planned);
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
        // The plan that produced this approach did not work, so it is not carried into the next one.
        arrival = null;
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

    /** Where this arrival joins the glide slope, falling back to the standard final geometry. */
    private double interceptDistance() {
        return arrival == null ? AutopilotConfig.FINAL_INTERCEPT_DISTANCE : arrival.interceptDistance();
    }

    private Vec3 interceptFix() {
        return arrival != null ? arrival.interceptFix()
            : landingEnd.approachPoint(AutopilotConfig.FINAL_INTERCEPT_DISTANCE, AutopilotConfig.PATTERN_HEIGHT);
    }

    /**
     * The one-phrase account of what this aircraft has decided and why, for {@code /autopilot status}
     * and the tower board. A path planner whose reasoning is invisible is one nobody can debug.
     */
    public Component planComponent() {
        boolean enRoute = mode == AutopilotMode.CRUISE || mode == AutopilotMode.CLIMB
            || mode == AutopilotMode.STRIKE;
        return !enRoute && arrival != null ? arrival.describe() : router.describe();
    }

    /** {@link #planComponent()} as plain text, for {@code /autopilot status}. */
    public String planPhrase() {
        return planComponent().getString();
    }

    /**
     * Height of the surface directly below, water and treetops included — the thing the aircraft
     * would touch if it went straight down. An unloaded column reports the aircraft's own altitude,
     * i.e. an AGL of zero, which is why nothing that commits the aircraft to anything may key off
     * this number alone; see {@link #landableBelow}.
     */
    private double groundBelow(PlaneEntity plane) {
        Vec3 position = plane.position();
        int surface = TerrainScanner.surfaceHeight(plane.level(), position.x, position.z);
        return surface == TerrainScanner.UNKNOWN_HEIGHT ? position.y : surface;
    }

    /** Whether the surface directly below is something the aircraft could put its wheels on. */
    private boolean landableBelow(PlaneEntity plane) {
        Vec3 position = plane.position();
        return TerrainScanner.isLandable(plane.level(), position.x, position.z);
    }

    /** Height of the ground below with any water or lava standing on it discounted. Telemetry only. */
    private double landableGroundBelow(PlaneEntity plane) {
        Vec3 position = plane.position();
        int surface = TerrainScanner.landableSurfaceHeight(plane.level(), position.x, position.z);
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
        // The aircraft's own position is part of the choice now: two ends with equally clean funnels
        // are not equal when one of them is behind the aircraft. Obstacles still dominate — see
        // Airfield#bestEnd — so this cannot trade a clear approach for a shorter one.
        landingEnd = airfield.bestEnd(level, plane.position());
        plan.setAirfieldName(airfield.name());
        return true;
    }

    private void setMode(PlaneEntity plane, AutopilotMode next) {
        if (mode == next) {
            return;
        }
        mode = next;
        modeTicks = 0;
        // Both reservations are released the moment the aircraft stops being entitled to them, and
        // the entitlement is asked of holdsRunway rather than re-derived from the mode here — the
        // same method RunwayOccupancy validates against, so the two cannot disagree. That is also
        // why entering TAXI_IN does not drop the strip: holdsRunway keeps it until the rectangle
        // test in tickTaxiIn says the aircraft is actually off the runway, which is neither the mode
        // change nor the end of the taxi.
        //
        // The departure's mirror image is unchanged: the strip is given back on the CLIMB entry at
        // TAKEOFF_CLEAR_HEIGHT. Releasing eagerly rather than waiting for the flight to end is what
        // lets the next sortie out of the same field while this one is still on its way.
        if (landingAirfield != null && !holdsRunway(landingAirfield.name())) {
            RunwayOccupancy.release(plane.level(), landingAirfield.name(), plane);
        }
        if (departureEnd != null && !holdsRunway(departureEnd.airfield().name())) {
            RunwayOccupancy.release(plane.level(), departureEnd.airfield().name(), plane);
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
            // Empty for the starter plane, so a status line with no mixed traffic in it reads
            // exactly as it always did; "large"/"cargo" otherwise, because three airframes that all
            // print as "#12 approach" cannot be told apart and they fly quite differently.
            .append(AircraftType.tag(plane))
            .append(' ').append(mode.getName())
            .append(String.format(" pos=%.0f,%.0f,%.0f", position.x, position.y, position.z))
            .append(String.format(" agl=%.0f", position.y - groundBelow(plane)))
            // Only when the two differ, which is exactly when the aircraft is over something it
            // cannot land on. An approach that ditched used to be indistinguishable in this readout
            // from one over a field: agl counts a waterline as ground.
            .append(landableBelow(plane) ? ""
                : String.format(" solid=%.0f", position.y - landableGroundBelow(plane)))
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
        if (departureAirfieldName() != null) {
            builder.append(" dep=").append(departureAirfieldName())
                .append('/').append(departureEnd.designator());
        }
        // A wait nobody can see is indistinguishable from a hang, so it says which of the two gates
        // it is sitting behind and, for the clock, how much of it is left.
        int held = departureHoldTicks();
        if (held > 0) {
            builder.append(" wait=clock ").append(TowerWatch.clock(held));
        } else if (held == 0) {
            builder.append(" wait=runway");
        }
        if (landingEnd != null) {
            builder.append(" rwy=").append(landingAirfield == null ? "?" : landingAirfield.name())
                .append('/').append(landingEnd.designator());
        }
        // A taxiing aircraft that reads like a stopped one is undebuggable, and on the ground almost
        // every other field on this line is the same for both — position barely moves, agl is 0, the
        // throttle dithers around 1. So the taxi says where it is going, how far is left and, in one
        // word, whether the runway behind it is free yet.
        if (mode == AutopilotMode.TAXI_IN && standTarget != null) {
            Vec3 stand = standTarget.position();
            builder.append(String.format(" stand=%.0f,%.0f,%.0f to_go=%.0f rwy_%s",
                stand.x, stand.y, stand.z,
                AutopilotMath.horizontalDistance(position, stand),
                clearOfRunway ? "clear" : "held"));
        }
        if (goArounds > 0) {
            builder.append(" go-arounds=").append(goArounds);
        }
        if (plan != null && plan.kind() == FlightPlan.Kind.ROUTE) {
            builder.append(" legs=").append(plan.legsFlown()).append('/').append(plan.maxLegs());
        }
        // Last, and always present: what the path planner decided and why. Without it a status line
        // shows an aircraft turning or circling with no way to tell a plan from a malfunction.
        builder.append(" plan[").append(planPhrase()).append(']');
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
        // Once the aircraft is taxiing in, the threshold it landed on is behind it and the stand is
        // what it is steering at; showing the runway here would put a growing dist= on a status line
        // that is meant to say how nearly the flight is over.
        if (mode == AutopilotMode.TAXI_IN && standTarget != null) {
            return standTarget.position();
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
        // A saved taxi in is not resumed at all, and unlike TAXI and PARKED it is not promoted to
        // TAKEOFF either — that would send an aircraft that has already completed its flight back
        // down the runway. Everything the flight was for has happened: it landed, the landing was
        // reported and the runway was given back. Losing the last leg of a taxi leaves the aircraft
        // standing on level ground beside its runway, which is a worse parking job than it asked for
        // and a perfectly good place to be. The stand and the route are flight-director state and
        // were never written to disk.
        if (AutopilotMode.byName(child.getStringOr("mode", AutopilotMode.CRUISE.getName()))
            == AutopilotMode.TAXI_IN) {
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
        // TAXI without a departure runway would sit on the threshold forever. PARKED goes the same
        // way and for the same reason — load() does not re-resolve the departure end, so a restored
        // PARKED would have no runway to ask for and no way to leave the spot. The cost is that a
        // restart during a departure delay departs the aircraft immediately instead of finishing the
        // clock; see AUTOPILOT.md, "Limitations".
        if (autopilot.mode == AutopilotMode.TAXI || autopilot.mode == AutopilotMode.PARKED) {
            autopilot.mode = AutopilotMode.TAKEOFF;
        }
        plane.setAutopilot(autopilot);
        AutopilotRegistry.register(plane);
    }
}
