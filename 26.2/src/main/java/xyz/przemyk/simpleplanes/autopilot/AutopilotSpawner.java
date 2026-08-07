package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;

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
                                                     double approachBearing, @Nullable Player owner) {
        Vec3 targetVec = new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        Vec3 spawn = AutopilotMath.pointAlong(targetVec, approachBearing, distance);

        double terrain = TerrainScanner.surfaceHeight(level, spawn.x, spawn.z);
        if (terrain == TerrainScanner.UNKNOWN_HEIGHT) {
            terrain = targetVec.y;
        }
        double altitude = Math.max(targetVec.y + AutopilotConfig.STRIKE_SPAWN_HEIGHT, terrain + 45);

        PlaneEntity plane = create(level, spawn.x, altitude, spawn.z, AutopilotMath.headingTo(spawn, targetVec));
        if (plane == null) {
            return null;
        }
        addToWorld(level, plane);

        PlaneAutopilot autopilot = new PlaneAutopilot();
        plane.setAutopilot(autopilot);
        // Powered by the autopilot, and never persisted: a strike aircraft is a one-shot weapon.
        autopilot.start(plane, FlightPlan.strike(target), true, false, owner);
        return plane;
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
                                                    @Nullable String airfieldName, @Nullable Player owner) {
        if (waypoints.isEmpty()) {
            return null;
        }
        BlockPos first = waypoints.get(0);
        Vec3 start = new Vec3(first.getX() + 0.5, cruiseAltitude, first.getZ() + 0.5);
        Vec3 towards = waypoints.size() > 1
            ? new Vec3(waypoints.get(1).getX() + 0.5, cruiseAltitude, waypoints.get(1).getZ() + 0.5)
            : start.add(0, 0, 1);

        PlaneEntity plane = create(level, start.x, start.y, start.z, AutopilotMath.headingTo(start, towards));
        if (plane == null) {
            return null;
        }
        addToWorld(level, plane);

        PlaneAutopilot autopilot = new PlaneAutopilot();
        plane.setAutopilot(autopilot);
        // Powered by the autopilot so a courier aircraft does not need an engine upgrade, and
        // persisted so the route resumes after a restart.
        autopilot.start(plane, FlightPlan.route(waypoints, cruiseAltitude, legs, airfieldName), true, true, owner);
        return plane;
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
        // Ticket first: the aircraft is usually spawned far from anyone, and an entity in a chunk
        // nobody keeps loaded never ticks — it would simply hang in the air. Requesting before the
        // entity is added gets the chunk load under way first. The autopilot renews this every
        // 20 ticks; the ticket itself expires after 40, so nothing leaks.
        if (level instanceof ServerLevel serverLevel) {
            PlaneAutopilot.keepChunksLoaded(serverLevel, plane);
        }
        level.addFreshEntity(plane);
    }

    private static @Nullable PlaneEntity create(Level level, double x, double y, double z, double heading) {
        PlaneEntity plane = SimplePlanesEntities.PLANE.get().create(level, EntitySpawnReason.COMMAND);
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
