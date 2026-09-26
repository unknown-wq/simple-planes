package xyz.przemyk.simpleplanes.airdefence;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * The trailing {@code hostile} keyword on the commands that spawn aircraft ({@code /autopilot strike|route|
 * flight|inbound|heliflight|heliinbound|shuttle add}, {@code /gunship launch}). It is grafted onto every
 * executable node of those subcommands after they are registered, so their own trees stay as they are.
 */
public final class AllegianceOption {

    public static final String KEYWORD = "hostile";

    private static final String[][] SPAWNING = {
        {"autopilot", "strike"}, {"autopilot", "route"}, {"autopilot", "flight"}, {"autopilot", "inbound"},
        {"autopilot", "heliflight"}, {"autopilot", "heliinbound"}, {"autopilot", "shuttle", "add"},
        {"gunship", "launch"},
    };

    private AllegianceOption() {}

    /** Called from a registration callback that runs after the autopilot's and the gunship's. */
    static void graft(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String[] path : SPAWNING) {
            CommandNode<CommandSourceStack> node = dispatcher.getRoot();
            for (String name : path) {
                node = node == null ? null : node.getChild(name);
            }
            if (node != null) graftBelow(node);
        }
    }

    private static void graftBelow(CommandNode<CommandSourceStack> node) {
        List<CommandNode<CommandSourceStack>> children = new ArrayList<>(node.getChildren());
        if (node.getCommand() != null && node.getChild(KEYWORD) == null && !KEYWORD.equals(node.getName())) {
            node.addChild(Commands.<CommandSourceStack>literal(KEYWORD).executes(node.getCommand()).build());
        }
        for (CommandNode<CommandSourceStack> child : children) graftBelow(child);
    }

    public static Allegiance of(CommandContext<CommandSourceStack> context) {
        for (ParsedCommandNode<CommandSourceStack> n : context.getNodes()) {
            if (KEYWORD.equals(n.getNode().getName())) return Allegiance.HOSTILE;
        }
        return Allegiance.FRIENDLY;
    }

    /** Sets the allegiance chosen on the command line and says so when it is hostile. */
    public static void apply(CommandContext<CommandSourceStack> context, @Nullable PlaneEntity plane) {
        if (plane == null) return;
        Allegiance allegiance = of(context);
        plane.setAllegiance(allegiance);
        if (allegiance == Allegiance.HOSTILE) {
            context.getSource().sendSuccess(() -> Component.literal("Aircraft #" + plane.getId() + " is hostile."), true);
        }
    }
}
