package xyz.przemyk.simpleplanes.api.drone;

import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A snapshot of one drone, taken on the server thread. Part of the stable drone API.
 *
 * @param id            the drone's entity UUID, stable across saves.
 * @param entityId      the entity id in this session.
 * @param state         what it is doing.
 * @param position      where it is.
 * @param home          where it takes off from and lands.
 * @param routeSize     number of waypoints in its route.
 * @param routeIndex    the waypoint it is flying to, or -1 with no route.
 * @param loop          whether the route loops.
 * @param tracked       the entity it is orbiting, or null.
 * @param trackedAt     the last position it saw that entity at, or null.
 * @param health        current health.
 * @param maxHealth     full health.
 * @param maxRange      operating radius from home, blocks.
 * @param controllerKey the controller the drone reports to, "" for none.
 */
public record DroneStatus(UUID id,
                          int entityId,
                          DroneState state,
                          Vec3 position,
                          Vec3 home,
                          int routeSize,
                          int routeIndex,
                          boolean loop,
                          @Nullable UUID tracked,
                          @Nullable Vec3 trackedAt,
                          int health,
                          int maxHealth,
                          double maxRange,
                          String controllerKey) {
}
