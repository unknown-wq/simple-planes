package xyz.przemyk.simpleplanes.api;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /airspaceguard} — the off switch for {@link AirspaceGuards}.
 *
 * <pre>
 * /airspaceguard            # same as status
 * /airspaceguard status
 * /airspaceguard on
 * /airspaceguard off
 * </pre>
 *
 * <h2>Why a command and not a config option</h2>
 * The same reason {@link BlastGuardCommand} is one: this port has no editable config to put it in.
 * NeoForge's {@code ModConfigSpec} has no Fabric equivalent and was not replaced, so
 * {@link xyz.przemyk.simpleplanes.setup.SimplePlanesConfig} is a frozen set of compile-time defaults
 * and the README lists "the config is not editable" as a known cut. A command is what this mod
 * actually steers with — {@code /autopilot}, {@code /gunship} and {@code /blastguard} are all bare
 * feature-named roots gated at the game-master level, and this follows them exactly. It also gets
 * something a config file would not: the switch takes effect on an aircraft's next route search,
 * with no restart, no reload, and no disturbance to a flight already under way.
 *
 * <p>Like the others, <b>no subcommand requires a player</b> — this runs from the server console, a
 * command block or a datapack function.
 *
 * <h2>What it reports</h2>
 * The status line says both things a person needs and neither mod is allowed to assume: whether the
 * switch is on, and whether any guard is actually registered. Those are independent. Someone running
 * this mod on its own has avoidance switched on and no guards at all, and the honest answer for them
 * is that their aircraft route exactly as they always did — not a bare "enabled" that implies
 * something is happening.
 *
 * <p>It also says the one thing about scope that people get wrong: this steers the autopilot and
 * nothing else. A player at the controls is never routed and never stopped.
 */
public final class AirspaceGuardCommand {

    private AirspaceGuardCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("airspaceguard")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(AirspaceGuardCommand::status);

            root.then(Commands.literal("status").executes(AirspaceGuardCommand::status));
            root.then(Commands.literal("on").executes(context -> set(context, true)));
            root.then(Commands.literal("off").executes(context -> set(context, false)));

            dispatcher.register(root);
        });
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        boolean enabled = AirspaceGuardSettings.isEnabled(level);
        int guards = AirspaceGuards.count();

        context.getSource().sendSuccess(() -> Component.literal(describe(enabled, guards)), false);
        return enabled ? 1 : 0;
    }

    private static int set(CommandContext<CommandSourceStack> context, boolean enabled) {
        ServerLevel level = context.getSource().getLevel();
        boolean changed = AirspaceGuardSettings.setEnabled(level, enabled);
        int guards = AirspaceGuards.count();

        String prefix = changed
            ? (enabled ? "Airspace avoidance switched on. " : "Airspace avoidance switched off. ")
            : (enabled ? "Airspace avoidance was already on. " : "Airspace avoidance was already off. ");
        context.getSource().sendSuccess(() -> Component.literal(prefix + describe(enabled, guards)), true);
        return changed ? 1 : 0;
    }

    /**
     * One sentence covering the switch, whether anything is listening, and the scope — because any
     * one of the three alone misleads.
     */
    private static String describe(boolean enabled, int guards) {
        if (!enabled) {
            return guards == 0
                ? "The autopilot routes on terrain alone."
                : "The autopilot routes on terrain alone; " + guards
                  + " registered airspace guard(s) are not being consulted.";
        }
        if (guards == 0) {
            return "No other mod has registered an airspace guard, so the autopilot routes on terrain alone.";
        }
        return guards + " airspace guard(s) registered; the autopilot may route around claimed airspace. "
               + "Hand-flying is never affected.";
    }
}
