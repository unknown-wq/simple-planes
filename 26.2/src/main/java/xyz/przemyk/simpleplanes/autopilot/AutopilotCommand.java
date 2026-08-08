package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
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
 * /autopilot strike &lt;target&gt; [distance] [bearing] [blast] [blocks] [fire]
 * /autopilot route &lt;from&gt; &lt;to&gt; [speed]
 * /autopilot flight &lt;fromAirfield&gt; &lt;toAirfield&gt; [speed] [delay &lt;seconds&gt;]
 * /autopilot inbound &lt;from&gt; &lt;airfield&gt; [speed]
 * /autopilot survey &lt;threshold1&gt; &lt;threshold2&gt;
 * /autopilot airfields [info|show|remove|rename|park|unpark] …
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

            // strike <target> [distance] [bearing] [blast] [blocks] [fire]
            //
            // Positional and progressively optional, which is the shape the subcommand already had.
            // Every level executes the same method: the optional arguments are read back by name
            // from the parsed context, so the tree stays flat instead of gaining a lambda per
            // combination. See optionalInt/optionalFloat/optionalBool below.
            root.then(Commands.literal("strike")
                .then(Commands.argument("target", BlockPosArgument.blockPos())
                    .executes(AutopilotCommand::strike)
                    .then(Commands.argument("distance", IntegerArgumentType.integer(20, 4000))
                        .executes(AutopilotCommand::strike)
                        .then(Commands.argument("bearing", IntegerArgumentType.integer(0, 359))
                            .executes(AutopilotCommand::strike)
                            .then(Commands.argument("blast",
                                    FloatArgumentType.floatArg(Blast.MIN_POWER, Blast.MAX_POWER))
                                .executes(AutopilotCommand::strike)
                                .then(Commands.argument("blocks", BoolArgumentType.bool())
                                    .executes(AutopilotCommand::strike)
                                    .then(Commands.argument("fire", BoolArgumentType.bool())
                                        .executes(AutopilotCommand::strike))))))));

            root.then(Commands.literal("route")
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                    .then(Commands.argument("to", BlockPosArgument.blockPos())
                        .executes(context -> route(context, AutopilotConfig.CRUISE_SPEED))
                        .then(Commands.argument("speed", cruiseSpeedArgument())
                            .executes(context -> route(context, requestedSpeed(context)))))));

            // flight <from> <to> [speed] [delay <seconds>]
            //
            // The delay is what a person thinks of first, but it cannot be the first argument: every
            // existing `/autopilot flight "a" "b"` and `/autopilot flight "a" "b" 2.60` has to keep
            // parsing exactly as it does, and inserting a positional argument in front of them would
            // reinterpret the airfield names. A trailing positional would not work either — it would
            // be reachable only by also giving a speed. A keyword branch off both the two-argument
            // and the three-argument forms gets the delay without touching either, and reads as what
            // it is at the call site.
            root.then(Commands.literal("flight")
                .then(Commands.argument("from", StringArgumentType.string())
                    .suggests(AIRFIELD_SUGGESTIONS)
                    .then(Commands.argument("to", StringArgumentType.string())
                        .suggests(AIRFIELD_SUGGESTIONS)
                        .executes(AutopilotCommand::flight)
                        .then(departureDelayArgument())
                        .then(Commands.argument("speed", cruiseSpeedArgument())
                            .executes(AutopilotCommand::flight)
                            .then(departureDelayArgument())))));

            root.then(Commands.literal("inbound")
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                    .then(Commands.argument("airfield", StringArgumentType.string())
                        .suggests(AIRFIELD_SUGGESTIONS)
                        .executes(context -> inbound(context, AutopilotConfig.CRUISE_SPEED))
                        .then(Commands.argument("speed", cruiseSpeedArgument())
                            .executes(context -> inbound(context, requestedSpeed(context)))))));

            root.then(Commands.literal("survey")
                .then(Commands.argument("threshold1", BlockPosArgument.blockPos())
                    .then(Commands.argument("threshold2", BlockPosArgument.blockPos())
                        .executes(AutopilotCommand::survey))));

            root.then(Commands.literal("airfields")
                .executes(AutopilotCommand::airfields)
                .then(Commands.literal("info")
                    .then(Commands.argument("airfield", StringArgumentType.string())
                        .suggests(AIRFIELD_SUGGESTIONS)
                        .executes(AutopilotCommand::airfieldInfo)))
                .then(Commands.literal("show")
                    .then(Commands.argument("airfield", StringArgumentType.string())
                        .suggests(AIRFIELD_SUGGESTIONS)
                        .executes(AutopilotCommand::airfieldShow)))
                .then(Commands.literal("remove")
                    .then(Commands.argument("airfield", StringArgumentType.string())
                        .suggests(AIRFIELD_SUGGESTIONS)
                        .executes(AutopilotCommand::airfieldRemove)))
                .then(Commands.literal("rename")
                    .then(Commands.argument("airfield", StringArgumentType.string())
                        .suggests(AIRFIELD_SUGGESTIONS)
                        .then(Commands.argument("name", StringArgumentType.string())
                            .executes(AutopilotCommand::airfieldRename))))
                .then(Commands.literal("park")
                    .then(Commands.argument("airfield", StringArgumentType.string())
                        .suggests(AIRFIELD_SUGGESTIONS)
                        .then(Commands.argument("spot", BlockPosArgument.blockPos())
                            .executes(AutopilotCommand::airfieldPark))))
                .then(Commands.literal("unpark")
                    .then(Commands.argument("airfield", StringArgumentType.string())
                        .suggests(AIRFIELD_SUGGESTIONS)
                        .then(Commands.argument("spot", BlockPosArgument.blockPos())
                            .executes(AutopilotCommand::airfieldUnpark)))));

            root.then(Commands.literal("tower")
                .executes(AutopilotCommand::tower)
                .then(Commands.argument("airfield", StringArgumentType.string())
                    .suggests(AIRFIELD_SUGGESTIONS)
                    .executes(AutopilotCommand::towerOne)));

            root.then(Commands.literal("status").executes(AutopilotCommand::status));
            root.then(Commands.literal("stop").executes(AutopilotCommand::stop));

            dispatcher.register(root);
        });
    }

    /**
     * True when an optional argument was actually supplied on this invocation.
     *
     * <p>Brigadier has no notion of a default value: an argument that was not parsed simply is not
     * in the context, and asking for it throws {@code IllegalArgumentException}. The parsed argument
     * map is private, but the list of nodes that were actually matched is not, and an argument node
     * carries its argument name — so walking it is the exception-free way to ask, and it lets one
     * method serve every level of an optional positional chain.
     */
    private static boolean has(CommandContext<CommandSourceStack> context, String name) {
        for (ParsedCommandNode<CommandSourceStack> node : context.getNodes()) {
            if (node.getNode().getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static int optionalInt(CommandContext<CommandSourceStack> context, String name, int fallback) {
        return has(context, name) ? IntegerArgumentType.getInteger(context, name) : fallback;
    }

    private static float optionalFloat(CommandContext<CommandSourceStack> context, String name, float fallback) {
        return has(context, name) ? FloatArgumentType.getFloat(context, name) : fallback;
    }

    private static boolean optionalBool(CommandContext<CommandSourceStack> context, String name, boolean fallback) {
        return has(context, name) ? BoolArgumentType.getBool(context, name) : fallback;
    }

    /**
     * The optional cruise-speed argument shared by {@code route}, {@code flight} and {@code inbound},
     * in blocks per tick.
     *
     * <p>The accepted range is deliberately wider than the flyable one and the value is put through
     * {@link AutopilotConfig#clampCruiseSpeed} instead: a syntax error for asking a plane to go too
     * fast tells the user nothing, whereas the launch line reports the speed the aircraft is
     * actually being sent at, clamp and all.
     */
    private static DoubleArgumentType cruiseSpeedArgument() {
        return DoubleArgumentType.doubleArg(0.0, 10.0);
    }

    private static double requestedSpeed(CommandContext<CommandSourceStack> context) {
        return has(context, "speed")
            ? AutopilotConfig.clampCruiseSpeed(DoubleArgumentType.getDouble(context, "speed"))
            : AutopilotConfig.CRUISE_SPEED;
    }

    /**
     * {@code delay <seconds>} — how long a sortie waits on its parking spot before it asks for the
     * runway.
     *
     * <p>Seconds, because that is what a person thinks in; the flight plan stores ticks. Bounded
     * rather than unbounded because a parked aircraft holds one of the
     * {@link AutopilotConfig#MAX_ACTIVE_AUTOPILOTS} slots for the whole wait, so a mistyped delay is
     * indistinguishable from a launch that failed.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> departureDelayArgument() {
        return Commands.literal("delay")
            .then(Commands.argument("seconds",
                    IntegerArgumentType.integer(0, AutopilotConfig.MAX_DEPARTURE_DELAY_SECONDS))
                .executes(AutopilotCommand::flight));
    }

    private static int departureDelayTicks(CommandContext<CommandSourceStack> context) {
        return optionalInt(context, "seconds", 0) * 20;
    }

    /** " at 2.40 blocks/tick", or " at the default 2.40 blocks/tick" — always says what was ordered. */
    private static String describeSpeed(double cruiseSpeed) {
        return String.format(" at %.2f blocks/tick", cruiseSpeed);
    }

    /**
     * The warhead this strike carries.
     *
     * <p>Every field defaults to what an aircraft has always done — {@value Blast#DEFAULT_POWER}
     * strength, blocks broken, no fire — so an argument-free {@code /autopilot strike} is unchanged.
     * {@link Blast} clamps the power itself, so the range on the argument is a helpful error message
     * rather than the actual guard.
     */
    private static Blast blastFrom(CommandContext<CommandSourceStack> context) {
        return new Blast(
            optionalFloat(context, "blast", Blast.DEFAULT_POWER),
            optionalBool(context, "blocks", true),
            optionalBool(context, "fire", false));
    }

    private static int strike(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int distance = optionalInt(context, "distance", AutopilotConfig.STRIKE_SPAWN_DISTANCE);
        // Explicit run-in bearing in compass degrees, or null to derive one from wherever the
        // command was issued.
        Double compassBearing = has(context, "bearing")
            ? (double) IntegerArgumentType.getInteger(context, "bearing")
            : null;
        Blast blast = blastFrom(context);
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

        PlaneEntity plane = AutopilotSpawner.launchStrike(level, target, distance, bearing,
            source.getPlayer(), blast);
        if (plane == null) {
            source.sendFailure(Component.literal("Could not create the aircraft."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            AutopilotSpawner.describeLaunch(plane, target, distance, AutopilotMath.compassHeading(bearing))
                + " Warhead: " + blast.describe() + "."), true);
        return 1;
    }

    private static int route(CommandContext<CommandSourceStack> context, double cruiseSpeed)
        throws CommandSyntaxException {
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
            cruiseSpeed, Blast.DEFAULT);
        if (plane == null) {
            source.sendFailure(Component.literal("Could not create the aircraft."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Plane #" + plane.getId() + " flying "
            + from.toShortString() + " -> " + to.toShortString() + " -> " + from.toShortString()
            + " at altitude " + cruiseAltitude + describeSpeed(cruiseSpeed) + ", "
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
        double cruiseSpeed = requestedSpeed(context);
        int delayTicks = departureDelayTicks(context);
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
        // Refused here rather than discovered by an aircraft in the air. Both ends are checked: the
        // departure has to be long enough to get off, and the destination long enough to get back on.
        for (Airfield airfield : List.of(from, to)) {
            Component refusal = AirfieldBrowser.usabilityRefusal(airfield);
            if (refusal != null) {
                source.sendFailure(refusal);
                return 0;
            }
        }
        if (!AutopilotRegistry.canActivateAnother()) {
            source.sendFailure(Component.literal("Too many autopilot aircraft already flying ("
                + AutopilotRegistry.activeCount() + "/" + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS + ")."));
            return 0;
        }

        PlaneEntity plane = AutopilotSpawner.launchSortie(level, from, to, source.getPlayer(),
            cruiseSpeed, Blast.DEFAULT, delayTicks);
        if (plane == null) {
            source.sendFailure(Component.literal("Could not create the aircraft."));
            return 0;
        }
        double distance = AutopilotMath.horizontalDistance(from.centre(), to.centre());
        source.sendSuccess(() -> Component.literal("Plane #" + plane.getId() + " parked at "
            + from.name() + " (" + Math.round(plane.getX()) + ", " + Math.round(plane.getY())
            + ", " + Math.round(plane.getZ()) + "), sortie to " + to.name()
            + " - " + Math.round(distance) + " blocks" + describeSpeed(cruiseSpeed)
            + (delayTicks > 0 ? ", departing in " + delayTicks / 20 + "s" : "")
            + " (once the runway is free)."), true);
        return 1;
    }

    /**
     * A one-way arrival: launch in the air at a given point and fly an approach into a named
     * airfield. Exists so the landing can be exercised on its own, without first surviving the
     * departure, and because a genuine inbound flight is something {@code route} cannot express.
     */
    private static int inbound(CommandContext<CommandSourceStack> context, double cruiseSpeed)
        throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos from = BlockPosArgument.getBlockPos(context, "from");
        String name = StringArgumentType.getString(context, "airfield");

        Airfield destination = AutopilotSavedData.get(level).get(name);
        if (destination == null) {
            source.sendFailure(AirfieldBrowser.unknown(name));
            return 0;
        }
        Component refusal = AirfieldBrowser.usabilityRefusal(destination);
        if (refusal != null) {
            source.sendFailure(refusal);
            return 0;
        }
        if (!AutopilotRegistry.canActivateAnother()) {
            source.sendFailure(Component.literal("Too many autopilot aircraft already flying ("
                + AutopilotRegistry.activeCount() + "/" + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS + ")."));
            return 0;
        }

        PlaneEntity plane = AutopilotSpawner.launchInbound(level,
            new Vec3(from.getX() + 0.5, from.getY(), from.getZ() + 0.5), destination, source.getPlayer(),
            cruiseSpeed, Blast.DEFAULT);
        if (plane == null) {
            source.sendFailure(Component.literal("Could not create the aircraft."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Plane #" + plane.getId() + " inbound to "
            + destination.name() + " from " + Math.round(plane.getX()) + ", " + Math.round(plane.getY())
            + ", " + Math.round(plane.getZ()) + " - "
            + Math.round(AutopilotMath.horizontalDistance(plane.position(), destination.centre()))
            + " blocks" + describeSpeed(cruiseSpeed) + "."), true);
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
        return AirfieldBrowser.list(AutopilotOutput.toSource(source), source.getLevel(),
            source.getPosition(), originName(source));
    }

    /**
     * What the distances in the browser are measured from.
     *
     * <p>A player is at a place they can see. The console and a command block are not: their source
     * position is the world spawn, which is a perfectly good origin as long as the header says that
     * is what it is — an unlabelled "1.2 km" is a number nobody can act on.
     */
    private static String originName(CommandSourceStack source) {
        if (source.getPlayer() != null) {
            return source.getPlayer().getName().getString();
        }
        Vec3 origin = source.getPosition();
        return String.format("%.0f, %.0f (world origin)", origin.x, origin.z);
    }

    private static int airfieldInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Airfield airfield = named(context);
        if (airfield == null) {
            source.sendFailure(AirfieldBrowser.unknown(StringArgumentType.getString(context, "airfield")));
            return 0;
        }
        AirfieldBrowser.detail(AutopilotOutput.toSource(source), source.getLevel(), airfield,
            source.getPosition(), originName(source));
        return 1;
    }

    /**
     * Draws the runway in world with particles — the same highlight the survey itself produces, so
     * a field registered a week ago can be found again without re-surveying it.
     */
    private static int airfieldShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Airfield airfield = named(context);
        if (airfield == null) {
            source.sendFailure(AirfieldBrowser.unknown(StringArgumentType.getString(context, "airfield")));
            return 0;
        }
        AirfieldReport.highlight(source.getLevel(), airfield);
        source.sendSuccess(() -> Component.literal("Marked " + airfield.name() + " at "
            + airfield.thresholdA().toShortString() + "."), false);
        return 1;
    }

    private static int airfieldRemove(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        return AirfieldBrowser.remove(AutopilotOutput.toSource(source), source.getLevel(),
            StringArgumentType.getString(context, "airfield")) ? 1 : 0;
    }

    private static int airfieldRename(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        return AirfieldBrowser.rename(AutopilotOutput.toSource(source), source.getLevel(),
            StringArgumentType.getString(context, "airfield"),
            StringArgumentType.getString(context, "name")) ? 1 : 0;
    }

    private static int airfieldPark(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        // getLoadedBlockPos, as for survey and for the same reason: marking a parking spot measures
        // the ground there and the ground all the way to the threshold, and unloaded ground reads as
        // nothing at all.
        BlockPos spot = BlockPosArgument.getLoadedBlockPos(context, "spot");
        return AirfieldBrowser.park(AutopilotOutput.toSource(source), source.getLevel(),
            StringArgumentType.getString(context, "airfield"), spot) ? 1 : 0;
    }

    private static int airfieldUnpark(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos spot = BlockPosArgument.getBlockPos(context, "spot");
        return AirfieldBrowser.unpark(AutopilotOutput.toSource(source), source.getLevel(),
            StringArgumentType.getString(context, "airfield"), spot) ? 1 : 0;
    }

    private static @Nullable Airfield named(CommandContext<CommandSourceStack> context) {
        return AutopilotSavedData.get(context.getSource().getLevel())
            .get(StringArgumentType.getString(context, "airfield"));
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
