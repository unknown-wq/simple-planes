package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.upgrades.booster.BoosterUpgrade;

import java.util.List;

/**
 * Creates and tasks autopilot aircraft.
 *
 * <p>Nothing here needs a player. A player is only ever an optional owner for status messages, so
 * every entry point works from the server console, a command block or a datapack function.
 */
public final class AutopilotSpawner {

    /**
     * Run-in direction used when nothing better is known: the aircraft is placed due south of the
     * target (Minecraft yaw 0 is +Z) and flies north into it. Fixed rather than random so a headless
     * test produces the same flight every time.
     */
    public static final double DEFAULT_STRIKE_BEARING = 0.0;

    /**
     * Speed a strike aircraft is launched with, in blocks/tick. Roughly the terminal speed of a
     * boosted plane, so the run starts at attack speed instead of building up to it.
     */
    public static final double STRIKE_LAUNCH_SPEED = 2.0;
    /** Route launches leave at cruise speed rather than attack speed. */
    public static final double CRUISE_LAUNCH_SPEED = 0.7;

    /**
     * Raised speed ceiling for a strike aircraft.
     *
     * <p>This is what actually sets the terminal speed, and not through any limiter:
     * {@code PlaneEntity#tickMotion} fades the thrust out as the plane approaches
     * {@code maxPushSpeed = getMaxSpeed() * 10}, by the factor
     * {@code 1 - speed / (maxPushSpeed * (push + 0.05))}. At throttle 10 that denominator is
     * {@code maxSpeed * 1.125}, so thrust reaches zero at 1.125x this value and balances the drag
     * curve ({@code 0.001 v^2 + 0.0005 v + 0.001}) a little below it — about 2.0 blocks/tick at the
     * old ceiling of 2.0, about 2.8 at 3.0. The hard limiter in the same method sits at 3.0, so this
     * is as fast as the airframe goes.
     */
    public static final float STRIKE_MAX_SPEED = 3.0f;

    private AutopilotSpawner() {}

    /**
     * Spawns an aircraft {@code distance} blocks from {@code target} on the given bearing and sends
     * it at the target at full throttle.
     *
     * @param approachBearing Minecraft yaw from the target towards the spawn point, i.e. the side
     *                        the attack run comes in from
     * @param owner           optional, only used for progress messages
     * @return the aircraft, or null if it could not be created
     */
    public static @Nullable PlaneEntity launchStrike(Level level, BlockPos target, int distance,
                                                     double approachBearing, @Nullable Player owner,
                                                     Blast blast) {
        Vec3 targetVec = new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        Vec3 spawn = AutopilotMath.pointAlong(targetVec, approachBearing, distance);

        double terrain = TerrainScanner.surfaceHeight(level, spawn.x, spawn.z);
        if (terrain == TerrainScanner.UNKNOWN_HEIGHT) {
            terrain = targetVec.y;
        }
        // Launched straight onto the run-in profile: at the run-in height above whichever is higher,
        // the ground under the launch point or the target itself. Climbing to it afterwards would
        // cost the speed the run is supposed to start with.
        double altitude = Math.min(
            Math.max(terrain, targetVec.y) + AutopilotConfig.STRIKE_RUN_IN_AGL,
            level.getMaxY() - 8);

        double heading = AutopilotMath.headingTo(spawn, targetVec);
        PlaneEntity plane = create(level, spawn.x, altitude, spawn.z, heading);
        if (plane == null) {
            return null;
        }

        // A strike aircraft is launched, not taxied. Fit a booster (which raises the throttle
        // ceiling from 5 to 10), open the throttle fully and give it its cruise speed at t=0,
        // pointed at the target - otherwise it spends the first seconds of the run accelerating
        // from a standstill and sagging towards the ground while it does.
        fitBooster(plane, STRIKE_MAX_SPEED);
        plane.setThrottle(BoosterUpgrade.MAX_THROTTLE);
        Vec3 run = targetVec.subtract(spawn.x, altitude, spawn.z).normalize();
        plane.setDeltaMovement(run.scale(STRIKE_LAUNCH_SPEED));

        addToWorld(level, plane);

        PlaneAutopilot autopilot = new PlaneAutopilot();
        plane.setAutopilot(autopilot);
        // Powered by the autopilot, and never persisted: a strike aircraft is a one-shot weapon.
        autopilot.start(plane, FlightPlan.strike(target, blast), true, false, owner);
        return plane;
    }

    /**
     * Fits a booster and raises the speed ceiling.
     *
     * <p>Every aircraft the autopilot creates gets one, not just a strike. The booster raises the
     * throttle ceiling from 5 to 10 and {@code setMaxSpeed} moves the point the thrust fades out at
     * — neither is a limiter, so this only gives the airframe the capability to fly fast. What it
     * actually flies is whatever the flight director commands, which for a route is the plan's
     * cruise speed: the {@code speed} argument on the command, or {@link AutopilotConfig#CRUISE_SPEED}
     * when none was given.
     */
    private static void fitBooster(PlaneEntity plane, float maxSpeed) {
        plane.addUpgradeUsingWrench(SimplePlanesItems.BOOSTER.get().getDefaultInstance(),
            new BoosterUpgrade(plane));
        plane.setMaxSpeed(maxSpeed);
    }

    /**
     * Launch report for a strike. Reports where the aircraft actually appeared, not just how far
     * away it was asked to appear: without the real position there is no way to tell a strike that
     * failed to spawn from one that spawned and fell out of the sky.
     */
    public static String describeLaunch(PlaneEntity plane, BlockPos target, int distance, double bearing) {
        double terrain = TerrainScanner.surfaceHeight(plane.level(), plane.getX(), plane.getZ());
        String agl = terrain == TerrainScanner.UNKNOWN_HEIGHT
            ? "?"
            : String.valueOf(Math.round(plane.getY() - terrain));
        return "Strike #" + plane.getId() + " spawned at "
            + Math.round(plane.getX()) + ", " + Math.round(plane.getY()) + ", " + Math.round(plane.getZ())
            + " (" + agl + " above ground), inbound to " + target.toShortString()
            + " - " + distance + " blocks, bearing " + Math.round(bearing) + ".";
    }

    /**
     * Bearing an attack run should come in on, given where the order was issued from. Using the
     * issuer's position makes the aircraft run in past them, which is the nice behaviour when a
     * player triggers it; when the order comes from the console the origin is usually the world
     * spawn, and if that is on top of the target we fall back to a fixed bearing.
     */
    public static double approachBearingFrom(Vec3 origin, BlockPos target) {
        Vec3 targetVec = new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (AutopilotMath.horizontalDistance(origin, targetVec) < 2.0) {
            return DEFAULT_STRIKE_BEARING;
        }
        return AutopilotMath.headingTo(targetVec, origin);
    }

    /**
     * Spawns an aircraft at the first waypoint and sets it flying the route.
     *
     * @param owner optional, only used for progress messages
     */
    public static @Nullable PlaneEntity launchRoute(Level level, List<BlockPos> waypoints,
                                                    int cruiseAltitude, int legs,
                                                    @Nullable String airfieldName, @Nullable Player owner,
                                                    double cruiseSpeed, Blast blast) {
        return launchRoute(level, waypoints, cruiseAltitude, legs, airfieldName, owner, cruiseSpeed,
            blast, AircraftType.PLANE);
    }

    public static @Nullable PlaneEntity launchRoute(Level level, List<BlockPos> waypoints,
                                                    int cruiseAltitude, int legs,
                                                    @Nullable String airfieldName, @Nullable Player owner,
                                                    double cruiseSpeed, Blast blast, AircraftType type) {
        if (waypoints.isEmpty()) {
            return null;
        }
        BlockPos first = waypoints.get(0);
        Vec3 start = new Vec3(first.getX() + 0.5, cruiseAltitude, first.getZ() + 0.5);
        Vec3 towards = waypoints.size() > 1
            ? new Vec3(waypoints.get(1).getX() + 0.5, cruiseAltitude, waypoints.get(1).getZ() + 0.5)
            : start.add(0, 0, 1);

        PlaneEntity plane = create(level, start.x, start.y, start.z,
            AutopilotMath.headingTo(start, towards), type);
        if (plane == null) {
            return null;
        }
        // Launch at flying speed, like a strike. A route aircraft spawned mid-air with zero
        // airspeed has to dive to pick up flying speed, and from a low cruise altitude that dive
        // ends in the ground. Launched at the commanded cruise speed when that is higher, so a fast
        // route does not spend its first hundred blocks accelerating.
        fitBooster(plane, AutopilotConfig.ROUTE_MAX_SPEED);
        Vec3 run = towards.subtract(start);
        if (run.lengthSqr() > 1.0E-6) {
            plane.setDeltaMovement(run.normalize().scale(Math.max(CRUISE_LAUNCH_SPEED, cruiseSpeed)));
        }
        // Engine already running. Launched at 0.70 with the throttle shut, the aircraft spent its
        // first seconds slowing down, not speeding up: throttle 0 sets brakesMul = 5 in
        // PlaneEntity#tickMotion, which is a full airbrake, and the autopilot's throttle loop only
        // adds one notch every five ticks — 25 ticks to reach full power. Measured in the field at
        // 0.64 blocks/tick and falling against a commanded 0.80, with the lever still on 2.
        plane.setThrottle(BoosterUpgrade.MAX_THROTTLE);
        addToWorld(level, plane);

        PlaneAutopilot autopilot = new PlaneAutopilot();
        plane.setAutopilot(autopilot);
        // Powered by the autopilot so a courier aircraft does not need an engine upgrade, and
        // persisted so the route resumes after a restart.
        autopilot.start(plane, FlightPlan.route(waypoints, cruiseAltitude, legs, airfieldName, cruiseSpeed, blast),
            true, true, owner);
        return plane;
    }

    /**
     * Flies a complete sortie: park at {@code departure}, taxi out, take off, cruise, and land at
     * {@code destination}.
     *
     * <p>The aircraft is spawned <em>stationary, on the ground, at a parking spot</em> and is given
     * no velocity at all. The initial velocity a strike gets is an air-launch and stays exclusive to
     * strikes: a runway departure has a runway, and everything from the parking spot to the
     * threshold is done on the throttle and the nosewheel.
     *
     * @param departureDelayTicks how long the aircraft sits on its spot before asking for the runway
     * @return the aircraft, or null if it could not be created
     */
    public static @Nullable PlaneEntity launchSortie(ServerLevel level, Airfield departure, Airfield destination,
                                                     @Nullable Player owner, double cruiseSpeed, Blast blast,
                                                     int departureDelayTicks) {
        return launchSortie(level, departure, destination, owner, cruiseSpeed, blast, departureDelayTicks,
            AircraftType.PLANE);
    }

    public static @Nullable PlaneEntity launchSortie(ServerLevel level, Airfield departure, Airfield destination,
                                                     @Nullable Player owner, double cruiseSpeed, Blast blast,
                                                     int departureDelayTicks, AircraftType type) {
        // The runway is usually nowhere near a player, so its chunks have to exist before anything
        // can be measured on them or spawned into them. Both thresholds, not just the centre: a
        // 183-block runway spans a dozen chunks, and the parking spot sits beyond one of its ends —
        // loading only the middle left the spawn point unloaded, so the apron survey read "unknown
        // terrain" and the aircraft was parked on the centreline by the fallback instead.
        loadAirfield(level, departure);
        loadAirfield(level, destination);

        // Where the sortie is going decides which way it leaves, so the destination goes in here and
        // not only into the flight plan — see DeparturePlan. The parking spot is derived from the
        // answer, so this is the moment it has to be settled.
        RunwayEnd departureEnd = Airfield.departureEnd(level, departure, destination.centre());
        Airfield.ParkingSpot parking = Airfield.parkingPosition(level, departureEnd);
        loadAround(level, parking.position());
        // The heading comes from the parking spot rather than being derived here. Facing the
        // threshold is right for an apron, but wrong when the aircraft had to be parked on the strip
        // itself: from a spot a few blocks down the runway, "face the threshold" points it backwards
        // down its own departure path.
        Vec3 position = parking.position();

        PlaneEntity plane = create(level, position.x, position.y + 1.0, position.z, parking.heading(), type);
        if (plane == null) {
            return null;
        }
        // Explicitly stationary. No synthetic velocity on a runway departure. The booster is fitted
        // all the same — it is the throttle ceiling for the cruise, not a launch aid, and a take-off
        // roll that starts at zero is unaffected by it.
        fitBooster(plane, AutopilotConfig.ROUTE_MAX_SPEED);
        plane.setDeltaMovement(Vec3.ZERO);
        plane.setThrottle(0);
        addToWorld(level, plane);

        int cruiseAltitude = sortieCruiseAltitude(level, departure, destination);
        BlockPos aim = BlockPos.containing(destination.centre());
        PlaneAutopilot autopilot = new PlaneAutopilot();
        plane.setAutopilot(autopilot);
        autopilot.start(plane, FlightPlan.sortie(aim, cruiseAltitude, destination.name(), departure.name(),
            cruiseSpeed, blast, departureDelayTicks), true, true, owner);
        return plane;
    }

    /**
     * Launches an aircraft in the air at {@code from} and sends it one-way to a named airfield.
     *
     * <p>The arrival half of a sortie on its own, so an approach can be iterated on without flying
     * the departure first — and the answer to "start away from the field and fly in", which the
     * out-and-back {@code route} cannot express.
     */
    public static @Nullable PlaneEntity launchInbound(ServerLevel level, Vec3 from, Airfield destination,
                                                      @Nullable Player owner, double cruiseSpeed, Blast blast) {
        return launchInbound(level, from, destination, owner, cruiseSpeed, blast, AircraftType.PLANE);
    }

    public static @Nullable PlaneEntity launchInbound(ServerLevel level, Vec3 from, Airfield destination,
                                                      @Nullable Player owner, double cruiseSpeed, Blast blast,
                                                      AircraftType type) {
        loadAround(level, destination.centre());

        int cruiseAltitude = Math.max((int) from.y, sortieCruiseAltitude(level, destination, destination));
        Vec3 start = new Vec3(from.x, cruiseAltitude, from.z);
        Vec3 towards = destination.centre();
        double heading = AutopilotMath.headingTo(start, towards);

        PlaneEntity plane = create(level, start.x, start.y, start.z, heading, type);
        if (plane == null) {
            return null;
        }
        // Airborne launch: same reasoning as a route, an aircraft dropped in with no airspeed has to
        // dive to find some and does not always have the height to spare.
        fitBooster(plane, AutopilotConfig.ROUTE_MAX_SPEED);
        Vec3 run = towards.subtract(start);
        if (run.lengthSqr() > 1.0E-6) {
            plane.setDeltaMovement(run.normalize().scale(Math.max(CRUISE_LAUNCH_SPEED, cruiseSpeed)));
        }
        // Launch with the engine already running. Spawning at flying speed with the throttle shut
        // means throttle 0, and throttle 0 is an airbrake (PlaneEntity#tickMotion multiplies the
        // whole drag polynomial by 5), so the aircraft spent its first seconds losing the speed it
        // was given while the throttle loop crept up one notch every five ticks.
        plane.setThrottle(BoosterUpgrade.MAX_THROTTLE);
        addToWorld(level, plane);

        BlockPos aim = BlockPos.containing(destination.centre());
        PlaneAutopilot autopilot = new PlaneAutopilot();
        plane.setAutopilot(autopilot);
        autopilot.start(plane, FlightPlan.sortie(aim, cruiseAltitude, destination.name(), null,
            cruiseSpeed, blast, 0), true, true, owner);
        return plane;
    }

    /**
     * Flies a helicopter sortie: stand on {@code departure}, lift off vertically, transit, and let
     * down onto {@code destination}.
     *
     * <p>Spawned <em>on the pad, stationary</em> — the same rule a runway departure follows and for
     * a stronger reason: a helicopter has no take-off roll to accelerate over, so a machine given an
     * initial velocity would simply be a machine that started the flight somewhere other than the
     * pad the survey measured.
     *
     * <p>One block above the pad surface, exactly as a fixed-wing sortie is placed one block above
     * its stand. {@code Helipad#touchdown} is the top face of the centre block, so the machine
     * settles onto it in the first few ticks and the position the landing report compares against is
     * the position it started from.
     */
    public static @Nullable PlaneEntity launchHelicopterSortie(ServerLevel level, Helipad departure,
                                                               Helipad destination, @Nullable Player owner,
                                                               double cruiseSpeed, int departureDelayTicks) {
        HelipadReport.load(level, departure);
        HelipadReport.load(level, destination);

        Vec3 pad = departure.touchdown();
        double heading = AutopilotMath.headingTo(pad, destination.touchdown());
        PlaneEntity plane = create(level, pad.x, pad.y + 1.0, pad.z, heading, AircraftType.HELICOPTER);
        if (plane == null) {
            return null;
        }
        fitBooster(plane, AutopilotConfig.ROUTE_MAX_SPEED);
        plane.setDeltaMovement(Vec3.ZERO);
        plane.setThrottle(0);
        addToWorld(level, plane);

        int cruiseAltitude = Helipad.cruiseAltitude(level, departure, destination);
        PlaneAutopilot autopilot = new PlaneAutopilot();
        plane.setAutopilot(autopilot);
        autopilot.start(plane, FlightPlan.heliSortie(destination.centre(), cruiseAltitude,
            destination.name(), departure.name(), cruiseSpeed, departureDelayTicks), true, true, owner);
        return plane;
    }

    /**
     * The arrival half of a helicopter sortie on its own: launched in the air at {@code from} and
     * sent one-way to a pad. The rotorcraft counterpart of {@code inbound}, and it exists for the
     * same reason — so the let-down can be iterated on without flying the departure first.
     */
    public static @Nullable PlaneEntity launchHelicopterInbound(ServerLevel level, Vec3 from,
                                                                Helipad destination, @Nullable Player owner,
                                                                double cruiseSpeed) {
        HelipadReport.load(level, destination);
        int cruiseAltitude = Math.max((int) from.y,
            Helipad.cruiseAltitude(level, destination, destination));
        Vec3 start = new Vec3(from.x, cruiseAltitude, from.z);
        double heading = AutopilotMath.headingTo(start, destination.touchdown());

        PlaneEntity plane = create(level, start.x, start.y, start.z, heading, AircraftType.HELICOPTER);
        if (plane == null) {
            return null;
        }
        fitBooster(plane, AutopilotConfig.ROUTE_MAX_SPEED);
        // Launched already making way, like every other airborne launch here: a machine dropped in
        // with no speed spends its first seconds accelerating, which on a rotorcraft it does by
        // pitching its nose down and descending.
        Vec3 run = destination.touchdown().subtract(start);
        if (run.lengthSqr() > 1.0E-6) {
            plane.setDeltaMovement(run.normalize().scale(cruiseSpeed).multiply(1, 0, 1));
        }
        plane.setThrottle(BoosterUpgrade.MAX_THROTTLE);
        addToWorld(level, plane);

        PlaneAutopilot autopilot = new PlaneAutopilot();
        plane.setAutopilot(autopilot);
        autopilot.start(plane, FlightPlan.heliSortie(destination.centre(), cruiseAltitude,
            destination.name(), null, cruiseSpeed, 0), true, true, owner);
        return plane;
    }

    /** Cruise altitude for a sortie: clear of the terrain at both fields and everything between. */
    public static int sortieCruiseAltitude(ServerLevel level, Airfield from, Airfield to) {
        double highest = Math.max(from.centre().y, to.centre().y);
        Vec3 a = from.centre();
        Vec3 b = to.centre();
        int samples = 24;
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double x = a.x + (b.x - a.x) * t;
            double z = a.z + (b.z - a.z) * t;
            int surface = TerrainScanner.surfaceHeight(level, x, z);
            if (surface != TerrainScanner.UNKNOWN_HEIGHT) {
                highest = Math.max(highest, surface);
            }
        }
        return (int) Math.min(highest + 60, level.getMaxY() - 10);
    }

    /**
     * Makes a region resident before anything is spawned into or measured on it.
     *
     * <p>{@code ServerLevel#getChunk(int, int)} generates and returns the chunk synchronously, which
     * is what is needed here: a ticket alone only <em>schedules</em> the load, and the aircraft would
     * be spawned into a chunk that does not exist yet — it would fall through the world or, more
     * usually, sit there not ticking. The ticket is still added so the chunk stays resident
     * afterwards.
     */
    /**
     * Makes a whole airfield resident: both thresholds, the ground between them, and every marked
     * stand.
     *
     * <p><b>The stands are not covered by the centreline sweep and have to be listed separately.</b>
     * Each {@link #loadAround} pulls in a 3x3 block of chunks about a point on the centreline, which
     * is 24 blocks either side of it — and a stand beside a 25-wide strip sits further out than that.
     * Two things went wrong measurably while it did not: a departure read "unknown terrain" for a
     * perfectly good marked apron and fell back to parking on the centreline, and — worse, because it
     * is silent — an arrival asking whether a stand was free got its answer from
     * {@code Level#getEntities}, which reports an <em>empty</em> chunk as empty. Measured on the rig:
     * two sorties into one field, the first parked on a stand and its chunk ticket expired 40 ticks
     * later, and the second landed a thousand ticks after that, found the stand "free" because the
     * aircraft standing on it was not loaded, and taxied on top of it.
     */
    public static void loadAirfield(ServerLevel level, Airfield airfield) {
        Vec3 a = airfield.pointA();
        Vec3 b = airfield.pointB();
        int steps = Math.max(1, (int) Math.ceil(airfield.length() / 16.0));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            loadAround(level, new Vec3(a.x + (b.x - a.x) * t, a.y, a.z + (b.z - a.z) * t));
        }
        for (BlockPos spot : airfield.parkingSpots()) {
            loadAround(level, new Vec3(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5));
        }
    }

    /** {@link #loadAround} for callers outside this class — a helipad is one region and no more. */
    public static void loadRegion(ServerLevel level, Vec3 centre) {
        loadAround(level, centre);
    }

    private static void loadAround(ServerLevel level, Vec3 centre) {
        int chunkX = Mth.floor(centre.x) >> 4;
        int chunkZ = Mth.floor(centre.z) >> 4;
        level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL,
            new ChunkPos(chunkX, chunkZ), AutopilotConfig.CHUNK_TICKET_RADIUS);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.getChunk(chunkX + dx, chunkZ + dz);
            }
        }
    }

    /** Cruise high enough to clear the terrain under every waypoint. */
    public static int cruiseAltitudeFor(Level level, List<BlockPos> waypoints) {
        int highest = Integer.MIN_VALUE;
        for (BlockPos waypoint : waypoints) {
            int surface = TerrainScanner.surfaceHeight(level, waypoint.getX() + 0.5, waypoint.getZ() + 0.5);
            int candidate = surface == TerrainScanner.UNKNOWN_HEIGHT ? waypoint.getY() : Math.max(surface, waypoint.getY());
            highest = Math.max(highest, candidate);
        }
        if (highest == Integer.MIN_VALUE) {
            return (int) AutopilotConfig.DEFAULT_CRUISE_ALTITUDE;
        }
        return Math.min(highest + 60, level.getMaxY() - 10);
    }

    private static void addToWorld(Level level, PlaneEntity plane) {
        // Chunks first, and resident before the entity is added rather than merely requested. An
        // aircraft is nearly always spawned far from any player, and an entity added to a chunk that
        // is not loaded yet does not tick — it just hangs there. A ticket on its own only schedules
        // the load, so the spawn chunk is also pulled in synchronously.
        if (level instanceof ServerLevel serverLevel) {
            loadAround(serverLevel, plane.position());
            PlaneAutopilot.keepChunksLoaded(serverLevel, plane);
        }
        level.addFreshEntity(plane);
    }

    private static @Nullable PlaneEntity create(Level level, double x, double y, double z, double heading) {
        return create(level, x, y, z, heading, AircraftType.PLANE);
    }

    private static @Nullable PlaneEntity create(Level level, double x, double y, double z, double heading,
                                                AircraftType type) {
        PlaneEntity plane = type.resolve(level.getRandom()).entityType().get()
            .create(level, EntitySpawnReason.COMMAND);
        if (plane == null) {
            return null;
        }
        plane.setPos(x, y, z);
        plane.setYRot((float) heading);
        plane.yRotO = (float) heading;
        // The physics reads the orientation from the quaternion, not from yRot, so it has to agree
        // with the spawn heading or the aircraft immediately banks back onto its old heading.
        plane.setQ(MathUtil.toQuaternionf(heading, 0, 0));
        plane.setQ_Client(MathUtil.toQuaternionf(heading, 0, 0));
        plane.setQ_prev(MathUtil.toQuaternionf(heading, 0, 0));
        plane.setMaxSpeed(1.0f);
        return plane;
    }
}
