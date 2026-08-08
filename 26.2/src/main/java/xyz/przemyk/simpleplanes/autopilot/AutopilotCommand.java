package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
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
 * /autopilot flight &lt;fromAirfield&gt; &lt;toAirfield&gt;
 * /autopilot inbound &lt;from&gt; &lt;airfield&gt;
 * /autopilot survey &lt;threshold1&gt; &lt;threshold2&gt;
 * /autopilot airfields
 * /autopilot tower [&lt;airfield&gt;]
 * /autopilot status
 * /autopilot stop
 * </pre>
 *
 * <p><b>Positions may be unloaded.</b> Everything except {@code survey} takes its coordinates with
 * {@link BlockPosArgument#getBlockPos}, because a destination hundreds of blocks away is by
 * definition outside anyone's simulation distance and the aircraft loads its own chunks as it goes.
 * {@code survey} is the exception and keeps the loaded requirement, since it measures real blocks.
 * {@code flight} and {@code inbound} take airfields by name and so sidestep the question entirely.
 */
public final class AutopilotCommand {

    private AutopilotCommand() {}

    /** Tab completion for airfield-name arguments, from whatever is registered in this dimension. */
    private static final SuggestionProvider<CommandSourceStack> AIRFIELD_SUGGESTIONS =
        (context, builder) -> SharedSuggestionProvider.suggest(
            AutopilotSavedData.get(context.getSource().getLevel()).airfieldList().stream()
                .map(airfield -> "\"" + airfield.name() + "\""), builder);

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

            root.then(Commands.literal("flight")
                .then(Commands.argument("from", StringArgumentType.string())
                    .suggests(AIRFIELD_SUGGESTIONS)
                    .then(Commands.argument("to", StringArgumentType.string())
                        .suggests(AIRFIELD_SUGGESTIONS)
                        .executes(AutopilotCommand::flight))));

            root.then(Commands.literal("inbound")
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                    .then(Commands.argument("airfield", StringArgumentType.string())
                        .suggests(AIRFIELD_SUGGESTIONS)
                        .executes(AutopilotCommand::inbound))));

            root.then(Commands.literal("survey")
                .then(Commands.argument("threshold1", BlockPosArgument.blockPos())
                    .then(Commands.argument("threshold2", BlockPosArgument.blockPos())
                        .executes(AutopilotCommand::survey))));

            root.then(Commands.literal("tower")
                .executes(AutopilotCommand::tower)
                .then(Commands.argument("airfield", StringArgumentType.string())
                    .suggests(AIRFIELD_SUGGESTIONS)
                    .executes(AutopilotCommand::towerOne)));

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
        // getBlockPos, not getLoadedBlockPos. The target of an attack run is by definition hundreds
        // of blocks away and therefore outside anyone's simulation distance, so demanding a loaded
        // position rejected exactly the flights this command exists to fly ("That position is not
        // loaded"). The aircraft carries its own chunk tickets — see AutopilotRegistry — so the
        // destination does not have to be resident when the order is given.
        BlockPos target = BlockPosArgument.getBlockPos(context, "target");

        double bearing = compassBearing != null
            // Compass degrees to Minecraft yaw: 0 compass (north) is yaw 180.
            ? compassBearing - 180.0
            : AutopilotSpawner.approachBearingFrom(source.getPosition(), target);

        if (!RunwayOccupancy.canActivateAnother()) {
            source.sendFailure(Component.literal("Too many autopilot aircraft already flying ("
                + RunwayOccupancy.activeCount() + "/" + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS + ")."));
            return 0;
        }

        // TODO(blast): the command argument that selects strength, block damage and fire is the next
        // step; until then every strike uses the historic 4.0F TNT warhead.
        PlaneEntity plane = AutopilotSpawner.launchStrike(level, target, distance, bearing,
            source.getPlayer(), Blast.DEFAULT);
        if (plane == null) {
            source.sendFailure(Component.literal("Could not create the aircraft."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            AutopilotSpawner.describeLaunch(plane, target, distance, AutopilotMath.compassHeading(bearing))), true);
        return 1;
    }

    private static int route(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        // Unloaded positions accepted, for the same reason as the strike target above.
        BlockPos from = BlockPosArgument.getBlockPos(context, "from");
        BlockPos to = BlockPosArgument.getBlockPos(context, "to");
        List<BlockPos> waypoints = List.of(from, to);

        if (!RunwayOccupancy.canActivateAnother()) {
            source.sendFailure(Component.literal("Too many autopilot aircraft already flying ("
                + RunwayOccupancy.activeCount() + "/" + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS + ")."));
            return 0;
        }

        int cruiseAltitude = AutopilotSpawner.cruiseAltitudeFor(level, waypoints);
        Airfield nearest = AutopilotSavedData.get(level).nearest(from.getX(), from.getZ(), 512);
        PlaneEntity plane = AutopilotSpawner.launchRoute(level, waypoints, cruiseAltitude, 2,
            nearest == null ? null : nearest.name(), source.getPlayer(),
            AutopilotConfig.CRUISE_SPEED, Blast.DEFAULT);
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

    /**
     * The whole sortie: spawn parked at one registered airfield, taxi out, take off, cruise, and fly
     * an instrument approach onto the other.
     *
     * <p>Takes the airfields by name, so it needs no block-position argument and cannot be refused
     * for pointing at unloaded ground — which is the normal case, since both runways are usually
     * nowhere near a player. The spawner makes the departure and destination chunks resident itself.
     */
    private static int flight(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String fromName = StringArgumentType.getString(context, "from");
        String toName = StringArgumentType.getString(context, "to");

        AutopilotSavedData data = AutopilotSavedData.get(level);
        Airfield from = data.get(fromName);
        Airfield to = data.get(toName);
        if (from == null || to == null) {
            source.sendFailure(Component.literal("No such airfield: "
                + (from == null ? fromName : toName) + ". Use /autopilot airfields to list them."));
            return 0;
        }
        if (from.name().equals(to.name())) {
            source.sendFailure(Component.literal("Departure and destination are the same airfield."));
            return 0;
        }
        if (!AutopilotRegistry.canActivateAnother()) {
            source.sendFailure(Component.literal("Too many autopilot aircraft already flying ("
                + AutopilotRegistry.activeCount() + "/" + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS + ")."));
            return 0;
        }

        PlaneEntity plane = AutopilotSpawner.launchSortie(level, from, to, source.getPlayer(),
            AutopilotConfig.CRUISE_SPEED, Blast.DEFAULT);
        if (plane == null) {
            source.sendFailure(Component.literal("Could not create the aircraft."));
            return 0;
        }
        double distance = AutopilotMath.horizontalDistance(from.centre(), to.centre());
        source.sendSuccess(() -> Component.literal("Plane #" + plane.getId() + " parked at "
            + from.name() + " (" + Math.round(plane.getX()) + ", " + Math.round(plane.getY())
            + ", " + Math.round(plane.getZ()) + "), sortie to " + to.name()
            + " - " + Math.round(distance) + " blocks."), true);
        return 1;
    }

    /**
     * A one-way arrival: launch in the air at a given point and fly an approach into a named
     * airfield. Exists so the landing can be exercised on its own, without first surviving the
     * departure, and because a genuine inbound flight is something {@code route} cannot express.
     */
    private static int inbound(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos from = BlockPosArgument.getBlockPos(context, "from");
        String name = StringArgumentType.getString(context, "airfield");

        Airfield destination = AutopilotSavedData.get(level).get(name);
        if (destination == null) {
            source.sendFailure(Component.literal("No such airfield: " + name
                + ". Use /autopilot airfields to list them."));
            return 0;
        }
        if (!AutopilotRegistry.canActivateAnother()) {
            source.sendFailure(Component.literal("Too many autopilot aircraft already flying ("
                + AutopilotRegistry.activeCount() + "/" + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS + ")."));
            return 0;
        }

        PlaneEntity plane = AutopilotSpawner.launchInbound(level,
            new Vec3(from.getX() + 0.5, from.getY(), from.getZ() + 0.5), destination, source.getPlayer(),
            AutopilotConfig.CRUISE_SPEED, Blast.DEFAULT);
        if (plane == null) {
            source.sendFailure(Component.literal("Could not create the aircraft."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Plane #" + plane.getId() + " inbound to "
            + destination.name() + " from " + Math.round(plane.getX()) + ", " + Math.round(plane.getY())
            + ", " + Math.round(plane.getZ()) + " - "
            + Math.round(AutopilotMath.horizontalDistance(plane.position(), destination.centre()))
            + " blocks."), true);
        return 1;
    }

    private static int survey(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        // getLoadedBlockPos is correct here and is deliberately kept: a survey measures real blocks —
        // surface heights, width, slope, roughness — so surveying unloaded ground would silently
        // register a runway made of nothing. Refusing with "That position is not loaded" is the right
        // answer; go and stand on the runway.
        BlockPos first = BlockPosArgument.getLoadedBlockPos(context, "threshold1");
        BlockPos second = BlockPosArgument.getLoadedBlockPos(context, "threshold2");
        AirfieldReport.surveyAndRegister(AutopilotOutput.toSource(source), source.getLevel(), first, second);
        return 1;
    }

    /**
     * The tower board for every runway in this dimension: free or occupied, by whom, in what mode,
     * for how long, and who is holding for it.
     *
     * <p>Read-only. It reserves nothing and releases nothing; occupancy comes from
     * {@link RunwayOccupancy#holder}, the same validated answer the aircraft themselves get.
     *
     * <p>The board's sentences carry an English fallback, so a player reading it in chat gets their
     * own language and the dedicated server console — which loads no mod language files — still
     * prints English rather than raw translation keys.
     */
    private static int tower(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<Component> lines = TowerBoard.board(source.getLevel());
        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
        }
        return lines.size();
    }

    private static int towerOne(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "airfield");
        List<Component> lines = TowerBoard.board(source.getLevel(), name);
        if (lines.isEmpty()) {
            source.sendFailure(TowerBoard.unknownAirfield(name));
            return 0;
        }
        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
        }
        return lines.size();
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
