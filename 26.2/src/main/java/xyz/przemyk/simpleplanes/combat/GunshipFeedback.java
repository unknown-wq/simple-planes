package xyz.przemyk.simpleplanes.combat;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Output for the gunship. Same rule the autopilot follows and for the same reason: a sortie launched
 * from the console, a command block or a datapack function has no player to talk to, and dropping
 * the report there would make the feature untestable headlessly.
 *
 * <p>Its own logger and its own three methods rather than a call into
 * {@code autopilot.AutopilotFeedback}, so that nothing in {@code combat/} depends on a package
 * another agent is rewriting.
 */
public final class GunshipFeedback {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-gunship");

    /** {@code -Dsimpleplanes.gunship.trace=true} adds a line per shot. Off by default. */
    public static final boolean TRACE = Boolean.getBoolean("simpleplanes.gunship.trace");

    private GunshipFeedback() {}

    /**
     * Every report goes to the log, and additionally to the player who ordered the sortie if there
     * was one.
     *
     * <p>The log is unconditional rather than a fallback for the ownerless case. A sortie flown by a
     * player used to report only in chat, which meant the one run whose behaviour anybody actually
     * cared about was the one run that left no record — and a bug reported as "it said it landed but
     * it was in the sea" had nothing to read afterwards.
     */
    public static void report(@Nullable Player player, String message) {
        LOGGER.info(message);
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }

    public static void trace(String message) {
        if (TRACE) {
            LOGGER.info(message);
        }
    }
}
