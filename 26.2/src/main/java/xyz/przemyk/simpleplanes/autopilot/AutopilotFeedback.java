package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

/**
 * Player-facing output for the autopilot.
 *
 * <p>26.2 has no {@code displayClientMessage}: chat goes through
 * {@link Player#sendSystemMessage(Component)} and the action bar through
 * {@link Player#sendOverlayMessage(Component)}. Mode changes use the action bar so a long flight
 * does not flood chat; surveys and confirmations use chat.
 */
public final class AutopilotFeedback {

    private AutopilotFeedback() {}

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
