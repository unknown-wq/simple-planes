package xyz.przemyk.simpleplanes.aviation;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.api.map.AviationMap;
import xyz.przemyk.simpleplanes.api.map.AviationSnapshot;
import xyz.przemyk.simpleplanes.api.map.LaunchResult;
import xyz.przemyk.simpleplanes.api.map.SiloAction;

import java.util.Locale;

/**
 * {@code /aviation}: diagnostics for the silo index and the map's snapshot, and a fake-player driver for the
 * map's launch, load and unload handlers. Permission level 2, console-friendly. Documented in {@code MISSILES.md}.
 */
final class AviationCommand {

    private AviationCommand() {}

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("aviation")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

            root.then(Commands.literal("index")
                .executes(AviationCommand::index)
                .then(Commands.literal("sweep").executes(AviationCommand::sweep)));

            root.then(Commands.literal("snapshot")
                .executes(c -> snapshot(c, c.getSource().getPlayerOrException()))
                .then(Commands.argument("at", Vec3Argument.vec3())
                    .executes(c -> snapshot(c, fake(c, true)))
                    .then(Commands.literal("op").executes(c -> snapshot(c, fake(c, true))))
                    .then(Commands.literal("nonop").executes(c -> snapshot(c, fake(c, false))))));

            RequiredArgumentBuilder<CommandSourceStack, Integer> tz = Commands.argument("tz", IntegerArgumentType.integer())
                .executes(c -> launch(c, true, AviationMap.SURFACE));
            for (String who : new String[]{"op", "nonop"}) {
                boolean op = who.equals("op");
                tz.then(Commands.literal(who)
                    .executes(c -> launch(c, op, AviationMap.SURFACE))
                    .then(Commands.argument("y", IntegerArgumentType.integer())
                        .executes(c -> launch(c, op, IntegerArgumentType.getInteger(c, "y")))));
            }
            root.then(Commands.literal("test")
                .then(Commands.literal("launch")
                    .then(Commands.argument("at", Vec3Argument.vec3())
                        .then(Commands.argument("silo", BlockPosArgument.blockPos())
                            .then(Commands.argument("tx", IntegerArgumentType.integer())
                                .then(tz)))))
                .then(service(SiloAction.LOAD))
                .then(service(SiloAction.UNLOAD))
                .then(Commands.literal("resetlimits").executes(c -> {
                    AviationService.resetLimits();
                    return ok(c, "Aviation rate limits cleared.");
                })));

            dispatcher.register(root);
        });
    }

    /** {@code load|unload <at> <silo> [op|nonop]}. */
    private static LiteralArgumentBuilder<CommandSourceStack> service(SiloAction action) {
        return Commands.literal(action.name().toLowerCase(Locale.ROOT))
            .then(Commands.argument("at", Vec3Argument.vec3())
                .then(Commands.argument("silo", BlockPosArgument.blockPos())
                    .executes(c -> service(c, action, true))
                    .then(Commands.literal("op").executes(c -> service(c, action, true)))
                    .then(Commands.literal("nonop").executes(c -> service(c, action, false)))));
    }

    private static AviationTestPlayer fake(CommandContext<CommandSourceStack> c, boolean op) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 at = Vec3Argument.getVec3(c, "at");
        AviationTestPlayer player = new AviationTestPlayer(level, op);
        player.setPos(at.x, at.y, at.z);
        return player;
    }

    private static int index(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        SiloIndex index = SiloIndex.peek(level);
        if (index == null || index.size() == 0) return ok(c, "Silo index for " + level.dimension().identifier() + ": empty.");
        ok(c, "Silo index for " + level.dimension().identifier() + ": " + index.size() + " silo(s).");
        for (SiloIndex.Entry e : index.entries()) {
            ok(c, String.format(Locale.ROOT, "  %s T%d %s %s seen=%d chunk=%s", e.pos().toShortString(), e.tier(),
                e.strike() ? "strike" : "air_defence", e.loaded() ? "loaded" : "empty", e.seen(),
                level.isLoaded(e.pos()) ? "loaded" : "unloaded"));
        }
        return index.size();
    }

    private static int sweep(CommandContext<CommandSourceStack> c) {
        int dropped = AviationService.sweep(c.getSource().getLevel());
        return ok(c, "Silo index swept: " + dropped + " stale entr" + (dropped == 1 ? "y" : "ies") + " dropped.");
    }

    private static int snapshot(CommandContext<CommandSourceStack> c, ServerPlayer player) {
        AviationSnapshot s = AviationService.snapshot(player);
        ok(c, String.format(Locale.ROOT, "Snapshot for %s at %.1f %.1f %.1f in %s: permitted=%s near=%d radius=%d "
                + "airfields=%d helipads=%d routes=%d flights=%d silos=%d", player.getName().getString(), player.getX(),
            player.getY(), player.getZ(), s.dimension(), s.launchPermitted(), s.nearRadius(), s.snapshotRadius(),
            s.airfields().size(), s.helipads().size(), s.routes().size(), s.flights().size(), s.silos().size()));
        for (AviationSnapshot.Airfield a : s.airfields()) {
            ok(c, String.format(Locale.ROOT, "  airfield %s %s/%s %s -> %s w=%d%s", a.name(), a.designatorA(), a.designatorB(),
                a.thresholdA().toShortString(), a.thresholdB().toShortString(), a.width(), a.usable() ? "" : " (too short)"));
        }
        for (AviationSnapshot.Route r : s.routes()) {
            ok(c, String.format(Locale.ROOT, "  route #%d %s <-> %s %s %s%s legs=%d", r.id(), r.fieldA(), r.fieldB(), r.state(),
                r.aircraftType(), r.hostile() ? " hostile" : "", r.legs()));
        }
        for (AviationSnapshot.Flight f : s.flights()) {
            ok(c, String.format(Locale.ROOT, "  flight #%d %s %s %s at %.0f %.0f %.0f -> %s", f.entityId(), f.aircraftType(), f.kind(),
                f.mode(), f.x(), f.y(), f.z(), f.hasDestination() ? String.format(Locale.ROOT, "%.0f %.0f %s", f.destX(), f.destZ(), f.destination()) : "-"));
        }
        for (AviationSnapshot.Silo o : s.silos()) {
            ok(c, String.format(Locale.ROOT, "  silo %s T%d %s %s %s chunk=%s dist=%.1f range=%d-%d ad=%.0f/%.0f "
                    + "usable=%s status=%s serviceable=%s service=%s",
                o.pos().toShortString(), o.tier(), o.strike() ? "strike" : "air_defence", o.loaded() ? "loaded" : "empty",
                o.phase(), o.chunkLoaded() ? "loaded" : "unloaded", o.distance(), o.minRange(), o.maxRange(),
                o.detectionRadius(), o.engagementRange(), o.usable(), o.status().getString(), o.serviceable(),
                o.serviceStatus().getString()));
        }
        return s.silos().size();
    }

    private static int launch(CommandContext<CommandSourceStack> c, boolean op, int y) {
        AviationTestPlayer player = fake(c, op);
        BlockPos silo = BlockPosArgument.getBlockPos(c, "silo");
        int tx = IntegerArgumentType.getInteger(c, "tx");
        int tz = IntegerArgumentType.getInteger(c, "tz");
        LaunchResult result = AviationService.handleLaunch(player, new AviationPayloads.LaunchRequest(silo, tx, y, tz));
        String line = String.format(Locale.ROOT, "Test launch as %s from %.1f %.1f %.1f, silo %s, target %d %s %d: %s -- %s",
            op ? "operator" : "non-operator", player.getX(), player.getY(), player.getZ(), silo.toShortString(), tx,
            y == AviationMap.SURFACE ? "surface" : Integer.toString(y), tz,
            result.accepted() ? "ACCEPTED" : result.pending() ? "PENDING" : "REFUSED",
            result.message().getString());
        if (result.accepted()) {
            line += String.format(Locale.ROOT, " [resolved target %.1f %.1f %.1f]", result.targetX(), result.targetY(), result.targetZ());
        }
        if (player.lastMessage() != null) line += " | action bar: " + player.lastMessage().getString();
        return result.accepted() || result.pending() ? ok(c, line) : fail(c, line);
    }

    private static int service(CommandContext<CommandSourceStack> c, SiloAction action, boolean op) {
        AviationTestPlayer player = fake(c, op);
        BlockPos silo = BlockPosArgument.getBlockPos(c, "silo");
        LaunchResult result = AviationService.handleService(player, new AviationPayloads.SiloRequest(silo, action));
        String line = String.format(Locale.ROOT, "Test %s as %s from %.1f %.1f %.1f, silo %s: %s -- %s",
            action.name().toLowerCase(Locale.ROOT), op ? "operator" : "non-operator", player.getX(), player.getY(),
            player.getZ(), silo.toShortString(), result.accepted() ? "ACCEPTED" : "REFUSED", result.message().getString());
        if (player.lastMessage() != null) line += " | action bar: " + player.lastMessage().getString();
        return result.accepted() ? ok(c, line) : fail(c, line);
    }

    private static int ok(CommandContext<CommandSourceStack> c, String text) {
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> c, String text) {
        c.getSource().sendFailure(Component.literal(text));
        return 0;
    }
}
