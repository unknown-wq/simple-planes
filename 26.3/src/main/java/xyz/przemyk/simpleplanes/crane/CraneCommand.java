package xyz.przemyk.simpleplanes.crane;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /crane} test command. Stub: registered by the foundation, filled in by the crane work. */
public final class CraneCommand {

    private CraneCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
            Commands.<CommandSourceStack>literal("crane")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("/crane status: not implemented"), false);
                    return 0;
                }))));
    }
}
