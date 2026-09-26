package xyz.przemyk.simpleplanes.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.misc.MathUtil;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /aircraft fighter ...}: test aids for the fighter, usable on any plane. {@code hold <id> <y>|off} is an
 * altitude hold whose gains do not depend on airspeed (the harness's {@code hold} oscillates above about
 * 1.5 b/t); {@code level <id> on|off} holds the wings level through the test roll input.
 * See design/reports/AGENT-2-REPORT.md.
 */
public final class FighterCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-aircraft");

    /** Commanded flight-path angle per block of altitude error, and its limit (deg). */
    private static final double ALT_GAIN = 0.5;
    private static final double GAMMA_LIMIT = 10.0;
    /** Nose above the commanded path per degree of path error. */
    private static final double GAMMA_GAIN = 0.5;
    /** Low-pass on the nose-to-path angle, the level-flight trim estimate. */
    private static final double AOA_FILTER = 0.05;
    private static final double KI = 0.002;
    private static final double I_LIMIT = 3.0;
    private static final double I_BAND = 10.0;
    private static final double PITCH_LIMIT = 20.0;
    private static final double DEADBAND = 0.3;
    /** Roll-rate ramp of PlaneEntity.tickRoll, deg/tick^2, and the leveller's deadband (deg). */
    private static final double ROLL_RAMP = 0.5;
    private static final double ROLL_DEADBAND = 1.0;

    private static final Map<Integer, Hold> HOLDS = new HashMap<>();
    private static final Map<Integer, ServerLevel> LEVELLERS = new HashMap<>();
    private static boolean ticking;

    private FighterCommand() {}

    private static final class Hold {
        final ServerLevel level;
        final double y;
        double aoa = Double.NaN;
        double integral;

        Hold(ServerLevel level, double y) {
            this.level = level;
            this.y = y;
        }
    }

    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        if (!ticking) {
            ticking = true;
            ServerTickEvents.START_LEVEL_TICK.register(FighterCommand::onLevelTickStart);
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
                HOLDS.clear();
                LEVELLERS.clear();
            });
        }
        root.then(Commands.literal("fighter")
            .then(Commands.literal("hold").then(Commands.argument("id", IntegerArgumentType.integer(0))
                .then(Commands.literal("off").executes(c -> hold(c, false)))
                .then(Commands.argument("y", DoubleArgumentType.doubleArg()).executes(c -> hold(c, true)))))
            .then(Commands.literal("level").then(Commands.argument("id", IntegerArgumentType.integer(0))
                .then(Commands.literal("on").executes(c -> level(c, true)))
                .then(Commands.literal("off").executes(c -> level(c, false))))));
    }

    private static @Nullable PlaneEntity plane(CommandContext<CommandSourceStack> context) {
        int id = IntegerArgumentType.getInteger(context, "id");
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            if (level.getEntity(id) instanceof PlaneEntity plane) {
                return plane;
            }
        }
        context.getSource().sendFailure(Component.literal("No plane #" + id));
        return null;
    }

    private static void report(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
        LOGGER.info(line);
    }

    private static int level(CommandContext<CommandSourceStack> context, boolean on) {
        PlaneEntity plane = plane(context);
        if (plane == null) {
            return 0;
        }
        if (on) {
            LEVELLERS.put(plane.getId(), (ServerLevel) plane.level());
        } else {
            LEVELLERS.remove(plane.getId());
            plane.setTestStrafe((byte) 0);
        }
        report(context.getSource(), "Aircraft #" + plane.getId() + " fighter level " + (on ? "on" : "off"));
        return 1;
    }

    private static int hold(CommandContext<CommandSourceStack> context, boolean on) {
        PlaneEntity plane = plane(context);
        if (plane == null) {
            return 0;
        }
        int id = plane.getId();
        String line;
        if (on) {
            double y = DoubleArgumentType.getDouble(context, "y");
            HOLDS.put(id, new Hold((ServerLevel) plane.level(), y));
            line = String.format(Locale.ROOT, "Aircraft #%d fighter hold y %.1f", id, y);
        } else {
            HOLDS.remove(id);
            plane.setPitchUp((byte) 0);
            line = "Aircraft #" + id + " fighter hold off";
        }
        report(context.getSource(), line);
        return 1;
    }

    private static void onLevelTickStart(ServerLevel level) {
        Iterator<Map.Entry<Integer, Hold>> it = HOLDS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Hold> e = it.next();
            Hold hold = e.getValue();
            if (hold.level != level) {
                continue;
            }
            if (!(level.getEntity(e.getKey()) instanceof PlaneEntity plane) || plane.isRemoved()) {
                it.remove();
                continue;
            }
            steer(plane, hold);
        }
        Iterator<Map.Entry<Integer, ServerLevel>> lit = LEVELLERS.entrySet().iterator();
        while (lit.hasNext()) {
            Map.Entry<Integer, ServerLevel> e = lit.next();
            if (e.getValue() != level) {
                continue;
            }
            if (!(level.getEntity(e.getKey()) instanceof PlaneEntity plane) || plane.isRemoved()) {
                lit.remove();
                continue;
            }
            levelWings(plane);
        }
    }

    private static void levelWings(PlaneEntity plane) {
        double roll = Mth.wrapDegrees(plane.rotationRoll);
        double rate = Mth.wrapDegrees(plane.rotationRoll - plane.prevRotationRoll);
        double stopAt = roll + rate * Math.abs(rate) / (2 * ROLL_RAMP);
        plane.setTestStrafe((byte) (Math.abs(stopAt) < ROLL_DEADBAND ? 0 : -Math.signum(stopAt)));
    }

    private static void steer(PlaneEntity plane, Hold hold) {
        Vec3 v = plane.getDeltaMovement();
        if (plane.getOnGround() || v.length() < 0.1) {
            return;
        }
        double gamma = MathUtil.getPitch(v);
        double aoa = plane.getXRot() - gamma;
        hold.aoa = Double.isNaN(hold.aoa) ? aoa : hold.aoa + AOA_FILTER * (aoa - hold.aoa);
        double error = hold.y - plane.getY();
        if (Math.abs(error) < I_BAND) {
            hold.integral = Mth.clamp(hold.integral + KI * error, -I_LIMIT, I_LIMIT);
        }
        double gammaCmd = Mth.clamp(ALT_GAIN * error, -GAMMA_LIMIT, GAMMA_LIMIT);
        double target = Mth.clamp(hold.aoa + gammaCmd + GAMMA_GAIN * (gammaCmd - gamma) + hold.integral,
            -PITCH_LIMIT, PITCH_LIMIT);
        // Bang-bang aimed at where the nose will stop under the pitch-rate ramp.
        double rate = plane.getXRot() - plane.xRotO;
        double brake = 0.5 * plane.autopilotRotationSpeedMultiplier();
        double stopAt = plane.getXRot() + rate * Math.abs(rate) / (2 * brake);
        double pitchError = target - stopAt;
        plane.setPitchUp((byte) (Math.abs(pitchError) < DEADBAND ? 0 : Math.signum(pitchError)));
    }
}
