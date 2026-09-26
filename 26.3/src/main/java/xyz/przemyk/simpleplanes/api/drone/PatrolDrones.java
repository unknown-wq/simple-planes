package xyz.przemyk.simpleplanes.api.drone;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.drone.DroneRegistry;
import xyz.przemyk.simpleplanes.drone.PatrolDroneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server API for the recon/patrol drone: put a drone in the air from its item, give it a route, and hear what
 * it sees. Stable; bump {@link #API_VERSION} on an incompatible change.
 *
 * <pre>
 * PatrolDrones.registerController("mymod", controller);           // once, from the mod initialiser
 * PatrolDrone d = PatrolDrones.deploy(level, item, home, null, "mymod", "base-7/slot-1");
 * d.setRoute(List.of(a, b, c), true);                              // takes off and flies the loop
 * // controller.onSighting / onScan / onLostTrack / onReturned / onDestroyed arrive from the drone's tick
 * PatrolDrones.find(level, id)                                      // later, by UUID
 * </pre>
 *
 * <p>What the drone guarantees: it flies at a fixed height above the terrain, never leaves its operating radius
 * ({@link #MAX_RANGE} at most), keeps the chunks around it loaded while airborne (and asks for them again after a
 * restart), sees living entities within {@link #DETECTION_RADIUS} blocks horizontally that are open to the sky,
 * and is not a target for Simple Planes' air defence, which engages {@code PlaneEntity} aircraft only.
 *
 * <p>Server thread only. Nothing here touches a client class.
 */
public final class PatrolDrones {

    /**
     * Version of this API. 1: deploy / find / all / lastKnownPosition / isKnown / summon, {@link PatrolDrone}
     * orders, {@link DroneController} events and filter, {@link DroneStatus}.
     */
    public static final int API_VERSION = 1;

    /** Horizontal detection radius, blocks: one chunk. */
    public static final double DETECTION_RADIUS = 16.0;

    /** Largest operating radius a drone accepts, blocks from home. */
    public static final double MAX_RANGE = 1000.0;

    private static final Map<String, DroneController> CONTROLLERS = new ConcurrentHashMap<>();

    private PatrolDrones() {}

    /** Binds a controller key. Registering the same key again replaces the earlier controller. */
    public static void registerController(String key, DroneController controller) {
        CONTROLLERS.put(key, controller);
    }

    /** The controller bound to {@code key}, or null. */
    public static @Nullable DroneController controller(String key) {
        return key == null || key.isEmpty() ? null : CONTROLLERS.get(key);
    }

    /** Whether the stack is a patrol drone item. */
    public static boolean isDroneItem(ItemStack stack) {
        return !stack.isEmpty() && stack.is(SimplePlanesItems.PATROL_DRONE_ITEM.get());
    }

    /** A new, undamaged drone item. */
    public static ItemStack newDroneItem() {
        return new ItemStack(SimplePlanesItems.PATROL_DRONE_ITEM.get());
    }

    /**
     * Puts a drone down at {@code home}, parked, bound to a controller. The caller owns {@code item} and removes
     * it from wherever it came from; this reads its health and name and does not change it.
     *
     * @return the drone, or null if {@code item} is not a drone item or the entity could not be added.
     */
    public static @Nullable PatrolDrone deploy(ServerLevel level, ItemStack item, Vec3 home, @Nullable UUID owner,
                                               String controllerKey, String controllerData) {
        if (!isDroneItem(item)) {
            return null;
        }
        return PatrolDroneEntity.deploy(level, item, home, owner, controllerKey, controllerData);
    }

    /** The loaded drone with this UUID in this level, or null. */
    public static @Nullable PatrolDrone find(ServerLevel level, UUID id) {
        return DroneRegistry.find(level, id);
    }

    /** Every loaded drone in this level. */
    public static List<PatrolDrone> all(ServerLevel level) {
        return List.copyOf(DroneRegistry.all(level));
    }

    /**
     * Whether a drone with this UUID still exists in this level as far as the saved roster knows: true for a
     * drone that is merely unloaded, false once it has been destroyed, stowed, picked up, or written off as
     * missing (its chunk loaded and it was not there).
     */
    public static boolean isKnown(ServerLevel level, UUID id) {
        return DroneRegistry.isKnown(level, id);
    }

    /** Where the roster last saw this drone, or null if it is not known. */
    public static @Nullable Vec3 lastKnownPosition(ServerLevel level, UUID id) {
        return DroneRegistry.lastKnown(level, id);
    }

    /**
     * Asks for the chunk the drone was last seen in to be loaded for a few seconds, so an unloaded drone comes
     * back into {@link #find}. A drone whose chunk loads without it is written off (see {@link #isKnown}).
     */
    public static void summon(ServerLevel level, UUID id) {
        DroneRegistry.summon(level, id);
    }
}
