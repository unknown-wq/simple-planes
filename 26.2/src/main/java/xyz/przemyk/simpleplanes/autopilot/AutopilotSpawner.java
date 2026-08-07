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

/** Creates and tasks autopilot aircraft. */
public final class AutopilotSpawner {

    private AutopilotSpawner() {}

    /**
     * Spawns an aircraft {@link AutopilotConfig#STRIKE_SPAWN_DISTANCE} blocks from {@code target},
     * on the far side of the player so it runs in past them, and sends it at the target at full
     * throttle.
     *
     * @return the aircraft, or null if it could not be created
     */
    public static @Nullable PlaneEntity launchStrike(Level level, Player player, BlockPos target, int distance) {
        Vec3 targetVec = new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        double bearingToPlayer = AutopilotMath.headingTo(targetVec, player.position());
        Vec3 spawn = AutopilotMath.pointAlong(targetVec, bearingToPlayer, distance);

        double terrain = TerrainScanner.surfaceHeight(level, spawn.x, spawn.z);
        if (terrain == TerrainScanner.UNKNOWN_HEIGHT) {
            terrain = targetVec.y;
        }
        double altitude = Math.max(targetVec.y + AutopilotConfig.STRIKE_SPAWN_HEIGHT, terrain + 45);

        PlaneEntity plane = create(level, spawn.x, altitude, spawn.z, AutopilotMath.headingTo(spawn, targetVec));
        if (plane == null) {
            return null;
        }
        level.addFreshEntity(plane);
        // The aircraft is usually spawned far from anyone; without a ticket its chunk never ticks
        // and it would simply hang in the air. The autopilot renews this every 20 ticks.
        if (level instanceof ServerLevel serverLevel) {
            PlaneAutopilot.keepChunksLoaded(serverLevel, plane);
        }

        PlaneAutopilot autopilot = new PlaneAutopilot();
        plane.setAutopilot(autopilot);
        // Powered by the autopilot, and never persisted: a strike aircraft is a one-shot weapon.
        autopilot.start(plane, FlightPlan.strike(target), true, false, player);
        return plane;
    }

    /**
     * Spawns an aircraft at the first waypoint and sets it flying the route. The aircraft is a
     * normal plane: it is <em>not</em> expendable, so it needs the terrain to be reasonable and it
     * will be persisted with the world.
     */
    public static @Nullable PlaneEntity launchRoute(Level level, Player player, List<BlockPos> waypoints,
                                                    int cruiseAltitude, int legs, @Nullable String airfieldName) {
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
        level.addFreshEntity(plane);
        // The aircraft is usually spawned far from anyone; without a ticket its chunk never ticks
        // and it would simply hang in the air. The autopilot renews this every 20 ticks.
        if (level instanceof ServerLevel serverLevel) {
            PlaneAutopilot.keepChunksLoaded(serverLevel, plane);
        }

        PlaneAutopilot autopilot = new PlaneAutopilot();
        plane.setAutopilot(autopilot);
        // Powered by the autopilot so a courier aircraft does not need an engine upgrade, and
        // persisted so the route resumes after a restart.
        autopilot.start(plane, FlightPlan.route(waypoints, cruiseAltitude, legs, airfieldName), true, true, player);
        return plane;
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
