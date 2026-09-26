package xyz.przemyk.simpleplanes.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.entities.AirshipEntity;
import xyz.przemyk.simpleplanes.entities.MiniHelicopterEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code /airship}: test aids for the airship and, under {@code /airship miniheli}, the mini helicopter.
 * Permission 2, console-friendly; every line also goes to the log at INFO. Syntax: design/reports/AGENT-4-REPORT.md.
 */
public final class AirshipCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-airship");
    private static final double BOARD_RANGE = 8.0;

    /** Traced entity id -> {level, next trace line number, last traced tickCount}. */
    private static final Map<Integer, Trace> TRACES = new HashMap<>();

    private AirshipCommand() {}

    private static final class Trace {
        final ServerLevel level;
        final boolean airship;
        int line;
        int lastTickCount = -1;

        Trace(ServerLevel level, boolean airship) {
            this.level = level;
            this.airship = airship;
        }
    }

    public static void register() {
        ServerTickEvents.END_LEVEL_TICK.register(AirshipCommand::onLevelTickEnd);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> TRACES.clear());

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.<CommandSourceStack>literal("airship")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

            root.then(Commands.literal("status").executes(AirshipCommand::status));
            root.then(Commands.literal("trim").then(Commands.argument("id", IntegerArgumentType.integer(0))
                .then(Commands.argument("value", DoubleArgumentType.doubleArg(-1, 1)).executes(AirshipCommand::trim))));
            root.then(Commands.literal("hold").then(Commands.argument("id", IntegerArgumentType.integer(0))
                .then(Commands.literal("off").executes(AirshipCommand::holdOff))
                .then(Commands.argument("y", DoubleArgumentType.doubleArg()).executes(AirshipCommand::hold))));
            root.then(Commands.literal("trace").then(Commands.argument("id", IntegerArgumentType.integer(0))
                .then(Commands.literal("on").executes(c -> trace(c, true, true)))
                .then(Commands.literal("off").executes(c -> trace(c, true, false)))));
            root.then(Commands.literal("board").then(Commands.argument("id", IntegerArgumentType.integer(0))
                .then(Commands.argument("n", IntegerArgumentType.integer(1, 16)).executes(AirshipCommand::board))));

            root.then(Commands.literal("miniheli")
                .then(Commands.literal("status").executes(AirshipCommand::miniStatus))
                .then(Commands.literal("trace").then(Commands.argument("id", IntegerArgumentType.integer(0))
                    .then(Commands.literal("on").executes(c -> trace(c, false, true)))
                    .then(Commands.literal("off").executes(c -> trace(c, false, false))))));

            dispatcher.register(root);
        });
    }

    // ---- airship ----

    private static int status(CommandContext<CommandSourceStack> context) {
        return listAll(context, AirshipEntity.class, "no airship", ship -> "#" + ship.getId() + " " + airshipLine(ship)
            + " item-has-trim=" + itemHasTrim(ship));
    }

    private static int trim(CommandContext<CommandSourceStack> context) {
        AirshipEntity ship = find(context, AirshipEntity.class, "an airship");
        if (ship == null) {
            return 0;
        }
        ship.setTrim(DoubleArgumentType.getDouble(context, "value"));
        report(context.getSource(), String.format(Locale.ROOT, "Airship #%d trim = %.3f", ship.getId(), ship.getTrim()));
        return 1;
    }

    private static int hold(CommandContext<CommandSourceStack> context) {
        AirshipEntity ship = find(context, AirshipEntity.class, "an airship");
        if (ship == null) {
            return 0;
        }
        ship.setHoldY(DoubleArgumentType.getDouble(context, "y"));
        report(context.getSource(), String.format(Locale.ROOT, "Airship #%d holding y %.2f (elevator centred)", ship.getId(), ship.getHoldY()));
        return 1;
    }

    private static int holdOff(CommandContext<CommandSourceStack> context) {
        AirshipEntity ship = find(context, AirshipEntity.class, "an airship");
        if (ship == null) {
            return 0;
        }
        ship.setFlyByWire(false);
        report(context.getSource(), String.format(Locale.ROOT, "Airship #%d fly-by-wire off, trim frozen at %.3f", ship.getId(), ship.getTrim()));
        return 1;
    }

    private static int board(CommandContext<CommandSourceStack> context) {
        AirshipEntity ship = find(context, AirshipEntity.class, "an airship");
        if (ship == null) {
            return 0;
        }
        int n = IntegerArgumentType.getInteger(context, "n");
        List<Villager> villagers = ship.level().getEntitiesOfClass(Villager.class, ship.getBoundingBox().inflate(BOARD_RANGE),
            v -> v.isAlive() && !v.isPassenger());
        villagers.sort(Comparator.comparingDouble(v -> v.distanceToSqr(ship)));
        int mounted = 0;
        for (Villager villager : villagers) {
            if (mounted >= n) {
                break;
            }
            if (villager.startRiding(ship)) {
                mounted++;
            }
        }
        report(context.getSource(), String.format(Locale.ROOT, "Airship #%d boarded %d of %d villagers in range; riders=%d",
            ship.getId(), mounted, villagers.size(), ship.getPassengers().size()));
        return mounted;
    }

    private static String airshipLine(AirshipEntity ship) {
        Vec3 v = ship.position().subtract(ship.xo, ship.yo, ship.zo);
        double vh = Math.sqrt(v.x * v.x + v.z * v.z);
        return String.format(Locale.ROOT,
            "pos=%.2f,%.2f,%.2f vh=%.4f vs=%.4f hdg=%.2f trk=%.2f pitch=%.2f roll=%.2f trim=%.3f trimInt=%.3f holdY=%.2f"
                + " riders=%d thr=%d elev=%d rud=%d og=%b agl=%.2f vsCmd=%.3f cap=%b blocked=%b fbw=%b health=%d",
            ship.getX(), ship.getY(), ship.getZ(), vh, v.y, heading(ship.getYRot()), track(v), ship.getXRot(), ship.rotationRoll,
            ship.getTrim(), ship.getTrimIntegral(), ship.getHoldY(), ship.getPassengers().size(), ship.getThrottle(),
            ship.getPitchUp(), ship.getYawRight(), ship.getOnGround(), agl(ship), ship.getVsCommand(), ship.isCapturing(),
            ship.isEngineBlocked(), ship.isFlyByWire(), ship.getHealth());
    }

    private static boolean itemHasTrim(AirshipEntity ship) {
        ItemStack stack = ship.getItemStack();
        CompoundTag tag = stack.get(SimplePlanesComponents.ENTITY_TAG.get());
        return tag != null && (tag.contains("trim") || tag.contains("trim_int") || tag.contains("hold_y") || tag.contains("throttle"));
    }

    // ---- mini helicopter ----

    private static int miniStatus(CommandContext<CommandSourceStack> context) {
        return listAll(context, MiniHelicopterEntity.class, "no mini helicopter", heli -> "#" + heli.getId() + " " + miniLine(heli));
    }

    private static String miniLine(MiniHelicopterEntity heli) {
        Vec3 v = heli.position().subtract(heli.xo, heli.yo, heli.zo);
        double vh = Math.sqrt(v.x * v.x + v.z * v.z);
        return String.format(Locale.ROOT,
            "pos=%.2f,%.2f,%.2f vh=%.4f vs=%.4f hdg=%.2f pitch=%.2f roll=%.2f thr=%d boost=%b cyc=%d,%d ped=%d livery=%s"
                + " riders=%d og=%b agl=%.2f health=%d",
            heli.getX(), heli.getY(), heli.getZ(), vh, v.y, heading(heli.getYRot()), heli.getXRot(), heli.rotationRoll,
            heli.getThrottle(), heli.getCollectiveBoost(), heli.getCyclicForward(), heli.getCyclicRight(), heli.getPedal(),
            heli.hasMedicalLivery() ? "medical" : "standard", heli.getPassengers().size(), heli.getOnGround(), agl(heli),
            heli.getHealth());
    }

    // ---- trace ----

    private static int trace(CommandContext<CommandSourceStack> context, boolean airship, boolean on) {
        Entity entity = airship ? find(context, AirshipEntity.class, "an airship") : find(context, MiniHelicopterEntity.class, "a mini helicopter");
        if (entity == null) {
            return 0;
        }
        if (on) {
            TRACES.put(entity.getId(), new Trace((ServerLevel) entity.level(), airship));
        } else {
            TRACES.remove(entity.getId());
        }
        report(context.getSource(), (airship ? "Airship #" : "Mini helicopter #") + entity.getId() + " trace " + (on ? "on" : "off"));
        return 1;
    }

    private static void onLevelTickEnd(ServerLevel level) {
        if (TRACES.isEmpty()) {
            return;
        }
        TRACES.entrySet().removeIf(e -> {
            Trace trace = e.getValue();
            if (trace.level != level) {
                return false;
            }
            Entity entity = level.getEntity(e.getKey());
            if (entity == null || entity.isRemoved()) {
                return true;
            }
            // Only ticks the entity actually ran.
            if (entity.tickCount != trace.lastTickCount) {
                trace.lastTickCount = entity.tickCount;
                String fields = entity instanceof AirshipEntity ship ? airshipLine(ship)
                    : entity instanceof MiniHelicopterEntity heli ? miniLine(heli) : "";
                LOGGER.info(String.format(Locale.ROOT, "trace %s #%d t=%d %s", trace.airship ? "airship" : "miniheli",
                    entity.getId(), trace.line++, fields));
            }
            return false;
        });
    }

    // ---- helpers ----

    private static <T extends Entity> int listAll(CommandContext<CommandSourceStack> context, Class<T> type, String none,
                                                  Function<T, String> line) {
        List<T> found = new ArrayList<>();
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (type.isInstance(entity) && entity.isAlive()) {
                    found.add(type.cast(entity));
                }
            }
        }
        if (found.isEmpty()) {
            report(context.getSource(), none);
            return 0;
        }
        found.sort(Comparator.comparingInt(Entity::getId));
        for (T entity : found) {
            report(context.getSource(), line.apply(entity));
        }
        return found.size();
    }

    private static <T extends Entity> @Nullable T find(CommandContext<CommandSourceStack> context, Class<T> type, String what) {
        int id = IntegerArgumentType.getInteger(context, "id");
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (type.isInstance(entity)) {
                return type.cast(entity);
            }
            if (entity != null) {
                fail(context.getSource(), "#" + id + " is not " + what);
                return null;
            }
        }
        fail(context.getSource(), "No entity #" + id);
        return null;
    }

    private static double agl(Entity entity) {
        return entity.getY() - entity.level().getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(entity.getX()), Mth.floor(entity.getZ()));
    }

    private static double heading(float yRot) {
        double h = yRot % 360.0;
        return h < 0 ? h + 360.0 : h;
    }

    private static double track(Vec3 v) {
        if (v.x * v.x + v.z * v.z < 1.0E-10) {
            return Double.NaN;
        }
        double h = Math.toDegrees(Math.atan2(-v.x, v.z));
        return h < 0 ? h + 360.0 : h;
    }

    private static void report(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
        LOGGER.info(line);
    }

    private static void fail(CommandSourceStack source, String line) {
        source.sendFailure(Component.literal(line));
        LOGGER.info(line);
    }
}
