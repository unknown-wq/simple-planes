package xyz.przemyk.simpleplanes.drone;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.api.drone.DroneStatus;
import xyz.przemyk.simpleplanes.api.drone.PatrolDrones;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /drone}: operator control of patrol drones (permission 2), by entity id as {@code /drone list} prints it.
 * Every reply also goes to the log.
 *
 * <pre>
 * /drone spawn [pos]                          parked drone, owned by the caller
 * /drone list | status [id]
 * /drone route &lt;id&gt; add &lt;pos&gt; | clear | loop &lt;bool&gt; | go
 * /drone patrol &lt;id&gt; square|circle &lt;radius&gt;  loop around home
 * /drone launch|home|stow|untrack &lt;id&gt;
 * /drone track &lt;id&gt; &lt;entity&gt;
 * /drone autotrack &lt;id&gt; &lt;bool&gt;  /drone height &lt;id&gt; &lt;agl&gt;  /drone range &lt;id&gt; &lt;blocks&gt;  /drone scan &lt;id&gt; &lt;ticks&gt;
 * /drone kill                                 discards every loaded drone
 * </pre>
 */
public final class DroneCommand {

    private DroneCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.<CommandSourceStack>literal("drone")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

            root.then(Commands.literal("spawn")
                .executes(c -> spawn(c, c.getSource().getPosition()))
                .then(Commands.argument("pos", Vec3Argument.vec3(false))
                    .executes(c -> spawn(c, Vec3Argument.getVec3(c, "pos")))));

            root.then(Commands.literal("list").executes(DroneCommand::list));

            root.then(Commands.literal("status")
                .executes(DroneCommand::list)
                .then(id().executes(c -> {
                    PatrolDroneEntity d = drone(c);
                    return d == null ? 0 : say(c.getSource(), status(d.status()));
                })));

            root.then(Commands.literal("route").then(id()
                .then(Commands.literal("add").then(Commands.argument("pos", Vec3Argument.vec3(false)).executes(c -> {
                    PatrolDroneEntity d = drone(c);
                    if (d == null) {
                        return 0;
                    }
                    List<Vec3> route = new ArrayList<>(d.route());
                    route.add(Vec3Argument.getVec3(c, "pos"));
                    int refused = d.setRoute(route, d.loops());
                    return refused > 0
                        ? fail(c.getSource(), "Drone #" + d.getId() + ": waypoint refused, beyond " + (int) d.maxRange() + " blocks of home")
                        : say(c.getSource(), "Drone #" + d.getId() + ": route has " + d.route().size() + " point(s)");
                })))
                .then(Commands.literal("clear").executes(c -> {
                    PatrolDroneEntity d = drone(c);
                    if (d == null) {
                        return 0;
                    }
                    d.setRoute(List.of(), d.loops());
                    return say(c.getSource(), "Drone #" + d.getId() + ": route cleared");
                }))
                .then(Commands.literal("loop").then(Commands.argument("loop", BoolArgumentType.bool()).executes(c -> {
                    PatrolDroneEntity d = drone(c);
                    if (d == null) {
                        return 0;
                    }
                    boolean loop = BoolArgumentType.getBool(c, "loop");
                    d.setRoute(d.route(), loop);
                    return say(c.getSource(), "Drone #" + d.getId() + ": loop " + loop);
                })))));

            root.then(Commands.literal("patrol").then(id()
                .then(Commands.literal("square").then(radius().executes(c -> patrol(c, 4))))
                .then(Commands.literal("circle").then(radius().executes(c -> patrol(c, 12))))));

            root.then(order("launch", d -> d.launch(), "launch"));
            root.then(order("home", d -> d.returnHome(), "returning home"));
            root.then(order("stow", d -> d.stow(), "stowing"));
            root.then(order("untrack", d -> d.stopTracking(), "tracking released"));

            root.then(Commands.literal("track").then(id()
                .then(Commands.argument("target", EntityArgument.entity()).executes(c -> {
                    PatrolDroneEntity d = drone(c);
                    if (d == null) {
                        return 0;
                    }
                    Entity target = EntityArgument.getEntity(c, "target");
                    d.track(target);
                    return d.trackedTarget() != null && d.trackedTarget().equals(target.getUUID())
                        ? say(c.getSource(), "Drone #" + d.getId() + ": tracking " + target.getName().getString())
                        : fail(c.getSource(), "Drone #" + d.getId() + ": cannot track " + target.getName().getString()
                            + " (beyond " + (int) d.maxRange() + " blocks of home, or drone down)");
                }))));

            root.then(Commands.literal("autotrack").then(id()
                .then(Commands.argument("on", BoolArgumentType.bool()).executes(c -> {
                    PatrolDroneEntity d = drone(c);
                    if (d == null) {
                        return 0;
                    }
                    d.setAutoTrack(BoolArgumentType.getBool(c, "on"));
                    return say(c.getSource(), "Drone #" + d.getId() + ": autotrack " + d.autoTrack());
                }))));

            root.then(Commands.literal("height").then(id()
                .then(Commands.argument("agl", DoubleArgumentType.doubleArg(0, 300)).executes(c -> {
                    PatrolDroneEntity d = drone(c);
                    if (d == null) {
                        return 0;
                    }
                    d.setCruiseHeight(DoubleArgumentType.getDouble(c, "agl"));
                    return say(c.getSource(), String.format(Locale.ROOT, "Drone #%d: cruise %.0f above terrain", d.getId(), d.cruiseAgl()));
                }))));

            root.then(Commands.literal("range").then(id()
                .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0, 100000)).executes(c -> {
                    PatrolDroneEntity d = drone(c);
                    if (d == null) {
                        return 0;
                    }
                    d.setMaxRange(DoubleArgumentType.getDouble(c, "blocks"));
                    return say(c.getSource(), String.format(Locale.ROOT, "Drone #%d: range %.0f", d.getId(), d.maxRange()));
                }))));

            root.then(Commands.literal("scan").then(id()
                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 1000)).executes(c -> {
                    PatrolDroneEntity d = drone(c);
                    if (d == null) {
                        return 0;
                    }
                    d.setScanInterval(IntegerArgumentType.getInteger(c, "ticks"));
                    return say(c.getSource(), "Drone #" + d.getId() + ": scan every " + d.scanInterval() + " ticks");
                }))));

            root.then(Commands.literal("kill").executes(c -> {
                int n = 0;
                for (ServerLevel level : c.getSource().getServer().getAllLevels()) {
                    for (PatrolDroneEntity d : DroneRegistry.all(level)) {
                        d.pack();
                        n++;
                    }
                }
                return say(c.getSource(), "removed " + n + " drone(s)");
            }));

            dispatcher.register(root);
        });
    }

    private interface Order {
        void apply(PatrolDroneEntity drone);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> order(String name, Order order, String done) {
        return Commands.<CommandSourceStack>literal(name).then(id().executes(c -> {
            PatrolDroneEntity d = drone(c);
            if (d == null) {
                return 0;
            }
            order.apply(d);
            return say(c.getSource(), "Drone #" + d.getId() + ": " + done + " (" + d.state() + ")");
        }));
    }

    private static int spawn(CommandContext<CommandSourceStack> c, Vec3 pos) {
        CommandSourceStack source = c.getSource();
        UUID owner = source.getPlayer() == null ? null : source.getPlayer().getUUID();
        PatrolDroneEntity d = PatrolDroneEntity.deploy(source.getLevel(), PatrolDrones.newDroneItem(), pos, owner, "", "");
        return d == null ? fail(source, "could not place a drone at " + DroneFeedback.fmt(pos))
            : say(source, "Drone #" + d.getId() + " parked at " + DroneFeedback.fmt(pos));
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        int n = 0;
        for (ServerLevel level : c.getSource().getServer().getAllLevels()) {
            for (PatrolDroneEntity d : DroneRegistry.all(level)) {
                say(c.getSource(), status(d.status()));
                n++;
            }
        }
        return n == 0 ? say(c.getSource(), "no drones loaded") : n;
    }

    /** A regular polygon of {@code sides} points around home, looped. */
    private static int patrol(CommandContext<CommandSourceStack> c, int sides) throws CommandSyntaxException {
        PatrolDroneEntity d = drone(c);
        if (d == null) {
            return 0;
        }
        double r = DoubleArgumentType.getDouble(c, "radius");
        Vec3 home = d.home();
        List<Vec3> points = new ArrayList<>();
        for (int i = 0; i < sides; i++) {
            double a = Math.PI * 2 * i / sides + (sides == 4 ? Math.PI / 4 : 0);
            points.add(new Vec3(home.x + r * Math.cos(a), 0, home.z + r * Math.sin(a)));
        }
        int refused = d.setRoute(points, true);
        if (refused == sides) {
            return fail(c.getSource(), "Drone #" + d.getId() + ": radius " + (int) r + " is beyond its range " + (int) d.maxRange());
        }
        return say(c.getSource(), String.format(Locale.ROOT, "Drone #%d: patrolling %d points, radius %.0f%s", d.getId(),
            points.size() - refused, r, refused > 0 ? " (" + refused + " refused)" : ""));
    }

    static String status(DroneStatus s) {
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT,
            "#%d %s at %s home %s hp %d/%d route %d/%d%s range %.0f",
            s.entityId(), s.state(), DroneFeedback.fmt(s.position()), DroneFeedback.fmt(s.home()),
            s.health(), s.maxHealth(), s.routeIndex() + 1, s.routeSize(), s.loop() ? " loop" : "", s.maxRange()));
        if (s.tracked() != null) {
            b.append(" tracking ").append(s.tracked()).append(" at ").append(DroneFeedback.fmt(s.trackedAt()));
        }
        if (!s.controllerKey().isEmpty()) {
            b.append(" controller ").append(s.controllerKey());
        }
        return b.toString();
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> id() {
        return Commands.argument("id", IntegerArgumentType.integer(0));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Double> radius() {
        return Commands.argument("radius", DoubleArgumentType.doubleArg(1, 100000));
    }

    private static @Nullable PatrolDroneEntity drone(CommandContext<CommandSourceStack> c) {
        int id = IntegerArgumentType.getInteger(c, "id");
        for (ServerLevel level : c.getSource().getServer().getAllLevels()) {
            if (level.getEntity(id) instanceof PatrolDroneEntity d && !d.isRemoved()) {
                return d;
            }
        }
        fail(c.getSource(), "no drone #" + id + " in loaded chunks");
        return null;
    }

    private static int say(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
        DroneFeedback.LOGGER.info(line);
        return 1;
    }

    private static int fail(CommandSourceStack source, String line) {
        source.sendFailure(Component.literal(line));
        DroneFeedback.LOGGER.info(line);
        return 0;
    }
}
