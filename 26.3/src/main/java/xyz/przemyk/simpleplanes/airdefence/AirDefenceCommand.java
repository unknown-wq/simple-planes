package xyz.przemyk.simpleplanes.airdefence;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import xyz.przemyk.simpleplanes.missile.MissileTracker;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.autopilot.AircraftType;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.items.PlaneItem;
import xyz.przemyk.simpleplanes.missile.LaunchSiloBlockEntity;
import xyz.przemyk.simpleplanes.missile.MissileEntity;
import xyz.przemyk.simpleplanes.missile.MissileTier;
import xyz.przemyk.simpleplanes.missile.SiloStructure;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * {@code /airdefence}: aircraft allegiance, the aircraft AD can see, a dry run of a silo's target choice and
 * the per-tier interceptor figures. Permission level 2. Documented in MISSILES.md.
 */
public final class AirDefenceCommand {

    private AirDefenceCommand() {}

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("airdefence")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

            root.then(Commands.literal("aircraft")
                .executes(c -> aircraft(c, Double.MAX_VALUE))
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1.0))
                    .executes(c -> aircraft(c, DoubleArgumentType.getDouble(c, "radius")))));

            root.then(Commands.literal("allegiance")
                .then(Commands.argument("targets", EntityArgument.entities())
                    .executes(c -> allegiance(c, null))
                    .then(Commands.literal("friendly").executes(c -> allegiance(c, Allegiance.FRIENDLY)))
                    .then(Commands.literal("hostile").executes(c -> allegiance(c, Allegiance.HOSTILE)))));

            root.then(Commands.literal("item")
                .then(Commands.literal("friendly").executes(c -> item(c, Allegiance.FRIENDLY)))
                .then(Commands.literal("hostile").executes(c -> item(c, Allegiance.HOSTILE))));

            root.then(Commands.literal("scan")
                .then(Commands.argument("silo", BlockPosArgument.blockPos()).executes(AirDefenceCommand::scan)));

            root.then(Commands.literal("spec").executes(AirDefenceCommand::spec));

            root.then(Commands.literal("engagements").executes(AirDefenceCommand::engagements));

            root.then(Commands.literal("mode")
                .then(Commands.argument("silo", BlockPosArgument.blockPos())
                    .then(Commands.literal("strike").executes(c -> mode(c, LaunchSiloBlockEntity.Mode.MANUAL)))
                    .then(Commands.literal("air_defence").executes(c -> mode(c, LaunchSiloBlockEntity.Mode.AIR_DEFENCE)))));

            root.then(Commands.literal("tickets")
                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(AirDefenceCommand::tickets)));

            root.then(Commands.literal("click")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(c -> click(c, ItemStack.EMPTY, false))
                    .then(Commands.argument("item", ItemArgument.item(registry))
                        .executes(c -> click(c, ItemArgument.getItem(c, "item").createItemStack(1), false))
                        .then(Commands.literal("sneak")
                            .executes(c -> click(c, ItemArgument.getItem(c, "item").createItemStack(1), true))))));

            root.then(Commands.literal("place")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .then(Commands.argument("item", ItemArgument.item(registry))
                        .executes(AirDefenceCommand::place))));

            dispatcher.register(root);
            AllegianceOption.graft(dispatcher);
        });
    }

    private static int aircraft(CommandContext<CommandSourceStack> c, double radius) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 from = c.getSource().getPosition();
        List<PlaneEntity> planes = AircraftRoster.loaded(level).stream()
            .filter(p -> p.position().distanceTo(from) <= radius)
            .sorted(Comparator.comparingDouble(p -> p.position().distanceTo(from)))
            .toList();
        ok(c, planes.size() + " aircraft loaded" + (radius < Double.MAX_VALUE ? " within " + (int) radius : "") + ".");
        for (PlaneEntity p : planes) ok(c, "  " + line(p, from));
        return planes.size();
    }

    static String line(PlaneEntity p, Vec3 from) {
        AircraftType type = AircraftType.of(p);
        Vec3 pos = p.position();
        return String.format(Locale.ROOT, "#%d %s %s pos=%.1f,%.1f,%.1f spd=%.2f dist=%.1f%s", p.getId(),
            type == null ? net.minecraft.world.entity.EntityType.getKey(p.getType()).getPath() : type.getSerializedName(), p.getAllegiance().getSerializedName(),
            pos.x, pos.y, pos.z, p.getDeltaMovement().length(), pos.distanceTo(from),
            p.isAutopilotEngaged() ? " autopilot" : "");
    }

    private static int allegiance(CommandContext<CommandSourceStack> c, @Nullable Allegiance set) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(c, "targets");
        Vec3 from = c.getSource().getPosition();
        int n = 0;
        for (Entity e : targets) {
            if (!(e instanceof PlaneEntity p)) continue;
            if (set != null) p.setAllegiance(set);
            ok(c, "  " + line(p, from));
            n++;
        }
        if (n == 0) return fail(c, "No aircraft among the targets (only planes and helicopters have an allegiance).");
        if (set != null) ok(c, n + " aircraft set " + set.getSerializedName() + ".");
        return n;
    }

    private static int item(CommandContext<CommandSourceStack> c, Allegiance set) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof PlaneItem)) stack = player.getOffhandItem();
        if (!(stack.getItem() instanceof PlaneItem)) return fail(c, "Hold a plane or helicopter item.");
        CompoundTag tag = stack.get(SimplePlanesComponents.ENTITY_TAG);
        tag = tag == null ? new CompoundTag() : tag.copy();
        tag.putString(Allegiance.NBT_KEY, set.getSerializedName());
        stack.set(SimplePlanesComponents.ENTITY_TAG, tag);
        return ok(c, "The held " + stack.getHoverName().getString() + " now places a " + set.getSerializedName() + " aircraft.");
    }

    private static int scan(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos master = SiloStructure.masterOf(level, BlockPosArgument.getBlockPos(c, "silo"));
        if (master == null || !(level.getBlockEntity(master) instanceof LaunchSiloBlockEntity silo)) return fail(c, "No silo there.");
        MissileTier tier = silo.tier();
        InterceptorSpec spec = InterceptorSpec.of(tier);
        Vec3 mouth = SiloStructure.mouth(master, tier);
        long now = level.getGameTime();
        ok(c, String.format(Locale.ROOT, "Silo T%d at %s: detection %.0f, range %.0f, speed %.1f, fuse %.1f.",
            tier.tier, master.toShortString(), spec.detectionRadius(), spec.range, spec.speed(), spec.fuseRadius));
        for (PlaneEntity p : AircraftRoster.loaded(level)) {
            double d = AircraftRoster.aimPoint(p).distanceTo(mouth);
            String why = !p.isHostile() ? "friendly, ignored"
                : d > spec.detectionRadius() ? "out of detection"
                : p.getHealth() <= 0 ? "shot down, ignored"
                : Engagements.saturated(p.getUUID(), now, Engagements.silo(level, master))
                    ? "already engaged by " + engager(Engagements.holder(p.getUUID(), now, Engagements.silo(level, master)))
                : "candidate" + (Engagements.lastMiss(p.getUUID(), now) instanceof Engagements.Miss m ? " (follow-up: " + m.describe(now) + ")" : "");
            ok(c, String.format(Locale.ROOT, "  #%d %s d=%.1f: %s", p.getId(), p.getAllegiance().getSerializedName(), d, why));
        }
        PlaneEntity pick = TargetSelector.select(level, master, tier, Engagements.silo(level, master));
        return pick == null ? ok(c, "Target: none.") : ok(c, "Target: #" + pick.getId() + ".");
    }

    private static int spec(CommandContext<CommandSourceStack> c) {
        for (InterceptorSpec s : InterceptorSpec.values()) {
            ok(c, String.format(Locale.ROOT, "T%d: speed %.1f b/t, accel %.2f, range %.0f, detection %.0f, fuse %.1f, turn %.0f deg/t, hatch %d t, flight limit %d t",
                s.tier.tier, s.speed(), s.tier.accel, s.range, s.detectionRadius(), s.fuseRadius, s.turnRate, s.hatchTicks, s.maxFlightTicks()));
        }
        return ok(c, "At most " + InterceptorSpec.MAX_PER_TARGET + " missile per target at a time (a follow-up only after it ends"
            + " without a kill); fuel = range; silos scan every " + InterceptorSpec.SCAN_INTERVAL + " ticks.");
    }

    /** Every live claim (who holds which aircraft, and why a follow-up), then the recent follow-up shots. */
    private static int engagements(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        long now = level.getGameTime();
        var claims = Engagements.claims(now);
        ok(c, claims.size() + " engagement(s).");
        for (var e : claims) {
            Engagements.Claim cl = e.getValue();
            ok(c, String.format(Locale.ROOT, "  %s holding #%d for %.1f s%s", engager(e.getKey()), cl.targetEntityId(),
                (now - cl.since()) / 20.0, cl.note() == null ? ", first shot" : ", " + cl.note()));
        }
        List<Engagements.FollowUp> follow = Engagements.followUps();
        if (!follow.isEmpty()) ok(c, "Recent follow-up shots:");
        for (Engagements.FollowUp f : follow) {
            ok(c, String.format(Locale.ROOT, "  %.1f s ago silo %s fired again at #%d: %s", (now - f.at()) / 20.0,
                f.silo().toShortString(), f.targetEntityId(), f.why()));
        }
        return claims.size();
    }

    private static String engager(Engagements.@Nullable Engager e) {
        if (e instanceof Engagements.SiloEngager s) return "silo " + s.pos().toShortString() + " (launch sequence)";
        if (e instanceof Engagements.MissileEngager m) {
            MissileEntity missile = MissileTracker.byId(m.id());
            return missile == null ? "missile #" + m.id()
                : "missile #" + m.id() + " from silo " + missile.silo().toShortString() + " (" + missile.fuelLine() + ")";
        }
        return "nobody";
    }

    private static int mode(CommandContext<CommandSourceStack> c, LaunchSiloBlockEntity.Mode mode) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos master = SiloStructure.masterOf(level, BlockPosArgument.getBlockPos(c, "silo"));
        if (master == null || !(level.getBlockEntity(master) instanceof LaunchSiloBlockEntity silo)) return fail(c, "No silo there.");
        String problem = silo.setMode(mode);
        if (problem != null) return fail(c, "Silo at " + master.toShortString() + " cannot switch mode: " + problem + ".");
        return ok(c, "Silo at " + master.toShortString() + " is in " + silo.mode().label + " mode.");
    }

    /** Test: a survival fake player right-clicks the top of {@code pos} holding {@code stack} (empty hand by default). */
    private static int click(CommandContext<CommandSourceStack> c, ItemStack stack, boolean sneak) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        AirDefenceTestPlayer player = new AirDefenceTestPlayer(level);
        Vec3 hit = Vec3.atCenterOf(pos).add(0, 0.5, 0);
        player.snapTo(hit.x, hit.y + 1.0, hit.z, 0.0F, 90.0F);
        player.setShiftKeyDown(sneak);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        String held = stack.isEmpty() ? "empty hand" : stack.getItem().toString();
        InteractionResult result = player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND,
            new BlockHitResult(hit, Direction.UP, pos, false));
        BlockPos master = SiloStructure.masterOf(level, pos);
        String silo = master != null && level.getBlockEntity(master) instanceof LaunchSiloBlockEntity be ? be.describe() : "no silo";
        Component message = player.lastMessage();
        return ok(c, String.format(Locale.ROOT, "click with %s%s: %s, %d left, message \"%s\"; %s",
            held, sneak ? " (sneaking)" : "",
            result.consumesAction() ? "accepted" : result instanceof InteractionResult.Fail ? "refused" : "passed",
            player.getMainHandItem().getCount(), message == null ? "" : message.getString(), silo));
    }

    /** Test: a survival fake player standing on {@code pos} looking straight down uses {@code item} (a plane item). */
    private static int place(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        ItemStack stack = ItemArgument.getItem(c, "item").createItemStack(1);
        AirDefenceTestPlayer player = new AirDefenceTestPlayer(level);
        player.snapTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 0.0F, 90.0F);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        java.util.Set<Integer> before = new java.util.HashSet<>();
        for (PlaneEntity p : AircraftRoster.loaded(level)) before.add(p.getId());
        String held = stack.getItem().toString();
        InteractionResult result = player.gameMode.useItem(player, level, stack, InteractionHand.MAIN_HAND);
        ok(c, "place " + held + ": " + (result.consumesAction() ? "accepted" : "refused"));
        int n = 0;
        for (PlaneEntity p : AircraftRoster.loaded(level)) {
            if (before.contains(p.getId())) continue;
            ok(c, "  placed " + line(p, c.getSource().getPosition()));
            n++;
        }
        return n;
    }

    /** The chunk tickets on the chunk holding {@code pos}, whether it ticks, and whether a silo there holds a ticket. */
    private static int tickets(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        ChunkPos chunk = ChunkPos.containing(pos);
        TicketStorage storage = level.getDataStorage().get(TicketStorage.TYPE);
        List<Ticket> list = storage == null ? List.of() : storage.getTickets(chunk.pack());
        boolean loaded = level.hasChunk(chunk.x(), chunk.z());
        // never load the chunk to answer: masterOf reads block states
        BlockPos master = loaded ? SiloStructure.masterOf(level, pos) : null;
        return ok(c, String.format(Locale.ROOT, "chunk %d,%d: loaded %s, block-ticking %s, %d ticket(s) %s; silo ticket hold: %s",
            chunk.x(), chunk.z(), loaded, level.getChunkSource().isPositionTicking(chunk.pack()),
            list.size(), list, !loaded ? "not checked (chunk unloaded)" : master == null ? "no silo" : MissileTracker.holdsSilo(level, master) ? "YES" : "none"));
    }

    static int ok(CommandContext<CommandSourceStack> c, String text) {
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    static int fail(CommandContext<CommandSourceStack> c, String text) {
        c.getSource().sendFailure(Component.literal(text));
        return 0;
    }
}
