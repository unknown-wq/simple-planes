package xyz.przemyk.simpleplanes.missile;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * {@code /missile}: build, load and fire silos, watch missiles in flight, and check that nothing was touched.
 * Every subcommand needs permission level 2 and runs from the console. Documented in MISSILES.md.
 */
public final class MissileCommand {

    private static final long MAX_HASH_VOLUME = 16_777_216L;

    private MissileCommand() {}

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("missile")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

            root.then(Commands.literal("silo")
                .then(Commands.literal("place")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("tier", IntegerArgumentType.integer(1, 4))
                            .executes(c -> place(c, false))
                            .then(Commands.argument("loaded", BoolArgumentType.bool())
                                .executes(c -> place(c, BoolArgumentType.getBool(c, "loaded")))))))
                .then(Commands.literal("remove")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(MissileCommand::remove)))
                .then(Commands.literal("load")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> load(c, true))))
                .then(Commands.literal("unload")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> load(c, false))))
                .then(Commands.literal("status")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(MissileCommand::status))));

            root.then(Commands.literal("launch")
                .then(Commands.argument("silo", BlockPosArgument.blockPos())
                    .then(Commands.argument("target", Vec3Argument.vec3())
                        .executes(MissileCommand::launch))));

            root.then(Commands.literal("list").executes(MissileCommand::list));
            root.then(Commands.literal("report")
                .executes(c -> report(c, 10))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                    .executes(c -> report(c, IntegerArgumentType.getInteger(c, "count")))));
            root.then(Commands.literal("abort")
                .then(Commands.literal("all").executes(MissileCommand::abortAll))
                .then(Commands.argument("id", IntegerArgumentType.integer(0)).executes(MissileCommand::abort)));
            root.then(Commands.literal("telemetry")
                .then(Commands.argument("interval", IntegerArgumentType.integer(0, 1200)).executes(MissileCommand::telemetry)));
            root.then(Commands.literal("tickets")
                .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(MissileCommand::tickets)));
            root.then(Commands.literal("hash")
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                    .then(Commands.argument("to", BlockPosArgument.blockPos())
                        .executes(c -> hash(c, false))
                        .then(Commands.literal("census").executes(c -> hash(c, true))))));

            dispatcher.register(root);
        });
    }

    private static int place(CommandContext<CommandSourceStack> c, boolean loaded) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        MissileTier tier = MissileTier.of(IntegerArgumentType.getInteger(c, "tier"));
        String problem = SiloStructure.checkPlacement(level, pos, tier);
        if (problem != null) return fail(c, "Cannot place a tier " + tier.tier + " silo at " + pos.toShortString() + ": " + problem + ".");
        SiloStructure.place(level, pos, tier);
        LaunchSiloBlockEntity be = silo(level, pos);
        if (be != null && loaded) be.load();
        Vec3 mouth = SiloStructure.mouth(pos, tier);
        return ok(c, String.format(Locale.ROOT, "Placed a tier %d silo at %s (%dx%d, %d deep, mouth %.1f %.1f %.1f)%s.",
            tier.tier, pos.toShortString(), tier.footprint, tier.footprint, tier.depthBlocks(), mouth.x, mouth.y, mouth.z,
            loaded ? ", loaded" : ""));
    }

    private static int remove(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos master = SiloStructure.masterOf(level, BlockPosArgument.getBlockPos(c, "pos"));
        if (master == null) return fail(c, "No silo there.");
        int n = SiloStructure.dismantle(level, master);
        return ok(c, "Removed the silo at " + master.toShortString() + " (" + n + " blocks).");
    }

    private static int load(CommandContext<CommandSourceStack> c, boolean load) {
        ServerLevel level = c.getSource().getLevel();
        LaunchSiloBlockEntity be = resolve(level, BlockPosArgument.getBlockPos(c, "pos"));
        if (be == null) return fail(c, "No silo there.");
        String problem = load ? be.load() : be.unload();
        if (problem != null) return fail(c, "Silo at " + be.getBlockPos().toShortString() + ": " + problem + ".");
        return ok(c, (load ? "Loaded a tier " : "Unloaded the tier ") + be.tier().tier + " missile " + (load ? "into" : "from")
            + " the silo at " + be.getBlockPos().toShortString() + ".");
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        LaunchSiloBlockEntity be = resolve(level, BlockPosArgument.getBlockPos(c, "pos"));
        if (be == null) return fail(c, "No silo there.");
        boolean intact = SiloStructure.isIntact(level, be.getBlockPos(), be.tier());
        return ok(c, be.describe() + (intact ? "" : ", STRUCTURE DAMAGED"));
    }

    private static int launch(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        LaunchSiloBlockEntity be = resolve(level, BlockPosArgument.getBlockPos(c, "silo"));
        if (be == null) return fail(c, "No silo there.");
        Vec3 target = Vec3Argument.getVec3(c, "target");
        String problem = be.launch(level, target);
        if (problem != null) return fail(c, "Silo at " + be.getBlockPos().toShortString() + " cannot launch: " + problem + ".");
        MissileTier tier = be.tier();
        Vec3 mouth = SiloStructure.mouth(be.getBlockPos(), tier);
        return ok(c, String.format(Locale.ROOT, "Silo at %s: hatch opening, tier %d missile to %s (%.1f blocks).",
            be.getBlockPos().toShortString(), tier.tier, MissileTracker.fmt(target), Math.hypot(target.x - mouth.x, target.z - mouth.z)));
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        List<MissileEntity> active = MissileTracker.active();
        if (active.isEmpty()) return ok(c, "No missiles in flight.");
        for (MissileEntity m : active) ok(c, m.telemetryLine());
        return active.size();
    }

    private static int report(CommandContext<CommandSourceStack> c, int count) {
        List<MissileTracker.Report> reports = MissileTracker.reports();
        if (reports.isEmpty()) return ok(c, "No flights have ended since the server started.");
        for (MissileTracker.Report r : reports.subList(Math.max(0, reports.size() - count), reports.size())) ok(c, r.line());
        return reports.size();
    }

    private static int abortAll(CommandContext<CommandSourceStack> c) {
        List<MissileEntity> active = MissileTracker.active();
        active.forEach(MissileEntity::abort);
        return ok(c, "Aborted " + active.size() + " missile(s).");
    }

    private static int abort(CommandContext<CommandSourceStack> c) {
        int id = IntegerArgumentType.getInteger(c, "id");
        MissileEntity m = MissileTracker.byId(id);
        if (m == null) return fail(c, "No missile #" + id + " in flight.");
        m.abort();
        return ok(c, "Aborted missile #" + id + ".");
    }

    private static int telemetry(CommandContext<CommandSourceStack> c) {
        int interval = IntegerArgumentType.getInteger(c, "interval");
        MissileTracker.setTelemetry(interval);
        return ok(c, interval == 0 ? "Missile telemetry off." : "Missile telemetry every " + interval + " tick(s), to the server log.");
    }

    private static int tickets(CommandContext<CommandSourceStack> c) {
        boolean enabled = BoolArgumentType.getBool(c, "enabled");
        MissileTracker.setTicketsEnabled(enabled);
        return ok(c, enabled ? "Missile chunk tickets on." : "Missile chunk tickets OFF: test use only, missiles will stall outside loaded ground.");
    }

    private static int hash(CommandContext<CommandSourceStack> c, boolean census) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos a = BlockPosArgument.getBlockPos(c, "from");
        BlockPos b = BlockPosArgument.getBlockPos(c, "to");
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (volume > MAX_HASH_VOLUME) return fail(c, "Region is " + volume + " blocks; the limit is " + MAX_HASH_VOLUME + ".");
        long h = 0xcbf29ce484222325L;
        long nonAir = 0;
        java.util.Map<BlockState, Integer> counts = new java.util.HashMap<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x <= max.getX(); x++)
            for (int z = min.getZ(); z <= max.getZ(); z++)
                for (int y = min.getY(); y <= max.getY(); y++) {
                    BlockState state = level.getBlockState(p.set(x, y, z));
                    int id = Block.getId(state);
                    if (!state.isAir()) nonAir++;
                    if (census) counts.merge(state, 1, Integer::sum);
                    for (int k = 0; k < 4; k++) {
                        h ^= (id >>> (8 * k)) & 0xFF;
                        h *= 0x100000001b3L;
                    }
                }
        ok(c, String.format(Locale.ROOT, "Region %s .. %s: %d blocks, %d non-air, hash %016x",
            min.toShortString(), max.toShortString(), volume, nonAir, h));
        if (census) {
            counts.entrySet().stream()
                .sorted(java.util.Map.Entry.<BlockState, Integer>comparingByValue().reversed())
                .forEach(e -> ok(c, "  " + e.getValue() + " x " + e.getKey()));
        }
        return 1;
    }

    private static @Nullable LaunchSiloBlockEntity resolve(ServerLevel level, BlockPos pos) {
        BlockPos master = SiloStructure.masterOf(level, pos);
        return master == null ? null : silo(level, master);
    }

    private static @Nullable LaunchSiloBlockEntity silo(ServerLevel level, BlockPos master) {
        BlockEntity be = level.getBlockEntity(master);
        return be instanceof LaunchSiloBlockEntity silo ? silo : null;
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
