package xyz.przemyk.simpleplanes.api;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /blastguard} — the off switch for {@link BlastGuards}.
 *
 * <pre>
 * /blastguard            # same as status
 * /blastguard status
 * /blastguard on
 * /blastguard off
 * </pre>
 *
 * <h2>Why a command and not a config option</h2>
 * Because this port has no editable config to put it in. NeoForge's {@code ModConfigSpec} has no
 * Fabric equivalent and was not replaced — {@link xyz.przemyk.simpleplanes.setup.SimplePlanesConfig}
 * is a frozen set of compile-time defaults and the README lists "the config is not editable" as a
 * known cut. A command is what this mod actually steers with: {@code /autopilot} and {@code /gunship}
 * are both bare feature-named roots gated at the game-master level, and this follows them exactly. It
 * also gets something a config file would not: the switch takes effect on the next explosion, with no
 * restart and no reload.
 *
 * <p>Like the other two, <b>no subcommand requires a player</b> — this runs from the server console, a
 * command block or a datapack function.
 *
 * <h2>What it reports</h2>
 * The status line says both things a person needs and neither mod is allowed to assume: whether the
 * switch is on, and whether any guard is actually registered. Those are independent. Someone running
 * this mod on its own has guarding switched on and no guards at all, and the honest answer for them is
 * that their explosions are untouched — not a bare "enabled" that implies something is happening.
 */
public final class BlastGuardCommand {

    private BlastGuardCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("blastguard")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(BlastGuardCommand::status);

            root.then(Commands.literal("status").executes(BlastGuardCommand::status));
            root.then(Commands.literal("on").executes(context -> set(context, true)));
            root.then(Commands.literal("off").executes(context -> set(context, false)));

            dispatcher.register(root);
        });
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        boolean enabled = BlastGuardSettings.isEnabled(level);
        int guards = BlastGuards.count();

        context.getSource().sendSuccess(() -> Component.literal(describe(enabled, guards)), false);
        return enabled ? 1 : 0;
    }

    private static int set(CommandContext<CommandSourceStack> context, boolean enabled) {
        ServerLevel level = context.getSource().getLevel();
        boolean changed = BlastGuardSettings.setEnabled(level, enabled);
        int guards = BlastGuards.count();

        String prefix = changed
            ? (enabled ? "Blast guarding switched on. " : "Blast guarding switched off. ")
            : (enabled ? "Blast guarding was already on. " : "Blast guarding was already off. ");
        context.getSource().sendSuccess(() -> Component.literal(prefix + describe(enabled, guards)), true);
        return changed ? 1 : 0;
    }

    /**
     * One sentence covering both the switch and whether anything is listening, because either alone
     * misleads.
     */
    private static String describe(boolean enabled, int guards) {
        if (!enabled) {
            return guards == 0
                ? "Aircraft explosions are exactly as this mod orders them."
                : "Aircraft explosions are exactly as this mod orders them; " + guards
                  + " registered guard(s) are not being consulted.";
        }
        return guards == 0
            ? "No other mod has registered a blast guard, so aircraft explosions are unaffected."
            : guards + " blast guard(s) registered; they may weaken or cancel an aircraft's explosion.";
    }
}
