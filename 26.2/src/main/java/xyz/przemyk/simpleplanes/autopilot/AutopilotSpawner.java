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
                                                     double approachBearing, @Nullable Player owner) {
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
        plane.addUpgradeUsingWrench(SimplePlanesItems.BOOSTER.get().getDefaultInstance(),
            new BoosterUpgrade(plane));
        plane.setMaxSpeed(STRIKE_MAX_SPEED);
        plane.setThrottle(BoosterUpgrade.MAX_THROTTLE);
        Vec3 run = targetVec.subtract(spawn.x, altitude, spawn.z).normalize();
        plane.setDeltaMovement(run.scale(STRIKE_LAUNCH_SPEED));

        addToWorld(level, plane);

        PlaneAutopilot autopilot = new PlaneAutopilot();
        plane.setAutopilot(autopilot);
        // Powered by the autopilot, and never persisted: a strike aircraft is a one-shot weapon.
        autopilot.start(plane, FlightPlan.strike(target), true, false, owner);
        return plane;
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
