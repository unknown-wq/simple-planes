package xyz.przemyk.simpleplanes.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.entities.AirlinerEntity;
import xyz.przemyk.simpleplanes.entities.HelicopterEntity;
import xyz.przemyk.simpleplanes.entities.MiniHelicopterEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.entities.QuadcopterEntity;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.upgrades.engines.furnace.FurnaceEngineUpgrade;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code /aircraft}: headless test harness for every airframe. Permission 2, works from the console; every
 * reported line also goes to the log at INFO. See design/reports/AGENT-1-REPORT.md for the syntax.
 */
public final class AircraftCommand {

    public static final String TAG = "aircraft-test";
    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-aircraft");
    private static final boolean TRACE_ALL = Boolean.getBoolean("simpleplanes.aircraft.trace");

    private static final int TICKET_RADIUS = 3;
    /** Around a moving test aircraft: the autopilot's radius and lead (AutopilotConfig.CHUNK_TICKET_*). */
    private static final int FLIGHT_TICKET_RADIUS = 4;
    private static final int FLIGHT_TICKET_LEAD_TICKS = 40;
    private static final int SPAWN_TICKET_TICKS = 400;
    private static final int COAL = 64;
    private static final double HOLD_DEADBAND = 0.3;
    /** Integral trim of the altitude hold: gain (deg per block-tick), limit (deg), active band (blocks). */
    private static final double HOLD_KI = 0.004;
    private static final double HOLD_I_LIMIT = 5.0;
    private static final double HOLD_I_BAND = 5.0;

    private static final Map<String, Supplier<? extends EntityType<?>>> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("plane", SimplePlanesEntities.PLANE);
        TYPES.put("large", SimplePlanesEntities.LARGE_PLANE);
        TYPES.put("cargo", SimplePlanesEntities.CARGO_PLANE);
        TYPES.put("helicopter", SimplePlanesEntities.HELICOPTER);
        TYPES.put("fighter", SimplePlanesEntities.FIGHTER);
        TYPES.put("airliner", SimplePlanesEntities.AIRLINER);
        TYPES.put("regional_airliner", SimplePlanesEntities.REGIONAL_AIRLINER);
        TYPES.put("airship", SimplePlanesEntities.AIRSHIP);
        TYPES.put("mini_helicopter", SimplePlanesEntities.MINI_HELICOPTER);
        TYPES.put("quadcopter", SimplePlanesEntities.QUADCOPTER);
    }

    /** Per-aircraft harness state, keyed by entity id. */
    private static final Map<Integer, Control> CONTROLS = new LinkedHashMap<>();
    /** Spawn points kept loaded for a while after a spawn. */
    private static final List<SpawnTicket> SPAWN_TICKETS = new ArrayList<>();

    private AircraftCommand() {}

    private static final class Control {
        final ServerLevel level;
        final int id;
        @Nullable Double holdY;
        double holdIntegral;
        @Nullable Double trimPitch;
        @Nullable Takeoff takeoff;
        boolean trace;
        int traceTick;
        int lastTracedTickCount = -1;

        Control(ServerLevel level, int id) {
            this.level = level;
            this.id = id;
        }
    }

    private static final class Takeoff {
        final CommandSourceStack source;
        final Vec3 start;
        final double takeOffSpeed;
        final int startTickCount;
        int ticks;
        boolean rotating;
        double rotationDistance;
        int rotationTicks;

        Takeoff(CommandSourceStack source, Vec3 start, double takeOffSpeed, int startTickCount) {
            this.source = source;
            this.startTickCount = startTickCount;
            this.start = start;
            this.takeOffSpeed = takeOffSpeed;
        }
    }

    private record SpawnTicket(ServerLevel level, ChunkPos pos, long until) {}

    public static void register() {
        ServerTickEvents.START_LEVEL_TICK.register(AircraftCommand::onLevelTickStart);
        ServerTickEvents.END_LEVEL_TICK.register(AircraftCommand::onLevelTickEnd);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            CONTROLS.clear();
            SPAWN_TICKETS.clear();
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("aircraft")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

            root.then(Commands.literal("spawn")
                .then(Commands.argument("type", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(TYPES.keySet(), b))
                    .then(Commands.argument("pos", Vec3Argument.vec3(false))
                        .executes(AircraftCommand::spawn)
                        .then(Commands.argument("heading", FloatArgumentType.floatArg(-360, 360))
                            .executes(AircraftCommand::spawn)))));

            RequiredArgumentBuilder<CommandSourceStack, Integer> setId = Commands.argument("id", IntegerArgumentType.integer(0));
            setId.then(Commands.literal("throttle").then(Commands.argument("value", IntegerArgumentType.integer(0, 10))
                .executes(c -> setControl(c, "throttle"))));
            for (String axis : new String[]{"pitch", "yaw", "roll"}) {
                setId.then(Commands.literal(axis).then(Commands.argument("value", IntegerArgumentType.integer(-1, 1))
                    .executes(c -> setControl(c, axis))));
            }
            setId.then(Commands.literal("cyclic").then(Commands.argument("fwd", IntegerArgumentType.integer(-100, 100))
                .then(Commands.argument("right", IntegerArgumentType.integer(-100, 100))
                    .executes(AircraftCommand::setCyclic))));
            setId.then(Commands.literal("boost")
                .then(Commands.literal("on").executes(c -> setBoost(c, true)))
                .then(Commands.literal("off").executes(c -> setBoost(c, false))));
            root.then(Commands.literal("set").then(setId));

            root.then(Commands.literal("launch").then(Commands.argument("id", IntegerArgumentType.integer(0))
                .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0, 5))
                    .executes(AircraftCommand::launch)
                    .then(Commands.argument("pitch", DoubleArgumentType.doubleArg(-90, 90))
                        .executes(AircraftCommand::launch)))));

            root.then(Commands.literal("hold").then(Commands.argument("id", IntegerArgumentType.integer(0))
                .then(Commands.literal("off").executes(c -> hold(c, false)))
                .then(Commands.argument("y", DoubleArgumentType.doubleArg()).executes(c -> hold(c, true)))));

            root.then(Commands.literal("trim").then(Commands.argument("id", IntegerArgumentType.integer(0))
                .then(Commands.literal("off").executes(c -> trim(c, false)))
                .then(Commands.argument("pitch", DoubleArgumentType.doubleArg(-90, 90)).executes(c -> trim(c, true)))));

            root.then(Commands.literal("takeoff").then(Commands.argument("id", IntegerArgumentType.integer(0))
                .executes(AircraftCommand::takeoff)));

            root.then(Commands.literal("status")
                .executes(AircraftCommand::statusAll)
                .then(Commands.argument("id", IntegerArgumentType.integer(0)).executes(AircraftCommand::statusOne)));

            root.then(Commands.literal("trace").then(Commands.argument("id", IntegerArgumentType.integer(0))
                .then(Commands.literal("on").executes(c -> trace(c, true)))
                .then(Commands.literal("off").executes(c -> trace(c, false)))));

            root.then(Commands.literal("kill").executes(AircraftCommand::kill));
            FighterCommand.register(root);

            dispatcher.register(root);
        });
    }

    // ---- subcommands ----

    private static int spawn(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String typeName = StringArgumentType.getString(context, "type");
        Supplier<? extends EntityType<?>> type = TYPES.get(typeName);
        if (type == null) {
            return fail(source, "Unknown aircraft type '" + typeName + "'; one of " + String.join(" ", TYPES.keySet()));
        }
        Vec3 pos = Vec3Argument.getVec3(context, "pos");
        float heading = has(context, "heading") ? FloatArgumentType.getFloat(context, "heading") : 0.0F;

        ChunkPos chunk = ChunkPos.containing(net.minecraft.core.BlockPos.containing(pos));
        loadChunks(level, chunk);
        SPAWN_TICKETS.add(new SpawnTicket(level, chunk, level.getGameTime() + SPAWN_TICKET_TICKS));

        Entity entity = type.get().create(level, EntitySpawnReason.COMMAND);
        if (entity == null) {
            return fail(source, "Could not create " + typeName);
        }
        entity.snapTo(pos.x, pos.y, pos.z, heading, 0.0F);
        entity.setYRot(heading);
        entity.yRotO = heading;
        if (entity instanceof PlaneEntity plane) {
            plane.setQ(MathUtil.toQuaternionf(heading, 0, 0));
            plane.setQ_Client(MathUtil.toQuaternionf(heading, 0, 0));
            plane.setQ_prev(MathUtil.toQuaternionf(heading, 0, 0));
            FurnaceEngineUpgrade engine = new FurnaceEngineUpgrade(plane);
            plane.addUpgradeUsingWrench(SimplePlanesItems.FURNACE_ENGINE.get().getDefaultInstance(), engine);
            engine.container.setItem(0, new ItemStack(Items.COAL, COAL));
        } else if (entity instanceof QuadcopterEntity quad) {
            quad.setQ(MathUtil.toQuaternionf(heading, 0, 0));
            quad.setQ_Client(MathUtil.toQuaternionf(heading, 0, 0));
            quad.setQ_prev(MathUtil.toQuaternionf(heading, 0, 0));
        }
        entity.addTag(TAG);
        if (!level.addFreshEntity(entity)) {
            return fail(source, "Could not add " + typeName + " to the level");
        }
        Control control = control(level, entity.getId());
        control.trace = TRACE_ALL;

        report(source, String.format(Locale.ROOT, "Aircraft #%d %s spawned at %.1f, %.1f, %.1f heading %.1f",
            entity.getId(), typeName, pos.x, pos.y, pos.z, heading));
        return entity.getId();
    }

    private static int setControl(CommandContext<CommandSourceStack> context, String what) {
        PlaneEntity plane = plane(context);
        if (plane == null) {
            return 0;
        }
        int value = IntegerArgumentType.getInteger(context, "value");
        switch (what) {
            case "throttle" -> plane.setThrottle(value);
            case "pitch" -> plane.setPitchUp((byte) value);
            case "yaw" -> plane.setYawRight((byte) value);
            case "roll" -> plane.setTestStrafe((byte) value);
            default -> throw new IllegalStateException(what);
        }
        report(context.getSource(), "Aircraft #" + plane.getId() + " " + what + " = " + value);
        return 1;
    }

    private static int setCyclic(CommandContext<CommandSourceStack> context) {
        PlaneEntity plane = plane(context);
        if (plane == null) {
            return 0;
        }
        if (!(plane instanceof HelicopterEntity heli)) {
            return fail(context.getSource(), "Aircraft #" + plane.getId() + " is not a helicopter");
        }
        int fwd = IntegerArgumentType.getInteger(context, "fwd");
        int right = IntegerArgumentType.getInteger(context, "right");
        heli.setCyclicForward(fwd);
        heli.setCyclicRight(right);
        report(context.getSource(), "Aircraft #" + plane.getId() + " cyclic = " + fwd + " fwd, " + right + " right");
        return 1;
    }

    private static int setBoost(CommandContext<CommandSourceStack> context, boolean on) {
        PlaneEntity plane = plane(context);
        if (plane == null) {
            return 0;
        }
        if (!(plane instanceof HelicopterEntity heli)) {
            return fail(context.getSource(), "Aircraft #" + plane.getId() + " is not a helicopter");
        }
        heli.setCollectiveBoost(on);
        report(context.getSource(), "Aircraft #" + plane.getId() + " boost " + (on ? "on" : "off"));
        return 1;
    }

    private static int launch(CommandContext<CommandSourceStack> context) {
        Entity entity = entity(context);
        if (entity == null) {
            return 0;
        }
        double speed = DoubleArgumentType.getDouble(context, "speed");
        double pitch = has(context, "pitch") ? DoubleArgumentType.getDouble(context, "pitch") : 0.0;
        entity.setDeltaMovement(MathUtil.rotationToVector(entity.getYRot(), pitch, speed));
        report(context.getSource(), String.format(Locale.ROOT, "Aircraft #%d launched at %.3f b/t pitch %.1f",
            entity.getId(), speed, pitch));
        return 1;
    }

    private static int hold(CommandContext<CommandSourceStack> context, boolean on) {
        PlaneEntity plane = plane(context);
        if (plane == null) {
            return 0;
        }
        Control control = control((ServerLevel) plane.level(), plane.getId());
        control.trimPitch = null;
        control.takeoff = null;
        control.holdIntegral = 0;
        if (on) {
            control.holdY = DoubleArgumentType.getDouble(context, "y");
            report(context.getSource(), String.format(Locale.ROOT, "Aircraft #%d holding y %.1f", plane.getId(), control.holdY));
        } else {
            control.holdY = null;
            plane.setPitchUp((byte) 0);
            report(context.getSource(), "Aircraft #" + plane.getId() + " hold off");
        }
        return 1;
    }

    private static int trim(CommandContext<CommandSourceStack> context, boolean on) {
        PlaneEntity plane = plane(context);
        if (plane == null) {
            return 0;
        }
        Control control = control((ServerLevel) plane.level(), plane.getId());
        control.holdY = null;
        control.takeoff = null;
        if (on) {
            control.trimPitch = DoubleArgumentType.getDouble(context, "pitch");
            report(context.getSource(), String.format(Locale.ROOT, "Aircraft #%d trimmed to pitch %.1f", plane.getId(), control.trimPitch));
        } else {
            control.trimPitch = null;
            plane.setPitchUp((byte) 0);
            report(context.getSource(), "Aircraft #" + plane.getId() + " trim off");
        }
        return 1;
    }

    private static int takeoff(CommandContext<CommandSourceStack> context) {
        PlaneEntity plane = plane(context);
        if (plane == null) {
            return 0;
        }
        Control control = control((ServerLevel) plane.level(), plane.getId());
        control.holdY = null;
        control.trimPitch = null;
        control.takeoff = new Takeoff(context.getSource(), plane.position(), plane.testTakeOffSpeed(), plane.tickCount);
        plane.setThrottle(5);
        plane.setPitchUp((byte) 0);
        report(context.getSource(), String.format(Locale.ROOT, "Aircraft #%d take-off roll, rotating at %.3f b/t",
            plane.getId(), control.takeoff.takeOffSpeed));
        return 1;
    }

    private static int statusAll(CommandContext<CommandSourceStack> context) {
        List<Entity> found = new ArrayList<>();
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.entityTags().contains(TAG) && entity.isAlive()) {
                    found.add(entity);
                }
            }
        }
        if (found.isEmpty()) {
            report(context.getSource(), "no test aircraft");
            return 0;
        }
        found.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        for (Entity entity : found) {
            report(context.getSource(), statusLine(entity));
        }
        return found.size();
    }

    private static int statusOne(CommandContext<CommandSourceStack> context) {
        Entity entity = entity(context);
        if (entity == null) {
            return 0;
        }
        report(context.getSource(), statusLine(entity));
        return 1;
    }

    private static int trace(CommandContext<CommandSourceStack> context, boolean on) {
        Entity entity = entity(context);
        if (entity == null) {
            return 0;
        }
        Control control = control((ServerLevel) entity.level(), entity.getId());
        control.trace = on;
        control.traceTick = 0;
        report(context.getSource(), "Aircraft #" + entity.getId() + " trace " + (on ? "on" : "off"));
        return 1;
    }

    private static int kill(CommandContext<CommandSourceStack> context) {
        int count = 0;
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            List<Entity> doomed = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity.entityTags().contains(TAG)) {
                    doomed.add(entity);
                }
            }
            for (Entity entity : doomed) {
                entity.ejectPassengers();
                entity.discard();
                count++;
            }
        }
        CONTROLS.clear();
        SPAWN_TICKETS.clear();
        report(context.getSource(), "Removed " + count + " test aircraft");
        return count;
    }

    // ---- tick ----

    private static void onLevelTickStart(ServerLevel level) {
        if (CONTROLS.isEmpty() && SPAWN_TICKETS.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        SPAWN_TICKETS.removeIf(t -> t.level() == level && now > t.until());
        for (SpawnTicket ticket : SPAWN_TICKETS) {
            if (ticket.level() == level) {
                level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, ticket.pos(), TICKET_RADIUS);
            }
        }

        Iterator<Control> it = CONTROLS.values().iterator();
        while (it.hasNext()) {
            Control control = it.next();
            if (control.level != level) {
                continue;
            }
            Entity entity = level.getEntity(control.id);
            if (entity == null || entity.isRemoved()) {
                it.remove();
                continue;
            }
            keepLoaded(level, entity);
            if (entity instanceof PlaneEntity plane) {
                pilot(plane, control);
            }
        }
    }

    private static void keepLoaded(ServerLevel level, Entity entity) {
        ChunkPos here = ChunkPos.containing(entity.blockPosition());
        level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, here, FLIGHT_TICKET_RADIUS);
        Vec3 ahead = entity.position().add(entity.getDeltaMovement().scale(FLIGHT_TICKET_LEAD_TICKS));
        ChunkPos next = ChunkPos.containing(net.minecraft.core.BlockPos.containing(ahead));
        if (!next.equals(here)) {
            level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, next, FLIGHT_TICKET_RADIUS);
        }
    }

    private static void onLevelTickEnd(ServerLevel level) {
        if (CONTROLS.isEmpty()) {
            return;
        }
        for (Control control : CONTROLS.values()) {
            if (control.level != level || !control.trace) {
                continue;
            }
            Entity entity = level.getEntity(control.id);
            // Only ticks the entity actually ran: one outside the entity-ticking area does not move.
            if (entity != null && !entity.isRemoved() && entity.tickCount != control.lastTracedTickCount) {
                control.lastTracedTickCount = entity.tickCount;
                LOGGER.info(String.format(Locale.ROOT, "trace #%d t=%d %s", entity.getId(), control.traceTick++, telemetry(entity)));
            }
        }
    }

    private static void pilot(PlaneEntity plane, Control control) {
        Takeoff takeoff = control.takeoff;
        if (takeoff != null) {
            takeoff.ticks = plane.tickCount - takeoff.startTickCount;
            double distance = horizontalDistance(plane.position(), takeoff.start);
            double speed = plane.getDeltaMovement().length();
            if (!takeoff.rotating && speed >= takeoff.takeOffSpeed) {
                takeoff.rotating = true;
                takeoff.rotationDistance = distance;
                takeoff.rotationTicks = takeoff.ticks;
                plane.setPitchUp((byte) 1);
            } else if (takeoff.rotating && plane.getY() - takeoff.start.y > 1.0) {
                String line = String.format(Locale.ROOT,
                    "Aircraft #%d rotation at %.1f blocks (%d ticks), airborne at %.1f blocks (%d ticks) at %.2f b/t pitch %.1f",
                    plane.getId(), takeoff.rotationDistance, takeoff.rotationTicks, distance, takeoff.ticks, speed, plane.getXRot());
                report(takeoff.source, line);
                control.takeoff = null;
                control.holdY = takeoff.start.y + 30;
                control.holdIntegral = 0;
            }
            if (control.takeoff != null) {
                return;
            }
        }
        if (control.holdY != null) {
            double error = control.holdY - plane.getY();
            if (Math.abs(error) < HOLD_I_BAND) {
                control.holdIntegral = Mth.clamp(control.holdIntegral + HOLD_KI * error, -HOLD_I_LIMIT, HOLD_I_LIMIT);
            }
            double target = Mth.clamp(0.5 * error - 60 * plane.getDeltaMovement().y + control.holdIntegral, -15, 15);
            steerPitch(plane, target);
        } else if (control.trimPitch != null) {
            steerPitch(plane, control.trimPitch);
        }
    }

    /**
     * Bang-bang on the pitch input, aimed at where the nose will stop: the pitch rate ramps by
     * 0.5 * multiplier deg/tick^2, so steering on the present angle alone overshoots by tens of degrees.
     */
    private static void steerPitch(PlaneEntity plane, double targetPitch) {
        double rate = plane.getXRot() - plane.xRotO;
        double brake = 0.5 * plane.autopilotRotationSpeedMultiplier();
        double stopAt = plane.getXRot() + rate * Math.abs(rate) / (2 * brake);
        double error = targetPitch - stopAt;
        plane.setPitchUp((byte) (Math.abs(error) < HOLD_DEADBAND ? 0 : Math.signum(error)));
    }

    // ---- reporting ----

    private static String statusLine(Entity entity) {
        return "#" + entity.getId() + " " + typeName(entity) + " " + telemetry(entity) + extras(entity);
    }

    private static String telemetry(Entity entity) {
        // Measured displacement over the last tick. A parked plane moves only every fourth tick and its
        // deltaMovement accumulates gravity in between, so deltaMovement would read as motion.
        Vec3 v = entity.position().subtract(entity.xo, entity.yo, entity.zo);
        double ground = entity.level().getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(entity.getX()), Mth.floor(entity.getZ()));
        if (entity instanceof PlaneEntity plane) {
            return String.format(Locale.ROOT,
                "pos=%.2f,%.2f,%.2f spd=%.3f vs=%.3f hdg=%.1f pitch=%.1f roll=%.1f thr=%d og=%b agl=%.2f",
                plane.getX(), plane.getY(), plane.getZ(), v.length(), v.y, heading(plane.getYRot()), plane.getXRot(),
                plane.rotationRoll, plane.getThrottle(), plane.getOnGround(), plane.getY() - ground);
        }
        return String.format(Locale.ROOT, "pos=%.2f,%.2f,%.2f spd=%.3f vs=%.3f hdg=%.1f og=%b agl=%.2f",
            entity.getX(), entity.getY(), entity.getZ(), v.length(), v.y, heading(entity.getYRot()), entity.onGround(),
            entity.getY() - ground);
    }

    private static String extras(Entity entity) {
        if (entity instanceof AirlinerEntity airliner) {
            return " skin=" + (airliner.hasMetalSkin() ? "metal" : "wood") + " logo=" + airliner.getLogo();
        }
        if (entity instanceof MiniHelicopterEntity heli) {
            return " livery=" + (heli.hasMedicalLivery() ? "medical" : "standard");
        }
        if (entity instanceof QuadcopterEntity quad) {
            return String.format(Locale.ROOT, " state=hover L=%.2f carrying=%b health=%d", quad.getRopeLength(), quad.isCarrying(), quad.getHealth());
        }
        return "";
    }

    private static String typeName(Entity entity) {
        for (Map.Entry<String, Supplier<? extends EntityType<?>>> e : TYPES.entrySet()) {
            if (e.getValue().get() == entity.getType()) {
                return e.getKey();
            }
        }
        return EntityType.getKey(entity.getType()).toString();
    }

    private static double heading(float yRot) {
        double h = yRot % 360.0;
        return h < 0 ? h + 360.0 : h;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void report(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
        LOGGER.info(line);
    }

    private static int fail(CommandSourceStack source, String line) {
        source.sendFailure(Component.literal(line));
        LOGGER.info(line);
        return 0;
    }

    // ---- lookup ----

    private static Control control(ServerLevel level, int id) {
        return CONTROLS.computeIfAbsent(id, k -> new Control(level, id));
    }

    private static void loadChunks(ServerLevel level, ChunkPos center) {
        level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, center, TICKET_RADIUS);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.getChunk(center.x() + dx, center.z() + dz);
            }
        }
    }

    private static @Nullable Entity entity(CommandContext<CommandSourceStack> context) {
        int id = IntegerArgumentType.getInteger(context, "id");
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        fail(context.getSource(), "No entity #" + id);
        return null;
    }

    private static @Nullable PlaneEntity plane(CommandContext<CommandSourceStack> context) {
        Entity entity = entity(context);
        if (entity == null) {
            return null;
        }
        if (entity instanceof PlaneEntity plane) {
            return plane;
        }
        fail(context.getSource(), "Aircraft #" + entity.getId() + " is not a plane");
        return null;
    }

    private static boolean has(CommandContext<CommandSourceStack> context, String name) {
        for (ParsedCommandNode<CommandSourceStack> node : context.getNodes()) {
            if (node.getNode().getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
