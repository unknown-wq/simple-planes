package xyz.przemyk.simpleplanes.crane;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.entities.QuadcopterEntity;

import java.util.Locale;
import java.util.UUID;

/** Crane messages: terminal events go to the owner's chat and always to the log. */
public final class CraneFeedback {

    public static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-crane");

    private CraneFeedback() {}

    /** A terminal event: owner's chat if online, and always the log, as {@code Crane #id: message}. */
    public static void report(QuadcopterEntity crane, String message) {
        String line = "Crane #" + crane.getId() + ": " + message;
        UUID owner = crane.getOwner();
        Player player = owner == null ? null : crane.level().getPlayerByUUID(owner);
        if (player != null) {
            player.sendSystemMessage(Component.literal(line));
        }
        LOGGER.info(line);
    }

    /** Routine progress: log only. */
    public static void log(QuadcopterEntity crane, String message) {
        LOGGER.info("Crane #" + crane.getId() + ": " + message);
    }

    /** A message to a player with no crane involved (remote without a link); logged as well. */
    public static void tell(@Nullable Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
        LOGGER.info(message);
    }

    public static void trace(QuadcopterEntity crane, int tick) {
        Vec3 v = crane.getDeltaMovement();
        LOGGER.info(String.format(Locale.ROOT,
            "trace crane #%d t=%d state=%s pos=%.3f,%.3f,%.3f vel=%.3f,%.3f,%.3f tilt=%.2f thrust=%.4f L=%.2f theta=%.2f,%.2f load=%s sat=%b",
            crane.getId(), tick, crane.getState(), crane.getX(), crane.getY(), crane.getZ(), v.x, v.y, v.z,
            crane.phys.tilt(), crane.phys.thrust, crane.rope.length,
            Math.toDegrees(crane.rope.thetaX), Math.toDegrees(crane.rope.thetaZ), crane.getLoadName(), crane.isSaturated()));
    }

    /** The status line of {@code /crane status}. */
    public static String status(QuadcopterEntity crane) {
        Vec3 v = crane.getDeltaMovement();
        Entity load = crane.getFirstPassenger();
        Vec3 t = crane.getTarget();
        return String.format(Locale.ROOT,
            "#%d state=%s pos=%.2f,%.2f,%.2f vel=%.3f,%.3f,%.3f tilt=%.2f thrust=%.4f L=%.2f thetaX=%.2f thetaZ=%.2f load=%s mass=%.2f sat=%b agl=%.2f carrying=%b health=%d target=%s",
            crane.getId(), crane.getState(), crane.getX(), crane.getY(), crane.getZ(), v.x, v.y, v.z,
            crane.phys.tilt(), crane.phys.thrust, crane.rope.length,
            Math.toDegrees(crane.rope.thetaX), Math.toDegrees(crane.rope.thetaZ),
            load == null ? "none" : load.getName().getString(), crane.rope.mass, crane.isSaturated(), crane.agl(),
            crane.isCarrying(), crane.getHealth(),
            t == null ? "none" : String.format(Locale.ROOT, "%.1f,%.1f,%.1f", t.x, t.y, t.z));
    }
}
