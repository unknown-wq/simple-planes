package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Where a report goes. The runway survey and the flight summaries are printed identically whether
 * they were triggered by a player holding a tool or by {@code /autopilot} typed at the server
 * console, so neither path needs a player to exist.
 */
public interface AutopilotOutput {

    void line(String text);

    default void success(String text) {
        line(text);
    }

    default void warn(String text) {
        line(text);
    }

    /**
     * Sends a rich chat component — clickable names, copyable coordinates, hover tooltips.
     *
     * <p>Degrades rather than disappearing: the default implementation flattens the component to
     * its plain text, which is what a console, a command block or a datapack function should see.
     * Every caller must therefore keep the information in the text and use the click and hover
     * events only for convenience, never to carry a value that is not also written out.
     */
    default void component(Component component) {
        line(component.getString());
    }

    /** Sends to a player's chat. */
    static AutopilotOutput toPlayer(Player player) {
        return new AutopilotOutput() {
            @Override
            public void line(String text) {
                AutopilotFeedback.info(player, text);
            }

            @Override
            public void component(Component component) {
                player.sendSystemMessage(component);
            }

            @Override
            public void success(String text) {
                AutopilotFeedback.success(player, text);
            }

            @Override
            public void warn(String text) {
                AutopilotFeedback.warn(player, text);
            }
        };
    }

    /** Sends to a command source — the server console, a command block or a datapack function. */
    static AutopilotOutput toSource(CommandSourceStack source) {
        return new AutopilotOutput() {
            @Override
            public void line(String text) {
                source.sendSuccess(() -> Component.literal(text), false);
            }

            @Override
            public void component(Component component) {
                // A player source gets the interactive component; the console prints its flattened
                // text, which is why every row has to read correctly without its click events.
                source.sendSuccess(() -> component, false);
            }

            @Override
            public void success(String text) {
                source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), false);
            }

            @Override
            public void warn(String text) {
                source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.RED), false);
            }
        };
    }
}
