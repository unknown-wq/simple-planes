package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-facing output for the autopilot.
 *
 * <p>26.2 has no {@code displayClientMessage}: chat goes through
 * {@link Player#sendSystemMessage(Component)} and the action bar through
 * {@link Player#sendOverlayMessage(Component)}. Mode changes use the action bar so a long flight
 * does not flood chat; surveys and confirmations use chat.
 *
 * <h2>Three volumes, and why</h2>
 * <ul>
 *   <li>{@link #report} — something happened that a player has to know about or act on: an aircraft
 *       lost, a landing that did not happen, an aircraft stuck on a runway, a leg finished. Always
 *       in chat, and in the log when nobody owns the flight, so a headless run is still readable.</li>
 *   <li>{@link #progress} — routine progress: a plan taken, an approach replanned, a taxi begun.
 *       <b>Off unless the owner asked for it</b> with {@code /autopilot debug true}. This is not a
 *       matter of taste. Every holding aircraft re-announced its plan on every replan, and the plan
 *       flips between "holding, runway busy" and a real approach each time anything ahead of it
 *       lands and vacates, so with four machines in the circuit the chat is a wall of lines whose
 *       only varying content is a range nobody acts on. It also never stops: a shuttle nobody is
 *       watching goes round for as long as the schedule runs.</li>
 *   <li>{@link #overlay} — transient status, on the action bar, where it replaces itself.</li>
 * </ul>
 *
 * <p><b>Per player rather than a config key</b>, because every one of these messages already goes to
 * exactly one player — the one who ordered the flight — so a server-wide switch would be the wrong
 * granularity: one person debugging a route would silence or unsilence everybody else's. It is
 * deliberately not persisted either. A debug flag that survives a restart is a trap, and this way
 * there is no saved-data schema change and so nothing to migrate.
 */
public final class AutopilotFeedback {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-autopilot");

    private AutopilotFeedback() {}

    /**
     * Report that must not vanish when nobody owns the flight. A launch from the console, a command
     * block or a datapack function has no player to talk to, and silently dropping the end-of-flight
     * report there makes the feature impossible to debug headlessly.
     */
    public static void report(Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        } else {
            LOGGER.info(message);
        }
    }

    /**
     * Players who have asked to see routine autopilot progress, by UUID.
     *
     * <p>In memory only, and concurrent because the command thread writes it while the server thread
     * reads it on every tick that announces anything.
     */
    private static final Set<UUID> VERBOSE = ConcurrentHashMap.newKeySet();

    /** Whether this player has asked to see routine progress. Nobody has, until they say so. */
    public static boolean verbose(@Nullable Player player) {
        return player != null && VERBOSE.contains(player.getUUID());
    }

    /** Turns routine progress on or off for one player, and says which it now is. */
    public static void setVerbose(Player player, boolean on) {
        if (on) {
            VERBOSE.add(player.getUUID());
        } else {
            VERBOSE.remove(player.getUUID());
        }
    }

    /**
     * Routine progress, which nobody sees by default.
     *
     * <p>Sent to the owner's chat only when they have asked for it. Never at {@code INFO}: these
     * fire several times a minute per aircraft, and writing them to {@code latest.log} on a server
     * running shuttles would move the flood rather than stop it. A flight with no owner at all — one
     * launched from the console, a command block or a datapack, which is how the headless rig flies
     * everything — has nowhere to send them and no player who could ask, so they go to the log at
     * {@code DEBUG}, where they cost nothing under the default log configuration and are there for a
     * run that turns the logger up. What a player has to act on is in {@link #report} either way.
     */
    public static void progress(@Nullable Player player, String message) {
        if (verbose(player)) {
            player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GRAY));
        } else if (player == null) {
            LOGGER.debug(message);
        }
    }

    public static void info(Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }

    public static void success(Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GREEN));
        }
    }

    public static void warn(Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
        }
    }

    public static void overlay(Player player, String message) {
        if (player != null) {
            player.sendOverlayMessage(Component.literal(message));
        }
    }

    /** Announces an autopilot mode change on the action bar. */
    public static void mode(Player owner, PlaneEntity plane, AutopilotMode mode) {
        if (owner != null) {
            owner.sendOverlayMessage(Component.literal("Plane #" + plane.getId() + ": " + mode.getName())
                .withStyle(ChatFormatting.AQUA));
        }
    }
}
