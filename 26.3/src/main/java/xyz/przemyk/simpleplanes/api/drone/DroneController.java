package xyz.przemyk.simpleplanes.api.drone;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The owner of a group of drones: decides what they look for and hears what they find. Registered once
 * under a key with {@link PatrolDrones#registerController}; each drone stores the key (and an opaque data
 * string for the controller's own bookkeeping), so the binding survives a restart without any Java object
 * being saved.
 *
 * <p>Every method runs on the server thread, inside the drone's tick. An exception thrown from one is caught
 * and logged by the drone, which then carries on; it never takes the level down.
 *
 * <p>Part of the stable drone API ({@link PatrolDrones#API_VERSION} 1). New methods are added as defaults.
 */
public interface DroneController {

    /**
     * The detection filter: whether an entity the drone can see is one it should report. Only entities
     * within {@link PatrolDrones#DETECTION_RADIUS} blocks horizontally that are open to the sky are offered;
     * a mob in a cave or under a roof is never seen, whatever this returns.
     */
    default boolean isTarget(PatrolDrone drone, LivingEntity entity) {
        return entity instanceof Enemy;
    }

    /** Whether the drone shrugs this hit off, e.g. an arrow from the owner's own side. */
    default boolean ignoresDamage(PatrolDrone drone, DamageSource source) {
        return false;
    }

    /** Every scan (every {@code scanInterval} ticks in the air): all matching entities in view, maybe none. */
    default void onScan(PatrolDrone drone, List<LivingEntity> seen) {
    }

    /** A matching entity that was not in view on the previous scan. */
    default void onSighting(PatrolDrone drone, LivingEntity entity) {
    }

    /** The drone stopped tracking {@code target}; it resumes its route on the same tick. */
    default void onLostTrack(PatrolDrone drone, UUID target, @Nullable Vec3 lastSeen, LostReason reason) {
    }

    /** The drone landed at its home (after {@link PatrolDrone#returnHome()} or at the end of an open route). */
    default void onReturned(PatrolDrone drone) {
    }

    /** The drone was shot down (it is falling now) or removed by a creative player. It will not come back. */
    default void onDestroyed(PatrolDrone drone, Vec3 where, @Nullable DamageSource cause) {
    }

    /** A player broke the parked drone and took it as an item. */
    default void onPickedUp(PatrolDrone drone, @Nullable Player by) {
    }

    /**
     * The drone was stowed ({@link PatrolDrone#stow()}): it landed and was removed.
     *
     * @return true if the controller took {@code item}; false drops it where the drone landed.
     */
    default boolean onStowed(PatrolDrone drone, ItemStack item) {
        return false;
    }

    /** Why tracking ended. */
    enum LostReason {
        /** The target died or was removed. */
        GONE,
        /** Out of sight for too long. */
        OUT_OF_SIGHT,
        /** The target left the drone's operating radius. */
        OUT_OF_RANGE,
        /** The owner called {@link PatrolDrone#stopTracking()} or gave another order. */
        RELEASED
    }
}
