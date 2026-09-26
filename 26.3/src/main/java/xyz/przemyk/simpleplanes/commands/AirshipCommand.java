package xyz.przemyk.simpleplanes.commands;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /airship} test command. Stub: registered by the foundation, filled in by the airship work. */
public final class AirshipCommand {

    private AirshipCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
            Commands.<CommandSourceStack>literal("airship")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("/airship status: not implemented"), false);
                    return 0;
                }))));
    }
}
