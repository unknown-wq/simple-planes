package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.api.dispatch.AircraftStatus;
import xyz.przemyk.simpleplanes.api.dispatch.DispatchEvent;
import xyz.przemyk.simpleplanes.api.dispatch.DispatchListener;
import xyz.przemyk.simpleplanes.api.dispatch.DispatchOrder;
import xyz.przemyk.simpleplanes.api.dispatch.DispatchResult;
import xyz.przemyk.simpleplanes.api.dispatch.LandingZone;
import xyz.przemyk.simpleplanes.api.dispatch.LandingZoneSpec;
import xyz.przemyk.simpleplanes.api.dispatch.RotorcraftDispatch;
import xyz.przemyk.simpleplanes.entities.MiniHelicopterEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /autopilot medevac} and {@code /autopilot dispatch ...}: the dispatch API driven from the
 * console, for operators and for tests. Every action goes through {@link RotorcraftDispatch}.
 *
 * <p>Orders placed here belong to owner {@value #OWNER}; its listener is registered when the server
 * starts and posts each event to the log and to online operators.
 */
public final class DispatchCommand {

    public static final String OWNER = "simpleplanes:command";

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-dispatch");

    private static final SuggestionProvider<CommandSourceStack> PADS = (context, builder) ->
        SharedSuggestionProvider.suggest(AutopilotSavedData.get(context.getSource().getLevel()).helipadList()
            .stream().map(pad -> "\"" + pad.name() + "\""), builder);

    private DispatchCommand() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
            RotorcraftDispatch.registerListener(OWNER, new ConsoleListener(server)));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RotorcraftDispatch.unregisterListener(OWNER));
    }

    /** Posts events for command-issued orders to the log and to operators. */
    private record ConsoleListener(MinecraftServer server) implements DispatchListener {
        @Override
        public void onEvent(DispatchEvent event) {
            String line = String.format("[dispatch] %s %s at %d %d %d%s%s", shortId(event.aircraft()),
                event.type().name().toLowerCase(Locale.ROOT), event.x(), event.y(), event.z(),
                event.reason() == null ? "" : " (" + event.reason() + ")", event.aborted() ? " [aborted]" : "");
            LOGGER.info("{} order={} t={}", line, event.orderId(), event.gameTime());
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (server.getPlayerList().isOp(player.nameAndId())) {
                    player.sendSystemMessage(Component.literal(line));
                }
            }
        }
    }

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        // medevac <homePad> <target> [holdTicks] [crew <entity>] [radius <blocks>]
        root.then(Commands.literal("medevac")
            .then(Commands.argument("homePad", StringArgumentType.string()).suggests(PADS)
                .then(Commands.argument("target", BlockPosArgument.blockPos())
                    .executes(DispatchCommand::medevac)
                    .then(Commands.argument("holdTicks", IntegerArgumentType.integer(0, DispatchOrder.MAX_HOLD_TICKS))
                        .executes(DispatchCommand::medevac)
                        .then(crewOption())
                        .then(radiusOption())))));

        root.then(Commands.literal("dispatch")
            .then(Commands.literal("list").executes(DispatchCommand::list))
            .then(Commands.literal("status")
                .then(Commands.argument("aircraft", EntityArgument.entity()).executes(DispatchCommand::status)))
            .then(Commands.literal("deploy")
                .then(Commands.argument("pad", StringArgumentType.string()).suggests(PADS)
                    .executes(context -> deploy(context, false))
                    .then(Commands.literal("medical").executes(context -> deploy(context, true)))))
            .then(Commands.literal("stow")
                .then(Commands.argument("aircraft", EntityArgument.entity()).executes(DispatchCommand::stow)))
            .then(Commands.literal("send")
                .then(Commands.argument("aircraft", EntityArgument.entity())
                    .then(Commands.argument("homePad", StringArgumentType.string()).suggests(PADS)
                        .then(Commands.argument("target", BlockPosArgument.blockPos())
                            .then(Commands.argument("holdTicks", IntegerArgumentType.integer(0, DispatchOrder.MAX_HOLD_TICKS))
                                .executes(context -> send(context, true))
                                .then(Commands.literal("oneway").executes(context -> send(context, false))))))))
            .then(Commands.literal("load")
                .then(Commands.argument("aircraft", EntityArgument.entity())
                    .then(Commands.argument("passengers", EntityArgument.entities())
                        .executes(context -> load(context, RotorcraftDispatch.SEAT_ANY))
                        .then(Commands.literal("front").executes(context -> load(context, RotorcraftDispatch.SEAT_FRONT)))
                        .then(Commands.literal("litter").executes(context -> load(context, RotorcraftDispatch.SEAT_LITTER))))))
            .then(Commands.literal("unload")
                .then(Commands.argument("aircraft", EntityArgument.entity())
                    .executes(DispatchCommand::unloadAll)
                    .then(Commands.argument("passengers", EntityArgument.entities()).executes(DispatchCommand::unload))))
            .then(Commands.literal("extend")
                .then(Commands.argument("aircraft", EntityArgument.entity())
                    .then(Commands.argument("ticks", IntegerArgumentType.integer(1, DispatchOrder.MAX_HOLD_TICKS))
                        .executes(DispatchCommand::extend))))
            .then(Commands.literal("release")
                .then(Commands.argument("aircraft", EntityArgument.entity()).executes(DispatchCommand::release)))
            .then(Commands.literal("recall")
                .then(Commands.argument("aircraft", EntityArgument.entity()).executes(DispatchCommand::recall)))
            .then(Commands.literal("lz")
                .then(Commands.argument("target", BlockPosArgument.blockPos())
                    .executes(DispatchCommand::landingZone)
                    .then(Commands.argument("radius", IntegerArgumentType.integer(0, DispatchOrder.MAX_SEARCH_RADIUS))
                        .executes(DispatchCommand::landingZone)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> crewOption() {
        return Commands.literal("crew")
            .then(Commands.argument("crew", EntityArgument.entity())
                .executes(DispatchCommand::medevac)
                .then(radiusOption()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> radiusOption() {
        return Commands.literal("radius")
            .then(Commands.argument("radius", IntegerArgumentType.integer(0, DispatchOrder.MAX_SEARCH_RADIUS))
                .executes(DispatchCommand::medevac));
    }

    // ------------------------------------------------------------------ medevac

    /**
     * Out and back from a pad: uses an idle dispatch aircraft on the pad, else adopts a mini
     * helicopter standing on it, else deploys a new one in the medical livery.
     */
    private static int medevac(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String padName = StringArgumentType.getString(context, "homePad");
        BlockPos target = BlockPosArgument.getBlockPos(context, "target");
        int hold = has(context, "holdTicks") ? IntegerArgumentType.getInteger(context, "holdTicks")
            : DispatchOrder.DEFAULT_HOLD_TICKS;
        int radius = has(context, "radius") ? IntegerArgumentType.getInteger(context, "radius") : 0;
        Helipad pad = AutopilotSavedData.get(level).helipad(padName);
        if (pad == null) {
            source.sendFailure(HelipadBrowser.unknown(padName));
            return 0;
        }
        AutopilotSpawner.loadRegion(level, pad.touchdown());
        MiniHelicopterEntity plane = aircraftOnPad(level, pad);
        if (plane == null && !level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.containing(pad.centre()).pack())) {
            source.sendFailure(Component.literal(pad.name() + " is loading now; repeat the command in a moment."));
            return 0;
        }
        if (plane == null) {
            DispatchResult deployed = RotorcraftDispatch.tryDeploy(level, medicalItem(true), pad.name(), OWNER);
            if (!deployed.ok()) {
                source.sendFailure(Component.literal("Could not deploy onto " + pad.name() + ": "
                    + deployed.reason() + " - " + deployed.detail()));
                return 0;
            }
            plane = DispatchService.resolve(level, deployed.id());
            if (plane == null) {
                source.sendFailure(Component.literal("Deployed aircraft is not resolvable."));
                return 0;
            }
        }
        if (has(context, "crew")) {
            Entity crew = EntityArgument.getEntity(context, "crew");
            DispatchResult loaded = RotorcraftDispatch.loadPassenger(level, plane.getUUID(), crew,
                RotorcraftDispatch.SEAT_FRONT);
            if (!loaded.ok()) {
                source.sendFailure(Component.literal("Could not board the crew: " + loaded.reason()
                    + " - " + loaded.detail()));
                return 0;
            }
        }
        CompoundTag data = new CompoundTag();
        data.putString("issued_by", source.getTextName());
        DispatchOrder order = new DispatchOrder(OWNER, pad.name(), target.getX(), target.getY(), target.getZ(),
            radius, hold, true, 0, data);
        DispatchResult result = RotorcraftDispatch.dispatch(level, plane.getUUID(), order);
        if (!result.ok()) {
            source.sendFailure(Component.literal("Dispatch refused: " + result.reason() + " - " + result.detail()));
            return 0;
        }
        MiniHelicopterEntity sent = plane;
        source.sendSuccess(() -> Component.literal("Medevac " + shortId(sent.getUUID()) + " (#" + sent.getId()
            + ") from " + pad.name() + " to " + target.toShortString() + ", hold " + hold + " ticks, "
            + Math.round(Math.sqrt(pad.centre().distSqr(target))) + " blocks; order "
            + shortId(result.id()) + ". Aircraft " + sent.getUUID()), true);
        return 1;
    }

    private static @Nullable MiniHelicopterEntity aircraftOnPad(ServerLevel level, Helipad pad) {
        AABB box = AABB.ofSize(pad.touchdown(), (pad.radius() + 1) * 2.0, 6.0, (pad.radius() + 1) * 2.0);
        List<MiniHelicopterEntity> found = level.getEntities(EntityTypeTest.forClass(MiniHelicopterEntity.class), box,
            plane -> plane.isAlive() && (plane.getAutopilot() == null || !plane.getAutopilot().isActive())
                && plane.getPassengers().stream().noneMatch(p -> p instanceof net.minecraft.world.entity.player.Player));
        return found.isEmpty() ? null : found.getFirst();
    }

    private static ItemStack medicalItem(boolean medical) {
        ItemStack stack = new ItemStack(SimplePlanesItems.MINI_HELICOPTER_ITEM.get());
        if (medical) {
            CompoundTag tag = new CompoundTag();
            tag.putString("material", "minecraft:white_concrete");
            stack.set(SimplePlanesComponents.ENTITY_TAG.get(), tag);
        }
        return stack;
    }

    // ------------------------------------------------------------------ dispatch subcommands

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        List<UUID> all = DispatchService.allAircraft(level);
        source.sendSuccess(() -> Component.literal(all.size() + " dispatch aircraft, "
            + DispatchService.undelivered(level) + " undelivered events, listeners "
            + DispatchService.listenerOwners()), false);
        for (UUID id : all) {
            AircraftStatus status = RotorcraftDispatch.status(level, id);
            if (status != null) {
                source.sendSuccess(() -> Component.literal(describe(level, status)), false);
            }
        }
        return all.size();
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        Entity aircraft = EntityArgument.getEntity(context, "aircraft");
        AircraftStatus status = RotorcraftDispatch.status(level, aircraft.getUUID());
        if (status == null) {
            context.getSource().sendFailure(Component.literal("Not a dispatch aircraft."));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal(describe(level, status)), false);
        return 1;
    }

    private static String describe(ServerLevel level, AircraftStatus s) {
        StringBuilder line = new StringBuilder();
        line.append(shortId(s.aircraft())).append(' ').append(s.phase().name().toLowerCase(Locale.ROOT))
            .append(s.loaded() ? "" : " (not loaded)")
            .append(String.format(" at %.1f %.1f %.1f", s.x(), s.y(), s.z()))
            .append(" mode ").append(s.flightMode())
            .append(" home ").append(s.homePad()).append(s.atHome() ? " (here)" : "");
        if (s.hasLandingZone()) {
            line.append(" lz ").append(s.zoneX()).append(' ').append(s.zoneY()).append(' ').append(s.zoneZ());
        }
        if (s.holdTicksLeft() >= 0) {
            line.append(" hold ").append(s.holdTicksLeft()).append('t');
        }
        line.append(" riders ").append(s.passengers().size()).append('/').append(s.capacity());
        if (s.abortReason() != null) {
            line.append(" aborted ").append(s.abortReason());
        }
        if (s.ownerId() != null) {
            line.append(" owner ").append(s.ownerId());
        }
        return line.toString();
    }

    private static int deploy(CommandContext<CommandSourceStack> context, boolean medical) {
        CommandSourceStack source = context.getSource();
        String pad = StringArgumentType.getString(context, "pad");
        DispatchResult result = RotorcraftDispatch.tryDeploy(source.getLevel(), medicalItem(medical), pad, OWNER);
        if (!result.ok()) {
            source.sendFailure(Component.literal("Refused: " + result.reason() + " - " + result.detail()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Deployed " + result.id() + " (" + result.detail() + ")"), true);
        return 1;
    }

    private static int stow(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Entity aircraft = EntityArgument.getEntity(context, "aircraft");
        ItemStack item = RotorcraftDispatch.stow(source.getLevel(), aircraft.getUUID());
        if (item.isEmpty()) {
            source.sendFailure(Component.literal("Cannot stow: it must be idle and on the ground."));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null || !player.getInventory().add(item)) {
            source.getLevel().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(source.getLevel(),
                aircraft.getX(), aircraft.getY() + 0.5, aircraft.getZ(), item));
        }
        source.sendSuccess(() -> Component.literal("Stowed " + shortId(aircraft.getUUID()) + "."), true);
        return 1;
    }

    private static int send(CommandContext<CommandSourceStack> context, boolean returnHome) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Entity aircraft = EntityArgument.getEntity(context, "aircraft");
        BlockPos target = BlockPosArgument.getBlockPos(context, "target");
        DispatchOrder order = new DispatchOrder(OWNER, StringArgumentType.getString(context, "homePad"),
            target.getX(), target.getY(), target.getZ(), 0, IntegerArgumentType.getInteger(context, "holdTicks"),
            returnHome, 0, null);
        DispatchResult result = RotorcraftDispatch.dispatch(level, aircraft.getUUID(), order);
        if (!result.ok()) {
            source.sendFailure(Component.literal("Refused: " + result.reason() + " - " + result.detail()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Order " + shortId(result.id()) + ": " + result.detail()), true);
        return 1;
    }

    private static int load(CommandContext<CommandSourceStack> context, int seat) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Entity aircraft = EntityArgument.getEntity(context, "aircraft");
        Collection<? extends Entity> passengers = EntityArgument.getEntities(context, "passengers");
        int loaded = 0;
        for (Entity passenger : passengers) {
            DispatchResult result = RotorcraftDispatch.loadPassenger(source.getLevel(), aircraft.getUUID(), passenger, seat);
            if (result.ok()) {
                loaded++;
                source.sendSuccess(() -> Component.literal("Boarded " + passenger.getName().getString()
                    + " (" + result.detail() + ")"), true);
            } else {
                source.sendFailure(Component.literal(passenger.getName().getString() + ": " + result.reason()
                    + " - " + result.detail()));
            }
        }
        return loaded;
    }

    private static int unload(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Entity aircraft = EntityArgument.getEntity(context, "aircraft");
        int count = 0;
        for (Entity passenger : EntityArgument.getEntities(context, "passengers")) {
            if (RotorcraftDispatch.unloadPassenger(source.getLevel(), aircraft.getUUID(), passenger.getUUID())) {
                count++;
            }
        }
        int done = count;
        source.sendSuccess(() -> Component.literal("Put off " + done + "."), true);
        return count;
    }

    private static int unloadAll(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Entity aircraft = EntityArgument.getEntity(context, "aircraft");
        int count = RotorcraftDispatch.unloadAll(source.getLevel(), aircraft.getUUID());
        source.sendSuccess(() -> Component.literal("Put off " + count + "."), true);
        return count;
    }

    private static int extend(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity aircraft = EntityArgument.getEntity(context, "aircraft");
        int ticks = IntegerArgumentType.getInteger(context, "ticks");
        return report(context, RotorcraftDispatch.extendHold(context.getSource().getLevel(), aircraft.getUUID(), ticks),
            "Hold extended by " + ticks + " ticks.", "Not holding or outbound.");
    }

    private static int release(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity aircraft = EntityArgument.getEntity(context, "aircraft");
        return report(context, RotorcraftDispatch.release(context.getSource().getLevel(), aircraft.getUUID()),
            "Released.", "Not holding at a target.");
    }

    private static int recall(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity aircraft = EntityArgument.getEntity(context, "aircraft");
        return report(context, RotorcraftDispatch.recall(context.getSource().getLevel(), aircraft.getUUID()),
            "Recalled.", "Nothing to recall.");
    }

    private static int landingZone(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos target = BlockPosArgument.getBlockPos(context, "target");
        int radius = has(context, "radius") ? IntegerArgumentType.getInteger(context, "radius")
            : DispatchOrder.DEFAULT_SEARCH_RADIUS;
        long start = System.nanoTime();
        LandingZoneFinder finder = DispatchService.findNow(level, target.getX(), target.getZ(), radius,
            LandingZoneSpec.DEFAULT);
        long micros = (System.nanoTime() - start) / 1000;
        LandingZone zone = finder.result();
        String text = (zone == null ? "No landing zone" : String.format("Landing zone %d %d %d, %.1f blocks off,"
            + " sectors %s", zone.x(), zone.groundY(), zone.z(), zone.distance(),
            Integer.toBinaryString(zone.clearSectors())))
            + " - " + finder.diagnostics() + ", " + micros + " us.";
        source.sendSuccess(() -> Component.literal(text), false);
        return zone == null ? 0 : 1;
    }

    private static int report(CommandContext<CommandSourceStack> context, boolean ok, String yes, String no) {
        if (ok) {
            context.getSource().sendSuccess(() -> Component.literal(yes), true);
            return 1;
        }
        context.getSource().sendFailure(Component.literal(no));
        return 0;
    }

    private static boolean has(CommandContext<CommandSourceStack> context, String name) {
        for (ParsedCommandNode<CommandSourceStack> node : context.getNodes()) {
            if (node.getNode().getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    static String shortId(@Nullable UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }
}
