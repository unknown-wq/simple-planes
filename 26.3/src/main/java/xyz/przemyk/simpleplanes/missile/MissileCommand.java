package xyz.przemyk.simpleplanes.missile;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
                .then(Commands.literal("upgrade")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(MissileCommand::upgrade)))
                .then(Commands.literal("remove")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(MissileCommand::remove)))
                .then(Commands.literal("load")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> load(c, true))))
                .then(Commands.literal("unload")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> load(c, false))))
                .then(Commands.literal("status")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(MissileCommand::status)))
                .then(Commands.literal("reset")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(MissileCommand::reset))));

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
            root.then(Commands.literal("item")
                .then(Commands.literal("use")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("face", StringArgumentType.word())
                            .then(Commands.argument("yaw", FloatArgumentType.floatArg(-360.0F, 360.0F))
                                .executes(c -> itemUse(c, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                    .executes(c -> itemUse(c, IntegerArgumentType.getInteger(c, "count"))))))))
                .then(Commands.literal("break")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(MissileCommand::itemBreak)))
                .then(Commands.literal("recipe").executes(MissileCommand::itemRecipe)));
            root.then(Commands.literal("guard")
                .then(Commands.literal("add")
                    .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                            .executes(c -> guardAdd(c, false))
                            .then(Commands.literal("suppress").executes(c -> guardAdd(c, true))))))
                .then(Commands.literal("clear").executes(MissileCommand::guardClear))
                .then(Commands.literal("list").executes(MissileCommand::guardList)));
            root.then(Commands.literal("hash")
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                    .then(Commands.argument("to", BlockPosArgument.blockPos())
                        .executes(c -> hash(c, false))
                        .then(Commands.literal("census").executes(c -> hash(c, true)))
                        .then(Commands.literal("snapshot").executes(c -> snapshot(c, false)))
                        .then(Commands.literal("diff").executes(c -> snapshot(c, true))))));

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
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        BlockPos master = SiloStructure.masterOf(level, pos);
        if (master == null) return noSilo(c, level, pos);
        int n = SiloStructure.dismantle(level, master);
        return ok(c, "Removed the silo at " + master.toShortString() + " (" + n + " blocks put back as they were).");
    }

    private static int upgrade(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        BlockPos master = SiloStructure.masterOf(level, pos);
        if (master == null) return noSilo(c, level, pos);
        SiloStructure.Upgrade up = SiloStructure.upgrade(level, master, null, null);
        if (up.problem() != null) return fail(c, "Cannot upgrade the silo at " + master.toShortString() + ": " + up.problem() + ".");
        return ok(c, String.format(Locale.ROOT, "Upgraded the silo at %s to tier %d (%dx%d, %d deep, grew %s), master %s.",
            master.toShortString(), up.tier().tier, up.tier().footprint, up.tier().footprint, up.tier().depthBlocks(),
            up.grewToward(), up.master().toShortString()));
    }

    /** Test: a survival fake player uses {@code count} silo items on {@code face} of {@code pos}, looking along {@code yaw}. */
    private static int itemUse(CommandContext<CommandSourceStack> c, int count) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        Direction face = Direction.byName(StringArgumentType.getString(c, "face"));
        if (face == null) return fail(c, "Unknown face; use up, down, north, south, east or west.");
        float yaw = FloatArgumentType.getFloat(c, "yaw");
        SiloTestPlayer player = new SiloTestPlayer(level);
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        player.snapTo(hit.x, hit.y + 1.0, hit.z, yaw, 45.0F);
        ItemStack stack = new ItemStack(Missiles.LAUNCH_SILO_ITEM, count);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        InteractionResult result = player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND,
            new BlockHitResult(hit, face, pos, false));
        Component message = player.lastMessage();
        BlockPos master = SiloStructure.masterOf(level, pos);
        LaunchSiloBlockEntity be = master == null ? null : silo(level, master);
        String silo = be == null ? "no silo at " + pos.toShortString()
            : be.describe() + (SiloStructure.isIntact(level, master, be.tier()) ? ", intact" : ", STRUCTURE DAMAGED");
        return ok(c, String.format(Locale.ROOT, "item use: %s, %d of %d item(s) left, message \"%s\"; %s",
            result.consumesAction() ? "accepted" : "refused", player.getMainHandItem().getCount(), count,
            message == null ? "" : message.getString(), silo));
    }

    /** Test: a survival fake player breaks the block at {@code pos}; reports the silo items dropped. */
    private static int itemBreak(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        AABB around = new AABB(pos).inflate(8.0);
        Set<Integer> before = new HashSet<>();
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, around)) before.add(e.getId());
        String was = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        SiloTestPlayer player = new SiloTestPlayer(level);
        player.snapTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 0.0F, 90.0F);
        boolean broken = player.gameMode.destroyBlock(pos);
        int dropped = 0;
        int other = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, around)) {
            if (before.contains(e.getId())) continue;
            if (e.getItem().is(Missiles.LAUNCH_SILO_ITEM)) dropped += e.getItem().getCount();
            else other += e.getItem().getCount();
        }
        String now = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        return ok(c, String.format(Locale.ROOT, "item break at %s: %s, was %s, now %s, dropped %d silo item(s) and %d other item(s)",
            pos.toShortString(), broken ? "broken" : "not broken", was, now, dropped, other));
    }

    /** Test: looks the silo recipe up by its ingredients, the way a crafting table does. */
    private static int itemRecipe(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        ItemStack i = new ItemStack(Items.IRON_INGOT);
        ItemStack r = new ItemStack(Items.REDSTONE);
        ItemStack s = new ItemStack(Items.SMOOTH_STONE);
        CraftingInput grid = CraftingInput.of(3, 3, List.of(i, r, i, s, ItemStack.EMPTY, s, s, i, s));
        var found = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level);
        if (found.isEmpty()) return fail(c, "No crafting recipe matches iron/redstone/iron, stone/-/stone, stone/iron/stone.");
        ItemStack out = found.get().value().assemble(grid);
        return ok(c, "Recipe " + found.get().id().identifier() + " matches and gives " + out.getCount() + " x "
            + BuiltInRegistries.ITEM.getKey(out.getItem()) + ".");
    }

    private static int guardAdd(CommandContext<CommandSourceStack> c, boolean suppress) {
        BlockPos a = BlockPosArgument.getBlockPos(c, "from");
        BlockPos b = BlockPosArgument.getBlockPos(c, "to");
        AABB box = new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
            Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
        MissileTestGuard.add(new MissileTestGuard.Zone(c.getSource().getLevel().dimension(), box, suppress));
        return ok(c, "Test blast guard: protecting " + box + (suppress ? " (blasts reaching it are suppressed)"
            : " (blasts reaching it lose block damage and fire)") + ".");
    }

    private static int guardClear(CommandContext<CommandSourceStack> c) {
        return ok(c, "Test blast guard: cleared " + MissileTestGuard.clear() + " zone(s).");
    }

    private static int guardList(CommandContext<CommandSourceStack> c) {
        List<MissileTestGuard.Zone> zones = MissileTestGuard.zones();
        if (zones.isEmpty()) return ok(c, "Test blast guard: no zones.");
        for (MissileTestGuard.Zone z : zones) ok(c, "  " + z.dimension().identifier() + " " + z.box() + (z.suppress() ? " suppress" : " downgrade"));
        return zones.size();
    }

    private static int load(CommandContext<CommandSourceStack> c, boolean load) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        LaunchSiloBlockEntity be = resolve(level, pos);
        if (be == null) return noSilo(c, level, pos);
        String problem = load ? be.load() : be.unload();
        if (problem != null) return fail(c, "Silo at " + be.getBlockPos().toShortString() + ": " + problem + ".");
        return ok(c, (load ? "Loaded a tier " : "Unloaded the tier ") + be.tier().tier + " missile " + (load ? "into" : "from")
            + " the silo at " + be.getBlockPos().toShortString() + ".");
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        LaunchSiloBlockEntity be = resolve(level, pos);
        if (be == null) return noSilo(c, level, pos);
        String recovered = be.recoverIfStale();
        if (recovered != null) ok(c, "Silo at " + be.getBlockPos().toShortString() + " was stuck: " + recovered + ".");
        boolean intact = SiloStructure.isIntact(level, be.getBlockPos(), be.tier());
        return ok(c, be.describe() + (intact ? "" : ", STRUCTURE DAMAGED"));
    }

    private static int launch(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(c, "silo");
        LaunchSiloBlockEntity be = resolve(level, pos);
        if (be == null) return noSilo(c, level, pos);
        Vec3 target = Vec3Argument.getVec3(c, "target");
        String problem = be.launch(level, target);
        if (problem != null) return fail(c, "Silo at " + be.getBlockPos().toShortString() + " cannot launch: " + problem + ".");
        MissileTier tier = be.tier();
        Vec3 mouth = SiloStructure.mouth(be.getBlockPos(), tier);
        ok(c, String.format(Locale.ROOT, "Silo at %s: hatch opening, tier %d missile to %s (%.1f blocks).",
            be.getBlockPos().toShortString(), tier.tier, MissileTracker.fmt(target), Math.hypot(target.x - mouth.x, target.z - mouth.z)));
        if (LaunchSiloBlockEntity.frozen(level))
            fail(c, "The game is frozen (/tick freeze): the hatch won't move and the missile won't leave until /tick unfreeze.");
        return 1;
    }

    /** Op recovery: puts a silo back to idle whatever its phase; a missile not yet fired stays loaded. */
    private static int reset(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        LaunchSiloBlockEntity be = resolve(level, pos);
        if (be == null) return noSilo(c, level, pos);
        if (be.phase() == LaunchSiloBlockEntity.Phase.IDLE) return ok(c, "Silo at " + be.getBlockPos().toShortString() + " is already idle: " + be.describe() + ".");
        return ok(c, "Silo at " + be.getBlockPos().toShortString() + ": " + be.reset(level, "/missile silo reset") + ".");
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

    private record Snapshot(BlockPos min, BlockPos max, BlockState[] states) {}

    private static @Nullable Snapshot snapshot;

    /** Test: remembers every block state in the box, or counts the blocks that changed since, by transition. */
    private static int snapshot(CommandContext<CommandSourceStack> c, boolean diff) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos a = BlockPosArgument.getBlockPos(c, "from");
        BlockPos b = BlockPosArgument.getBlockPos(c, "to");
        BlockPos min = BlockPos.min(a, b);
        BlockPos max = BlockPos.max(a, b);
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (volume > MAX_HASH_VOLUME) return fail(c, "Region is " + volume + " blocks; the limit is " + MAX_HASH_VOLUME + ".");
        if (diff && (snapshot == null || !snapshot.min().equals(min) || !snapshot.max().equals(max)))
            return fail(c, "No snapshot of exactly this box.");
        BlockState[] states = new BlockState[(int) volume];
        java.util.Map<String, Integer> transitions = new java.util.HashMap<>();
        int changed = 0;
        int i = 0;
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(p);
            if (diff && state != snapshot.states()[i]) {
                changed++;
                transitions.merge(BuiltInRegistries.BLOCK.getKey(snapshot.states()[i].getBlock()).getPath() + " -> "
                    + BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), 1, Integer::sum);
            }
            states[i++] = state;
        }
        if (!diff) {
            snapshot = new Snapshot(min, max, states);
            return ok(c, "Snapshot of " + min.toShortString() + " .. " + max.toShortString() + ": " + volume + " blocks.");
        }
        ok(c, "Diff of " + min.toShortString() + " .. " + max.toShortString() + ": " + changed + " block(s) changed.");
        transitions.entrySet().stream()
            .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(e -> ok(c, "  " + e.getValue() + " x " + e.getKey()));
        return changed;
    }

    /** Any part of the silo, or the block just above its top (where a player standing on it has {@code ~ ~ ~}). */
    private static @Nullable LaunchSiloBlockEntity resolve(ServerLevel level, BlockPos pos) {
        BlockPos master = SiloStructure.masterOf(level, pos);
        if (master == null && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
            BlockPos below = SiloStructure.masterOf(level, pos.below());
            if (below != null && below.getY() == pos.getY() - 1) master = below;
        }
        return master == null ? null : silo(level, master);
    }

    private static int noSilo(CommandContext<CommandSourceStack> c, ServerLevel level, BlockPos pos) {
        return fail(c, "No silo at " + pos.toShortString() + " (that block is "
            + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()) + "). Give any block of the silo: look at its top"
            + " and press Tab, or stand on it and use ~ ~ ~.");
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
