package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.entity.EntityTypeTest;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.items.RouteWandItem;
import xyz.przemyk.simpleplanes.items.RunwayToolItem;

import java.util.List;

/**
 * {@code /autopilot} — drives the whole feature without needing the items, so the flight model can
 * be exercised on a headless dedicated server.
 *
 * <pre>
 * /autopilot strike &lt;pos&gt; [distance]
 * /autopilot route &lt;from&gt; &lt;to&gt;
 * /autopilot survey &lt;threshold1&gt; &lt;threshold2&gt;
 * /autopilot airfields
 * /autopilot status
 * /autopilot stop
 * </pre>
 */
public final class AutopilotCommand {

    private AutopilotCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("autopilot")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

            root.then(Commands.literal("strike")
                .then(Commands.argument("target", BlockPosArgument.blockPos())
                    .executes(context -> strike(context, AutopilotConfig.STRIKE_SPAWN_DISTANCE))
                    .then(Commands.argument("distance", IntegerArgumentType.integer(20, 4000))
                        .executes(context -> strike(context, IntegerArgumentType.getInteger(context, "distance"))))));

            root.then(Commands.literal("route")
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                    .then(Commands.argument("to", BlockPosArgument.blockPos())
                        .executes(AutopilotCommand::route))));

            root.then(Commands.literal("survey")
                .then(Commands.argument("threshold1", BlockPosArgument.blockPos())
                    .then(Commands.argument("threshold2", BlockPosArgument.blockPos())
                        .executes(AutopilotCommand::survey))));

            root.then(Commands.literal("airfields").executes(AutopilotCommand::airfields));
            root.then(Commands.literal("status").executes(AutopilotCommand::status));
            root.then(Commands.literal("stop").executes(AutopilotCommand::stop));

            dispatcher.register(root);
        });
    }

    private static int strike(CommandContext<CommandSourceStack> context, int distance) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        BlockPos target = BlockPosArgument.getLoadedBlockPos(context, "target");
        PlaneEntity plane = AutopilotSpawner.launchStrike(source.getLevel(), player, target, distance);
        if (plane == null) {
            source.sendSuccess(() -> Component.literal("Could not create the aircraft."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Strike launched: plane #" + plane.getId()
            + " inbound to " + target.toShortString() + " from " + distance + " blocks."), false);
        return 1;
    }

    private static int route(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        BlockPos from = BlockPosArgument.getLoadedBlockPos(context, "from");
        BlockPos to = BlockPosArgument.getLoadedBlockPos(context, "to");
        List<BlockPos> waypoints = List.of(from, to);

        int cruiseAltitude = RouteWandItem.cruiseAltitudeFor(level, waypoints);
        Airfield nearest = AutopilotSavedData.get(level).nearest(from.getX(), from.getZ(), 512);
        PlaneEntity plane = AutopilotSpawner.launchRoute(level, player, waypoints, cruiseAltitude, 2,
            nearest == null ? null : nearest.name());
        if (plane == null) {
            source.sendSuccess(() -> Component.literal("Could not create the aircraft."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Plane #" + plane.getId() + " flying "
            + from.toShortString() + " -> " + to.toShortString() + " -> " + from.toShortString()
            + " at altitude " + cruiseAltitude + ", "
            + (nearest == null ? "improvised landing" : "landing at " + nearest.name())), false);
        return 1;
    }

    private static int survey(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        BlockPos first = BlockPosArgument.getLoadedBlockPos(context, "threshold1");
        BlockPos second = BlockPosArgument.getLoadedBlockPos(context, "threshold2");
        RunwayToolItem.survey(player, source.getLevel(), first, second);
        return 1;
    }

    private static int airfields(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<Airfield> list = AutopilotSavedData.get(source.getLevel()).airfieldList();
        if (list.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No airfields registered in this dimension."), false);
            return 0;
        }
        for (Airfield airfield : list) {
            source.sendSuccess(() -> Component.literal(airfield.name() + " " + airfield.designators()
                + " at " + airfield.thresholdA().toShortString()
                + ", " + (int) airfield.length() + "x" + airfield.width()), false);
        }
        return list.size();
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<? extends PlaneEntity> planes = activePlanes(source.getLevel());
        source.sendSuccess(() -> Component.literal(RunwayOccupancy.activeCount() + "/"
            + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS + " autopilot aircraft active, "
            + planes.size() + " in this dimension."), false);
        for (PlaneEntity plane : planes) {
            PlaneAutopilot autopilot = plane.getAutopilot();
            if (autopilot != null) {
                source.sendSuccess(() -> Component.literal("  " + autopilot.describe(plane)
                    + String.format(" at %.0f,%.0f,%.0f", plane.getX(), plane.getY(), plane.getZ())), false);
            }
        }
        return planes.size();
    }

    private static int stop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<? extends PlaneEntity> planes = activePlanes(source.getLevel());
        for (PlaneEntity plane : planes) {
            PlaneAutopilot autopilot = plane.getAutopilot();
            if (autopilot != null) {
                autopilot.stop(plane);
            }
            plane.setAutopilot(null);
        }
        source.sendSuccess(() -> Component.literal("Stopped " + planes.size() + " autopilot aircraft."), false);
        return planes.size();
    }

    private static List<? extends PlaneEntity> activePlanes(ServerLevel level) {
        return level.getEntities(EntityTypeTest.forClass(PlaneEntity.class),
            plane -> plane.getAutopilot() != null && plane.getAutopilot().isActive());
    }
}
