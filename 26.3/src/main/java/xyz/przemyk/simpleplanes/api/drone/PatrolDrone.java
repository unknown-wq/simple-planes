package xyz.przemyk.simpleplanes.api.drone;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * One live patrol drone. Obtained from {@link PatrolDrones#deploy}, {@link PatrolDrones#find} or a
 * {@link DroneController} callback. Server thread only. Do not hold on to it across ticks without checking
 * {@link #isValid()}: the drone may have been unloaded, destroyed or stowed.
 *
 * <p>Orders are remembered by the drone and saved with it, so a drone reloaded after a restart carries on with
 * its route, its operating radius and its controller binding.
 *
 * <p>Part of the stable drone API ({@link PatrolDrones#API_VERSION} 1).
 */
public interface PatrolDrone {

    UUID id();

    /** The drone as an entity, for position, level and identity checks. Never cast it to a concrete type. */
    Entity asEntity();

    /** False once the drone has been removed from the level for any reason. */
    boolean isValid();

    DroneState state();

    DroneStatus status();

    Vec3 position();

    Vec3 home();

    /** Moves the home point; takes effect at the next return. */
    void setHome(Vec3 home);

    /** The route, as waypoints whose Y is ignored: the drone flies at its cruise height above the terrain. */
    List<Vec3> route();

    boolean loops();

    /**
     * Replaces the route and starts flying it (taking off first if parked). Waypoints farther than
     * {@link #maxRange()} from home, horizontally, are refused.
     *
     * @param points waypoints; an empty list means loiter over home.
     * @param loop   true to fly the route round and round; false to return home and land after the last point.
     * @return how many points were refused for being out of range.
     */
    int setRoute(List<Vec3> points, boolean loop);

    /** Operating radius from home, clamped to {@link PatrolDrones#MAX_RANGE}. */
    void setMaxRange(double blocks);

    double maxRange();

    /** Height above the terrain to cruise at, clamped to 8..48. */
    void setCruiseHeight(double blocksAboveTerrain);

    /** Ticks between scans, clamped to 5..100. */
    void setScanInterval(int ticks);

    /** When true the drone starts tracking the first target it sights by itself; off by default. */
    void setAutoTrack(boolean autoTrack);

    /** Takes off (if parked) and flies the current route. */
    void launch();

    /** Leaves the route and orbits {@code target} until it is gone, lost or out of range. */
    void track(Entity target);

    /** Stops tracking and resumes the route. */
    void stopTracking();

    @Nullable UUID trackedTarget();

    /** Where the tracked target was last seen, or null when not tracking. */
    @Nullable Vec3 trackedPosition();

    /** Flies home and lands. */
    void returnHome();

    /** Flies home, lands and is removed; the item goes to {@link DroneController#onStowed}. */
    void stow();

    /** Removes the drone at once, wherever it is, and returns it as an item. */
    ItemStack pack();

    String controllerKey();

    String controllerData();

    void setController(String key, String data);

    @Nullable UUID owner();
}
