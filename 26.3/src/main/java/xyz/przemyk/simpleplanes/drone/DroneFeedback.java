package xyz.przemyk.simpleplanes.drone;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.api.drone.DroneController;
import xyz.przemyk.simpleplanes.api.drone.PatrolDrone;

import java.util.Locale;
import java.util.UUID;

/**
 * Drone messages, and the controller used by a drone that has none (placed from the item or by {@code /drone}):
 * default filter ({@code Enemy}), events to the log and to the owner's chat.
 */
public final class DroneFeedback implements DroneController {

    public static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-drone");

    public static final DroneFeedback DEFAULT = new DroneFeedback();

    private DroneFeedback() {}

    /** A notable event: owner's chat if online, and always the log. */
    public static void report(PatrolDroneEntity drone, String message) {
        String line = "Drone #" + drone.getId() + ": " + message;
        UUID owner = drone.owner();
        Player player = owner == null ? null : drone.level().getPlayerByUUID(owner);
        if (player != null) {
            player.sendSystemMessage(Component.literal(line));
        }
        LOGGER.info(line);
    }

    /** Routine progress: log only. */
    public static void log(PatrolDroneEntity drone, String message) {
        LOGGER.info("Drone #" + drone.getId() + ": " + message);
    }

    static String fmt(@Nullable Vec3 v) {
        return v == null ? "none" : String.format(Locale.ROOT, "%.1f %.1f %.1f", v.x, v.y, v.z);
    }

    @Override
    public void onSighting(PatrolDrone drone, LivingEntity entity) {
        report((PatrolDroneEntity) drone, "sighted " + entity.getName().getString() + " at " + fmt(entity.position()));
    }

    @Override
    public void onLostTrack(PatrolDrone drone, UUID target, @Nullable Vec3 lastSeen, LostReason reason) {
        report((PatrolDroneEntity) drone, "lost track (" + reason.name().toLowerCase(Locale.ROOT) + ") last seen " + fmt(lastSeen));
    }

    @Override
    public void onReturned(PatrolDrone drone) {
        report((PatrolDroneEntity) drone, "landed at home " + fmt(drone.home()));
    }
}
