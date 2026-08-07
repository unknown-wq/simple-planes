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
import net.minecraft.world.level.entity.EntityTypeTest;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.List;

/**
 * {@code /autopilot} — drives the whole feature so the flight model can be exercised on a headless
 * dedicated server.
 *
 * <p><b>No subcommand requires a player.</b> Every one of them takes explicit coordinates, so they
 * all run from the server console, a command block or a datapack function. A player is only an
 * optional convenience: it makes relative coordinates ({@code ~ ~ ~}) work and decides which side an
 * attack run comes in from.
 *
 * <pre>
 * /autopilot strike &lt;target&gt; [distance] [bearing]
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
                    .executes(context -> strike(context, AutopilotConfig.STRIKE_SPAWN_DISTANCE, null))
                    .then(Commands.argument("distance", IntegerArgumentType.integer(20, 4000))
                        .executes(context -> strike(context, IntegerArgumentType.getInteger(context, "distance"), null))
                        .then(Commands.argument("bearing", IntegerArgumentType.integer(0, 359))
                            .executes(context -> strike(context,
                                IntegerArgumentType.getInteger(context, "distance"),
                                (double) IntegerArgumentType.getInteger(context, "bearing")))))));

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

    /**
     * @param compassBearing explicit run-in bearing in compass degrees, or null to derive one from
     *                       wherever the command was issued
     */
    private static int strike(CommandContext<CommandSourceStack> context, int distance,
                              @Nullable Double compassBearing) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos target = BlockPosArgument.getLoadedBlockPos(context, "target");

        double bearing = compassBearing != null
            // Compass degrees to Minecraft yaw: 0 compass (north) is yaw 180.
            ? compassBearing - 180.0
            : AutopilotSpawner.approachBearingFrom(source.getPosition(), target);

        if (!RunwayOccupancy.canActivateAnother()) {
            source.sendFailure(Component.literal("Too many autopilot aircraft already flying ("
                + RunwayOccupancy.activeCount() + "/" + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS + ")."));
            return 0;
        }

        PlaneEntity plane = AutopilotSpawner.launchStrike(level, target, distance, bearing, source.getPlayer());
        if (plane == null) {
            source.sendFailure(Component.literal("Could not create the aircraft."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(
            "Strike launched: plane #%d inbound to %s from %d blocks on bearing %03.0f.",
            plane.getId(), target.toShortString(), distance, AutopilotMath.compassHeading(bearing))), true);
        return 1;
    }

    private static int route(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos from = BlockPosArgument.getLoadedBlockPos(context, "from");
        BlockPos to = BlockPosArgument.getLoadedBlockPos(context, "to");
        List<BlockPos> waypoints = List.of(from, to);

        if (!RunwayOccupancy.canActivateAnother()) {
            source.sendFailure(Component.literal("Too many autopilot aircraft already flying ("
                + RunwayOccupancy.activeCount() + "/" + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS + ")."));
            return 0;
        }

        int cruiseAltitude = AutopilotSpawner.cruiseAltitudeFor(level, waypoints);
        Airfield nearest = AutopilotSavedData.get(level).nearest(from.getX(), from.getZ(), 512);
        PlaneEntity plane = AutopilotSpawner.launchRoute(level, waypoints, cruiseAltitude, 2,
            nearest == null ? null : nearest.name(), source.getPlayer());
        if (plane == null) {
            source.sendFailure(Component.literal("Could not create the aircraft."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Plane #" + plane.getId() + " flying "
            + from.toShortString() + " -> " + to.toShortString() + " -> " + from.toShortString()
            + " at altitude " + cruiseAltitude + ", "
            + (nearest == null ? "improvised landing" : "landing at " + nearest.name())), true);
        return 1;
    }

    private static int survey(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos first = BlockPosArgument.getLoadedBlockPos(context, "threshold1");
        BlockPos second = BlockPosArgument.getLoadedBlockPos(context, "threshold2");
        AirfieldReport.surveyAndRegister(AutopilotOutput.toSource(source), source.getLevel(), first, second);
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
                source.sendSuccess(() -> Component.literal("  " + autopilot.statusLine(plane)), false);
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
        source.sendSuccess(() -> Component.literal("Stopped " + planes.size() + " autopilot aircraft."), true);
        return planes.size();
    }

    private static List<? extends PlaneEntity> activePlanes(ServerLevel level) {
        return level.getEntities(EntityTypeTest.forClass(PlaneEntity.class),
            plane -> plane.getAutopilot() != null && plane.getAutopilot().isActive());
    }
}
