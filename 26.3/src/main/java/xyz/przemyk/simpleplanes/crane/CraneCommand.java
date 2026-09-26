package xyz.przemyk.simpleplanes.crane;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.QuadcopterEntity;
import xyz.przemyk.simpleplanes.entities.crane.SlungLoad;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /crane}: drives the quadcopter crane from the console (permission 2). Every line also goes to the
 * log. {@code debug ...} subcommands are test aids. Syntax in design/reports/AGENT-5-REPORT.md.
 */
public final class CraneCommand {

    public static final String TEST_TAG = "aircraft-test";

    /** Remote stacks used by {@code debug remote}, per crane id. */
    private static final Map<Integer, ItemStack> TEST_REMOTES = new HashMap<>();

    private CraneCommand() {}

    public static void register() {
        CraneRegistry.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.<CommandSourceStack>literal("crane")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

            root.then(Commands.literal("spawn").then(Commands.argument("pos", Vec3Argument.vec3(false))
                .executes(CraneCommand::spawn)));

            root.then(Commands.literal("goto").then(id()
                .then(Commands.argument("pos", Vec3Argument.vec3(false))
                    .executes(c -> goTo(c, false))
                    .then(Commands.argument("agl", DoubleArgumentType.doubleArg(0, 300)).executes(c -> goTo(c, true))))));

            root.then(Commands.literal("pickup").then(id()
                .then(Commands.argument("targets", EntityArgument.entities()).executes(CraneCommand::pickup))));

            root.then(Commands.literal("deliver").then(id()
                .then(Commands.argument("pos", Vec3Argument.vec3(false)).executes(CraneCommand::deliver))));

            root.then(Commands.literal("land").then(id().executes(c -> {
                QuadcopterEntity crane = crane(c);
                if (crane == null) {
                    return 0;
                }
                crane.orderLand(crane.getX(), crane.getZ());
                return say(c.getSource(), "Crane #" + crane.getId() + ": landing");
            })));

            root.then(Commands.literal("winch").then(id()
                .then(Commands.argument("length", DoubleArgumentType.doubleArg(SlungLoad.L_MIN, SlungLoad.L_MAX)).executes(c -> {
                    QuadcopterEntity crane = crane(c);
                    if (crane == null) {
                        return 0;
                    }
                    double length = DoubleArgumentType.getDouble(c, "length");
                    crane.orderWinch(length);
                    return say(c.getSource(), String.format(Locale.ROOT, "Crane #%d: winch to %.2f", crane.getId(), length));
                }))));

            root.then(Commands.literal("status")
                .executes(c -> {
                    List<QuadcopterEntity> all = cranes(c.getSource().getLevel());
                    if (all.isEmpty()) {
                        return say(c.getSource(), "no cranes");
                    }
                    all.forEach(crane -> say(c.getSource(), CraneFeedback.status(crane)));
                    return all.size();
                })
                .then(id().executes(c -> {
                    QuadcopterEntity crane = crane(c);
                    return crane == null ? 0 : say(c.getSource(), CraneFeedback.status(crane));
                })));

            root.then(Commands.literal("list").executes(c -> {
                List<QuadcopterEntity> all = cranes(c.getSource().getLevel());
                all.forEach(crane -> say(c.getSource(), String.format(Locale.ROOT, "#%d %s at %.1f %.1f %.1f load=%s",
                    crane.getId(), crane.getState(), crane.getX(), crane.getY(), crane.getZ(), crane.getLoadName())));
                return all.isEmpty() ? say(c.getSource(), "no cranes") : all.size();
            }));

            root.then(Commands.literal("stop")
                .then(Commands.literal("all").executes(c -> {
                    List<QuadcopterEntity> all = cranes(c.getSource().getLevel());
                    all.forEach(QuadcopterEntity::orderStop);
                    return say(c.getSource(), "stopped " + all.size() + " crane(s)");
                }))
                .then(id().executes(c -> {
                    QuadcopterEntity crane = crane(c);
                    if (crane == null) {
                        return 0;
                    }
                    crane.orderStop();
                    return say(c.getSource(), "Crane #" + crane.getId() + ": stop");
                })));

            root.then(Commands.literal("trace").then(id()
                .then(Commands.literal("on").executes(c -> trace(c, true)))
                .then(Commands.literal("off").executes(c -> trace(c, false)))));

            root.then(Commands.literal("kill").executes(CraneCommand::kill));

            LiteralArgumentBuilder<CommandSourceStack> debug = Commands.literal("debug");
            debug.then(Commands.literal("tmax").then(id()
                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0, 2)).executes(c -> {
                    QuadcopterEntity crane = crane(c);
                    if (crane == null) {
                        return 0;
                    }
                    crane.phys.tMaxBase = DoubleArgumentType.getDouble(c, "value");
                    return say(c.getSource(), String.format(Locale.ROOT, "Crane #%d: [test aid] T_MAX = %.4f (capacity %.2f, lift limit %.2f)",
                        crane.getId(), crane.phys.tMaxBase, SlungLoad.capacity(crane.phys.tMaxBase), SlungLoad.liftLimit(crane.phys.tMaxBase)));
                }))));
            debug.then(Commands.literal("swinggain").then(id()
                .then(Commands.argument("k", DoubleArgumentType.doubleArg(-5, 5)).executes(c -> {
                    QuadcopterEntity crane = crane(c);
                    if (crane == null) {
                        return 0;
                    }
                    crane.controller.swingGain = DoubleArgumentType.getDouble(c, "k");
                    return say(c.getSource(), String.format(Locale.ROOT, "Crane #%d: [test aid] swing gain = %.2f", crane.getId(), crane.controller.swingGain));
                }))));
            debug.then(Commands.literal("kick").then(id()
                .then(Commands.argument("degrees", DoubleArgumentType.doubleArg(-60, 60)).executes(c -> {
                    QuadcopterEntity crane = crane(c);
                    if (crane == null) {
                        return 0;
                    }
                    crane.debugKick(DoubleArgumentType.getDouble(c, "degrees"));
                    return say(c.getSource(), String.format(Locale.ROOT, "Crane #%d: [test aid] rope kicked to %.1f deg",
                        crane.getId(), Math.toDegrees(crane.rope.thetaX)));
                }))));
            RequiredArgumentBuilder<CommandSourceStack, Integer> remote = id();
            remote.then(Commands.literal("link").executes(c -> remote(c, "link")));
            remote.then(Commands.literal("recall").executes(c -> remote(c, "recall")));
            remote.then(Commands.literal("pickup").then(Commands.argument("target", EntityArgument.entity())
                .executes(c -> remote(c, "pickup"))));
            remote.then(Commands.literal("block").then(Commands.argument("block", BlockPosArgument.blockPos())
                .executes(c -> remote(c, "block"))));
            remote.then(Commands.literal("sneakblock").then(Commands.argument("block", BlockPosArgument.blockPos())
                .executes(c -> remote(c, "sneakblock"))));
            debug.then(Commands.literal("remote").then(remote));
            root.then(debug);

            dispatcher.register(root);
        });
    }

    /** Every crane in the level's loaded chunks, including ones that have not ticked yet. */
    private static List<QuadcopterEntity> cranes(ServerLevel level) {
        List<QuadcopterEntity> out = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof QuadcopterEntity crane && !crane.isRemoved()) {
                out.add(crane);
            }
        }
        out.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        return out;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> id() {
        return Commands.argument("id", IntegerArgumentType.integer(0));
    }

    private static int say(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
        CraneFeedback.LOGGER.info(line);
        return 1;
    }

    private static int fail(CommandSourceStack source, String line) {
        source.sendFailure(Component.literal(line));
        CraneFeedback.LOGGER.info(line);
        return 0;
    }

    private static @Nullable QuadcopterEntity crane(CommandContext<CommandSourceStack> context) {
        int id = IntegerArgumentType.getInteger(context, "id");
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            if (level.getEntity(id) instanceof QuadcopterEntity crane) {
                return crane;
            }
        }
        fail(context.getSource(), "no crane #" + id + " in loaded chunks");
        return null;
    }

    private static int spawn(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Vec3 pos = Vec3Argument.getVec3(context, "pos");
        QuadcopterEntity crane = SimplePlanesEntities.QUADCOPTER.get().create(level, EntitySpawnReason.COMMAND);
        if (crane == null) {
            return fail(source, "could not create a quadcopter");
        }
        crane.snapTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        crane.setQ(MathUtil.toQuaternionf(0, 0, 0));
        crane.setQ_Client(MathUtil.toQuaternionf(0, 0, 0));
        crane.setQ_prev(MathUtil.toQuaternionf(0, 0, 0));
        double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(pos).getX(), BlockPos.containing(pos).getZ());
        crane.initAt(pos, pos.y - ground > 0.5);
        crane.addTag(TEST_TAG);
        if (source.getEntity() instanceof Player player) {
            crane.setOwner(player.getUUID());
        }
        if (!level.addFreshEntity(crane)) {
            return fail(source, "could not add the quadcopter to the level");
        }
        CraneRegistry.track(crane);
        return say(source, String.format(Locale.ROOT, "Crane #%d spawned at %.2f %.2f %.2f%s", crane.getId(), pos.x, pos.y, pos.z,
            pos.y - ground > 0.5 ? " (hovering)" : " (parked)"));
    }

    private static int goTo(CommandContext<CommandSourceStack> context, boolean agl) {
        QuadcopterEntity crane = crane(context);
        if (crane == null) {
            return 0;
        }
        Vec3 pos = Vec3Argument.getVec3(context, "pos");
        if (agl) {
            ServerLevel level = (ServerLevel) crane.level();
            BlockPos b = BlockPos.containing(pos);
            pos = new Vec3(pos.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, b.getX(), b.getZ())
                + DoubleArgumentType.getDouble(context, "agl"), pos.z);
        }
        crane.orderGoto(pos);
        return say(context.getSource(), String.format(Locale.ROOT, "Crane #%d: going to %.2f %.2f %.2f", crane.getId(), pos.x, pos.y, pos.z));
    }

    private static int pickup(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        QuadcopterEntity crane = crane(context);
        if (crane == null) {
            return 0;
        }
        Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
        Entity first = targets.iterator().next();
        String refused = crane.orderPickup(first);
        if (refused != null) {
            return fail(context.getSource(), "Crane #" + crane.getId() + ": refused: " + refused);
        }
        return say(context.getSource(), "Crane #" + crane.getId() + ": picking up " + first.getName().getString() + " #" + first.getId());
    }

    private static int deliver(CommandContext<CommandSourceStack> context) {
        QuadcopterEntity crane = crane(context);
        if (crane == null) {
            return 0;
        }
        Vec3 pos = Vec3Argument.getVec3(context, "pos");
        crane.orderDeliver(pos);
        return say(context.getSource(), String.format(Locale.ROOT, "Crane #%d: delivery point %.2f %.2f %.2f", crane.getId(), pos.x, pos.y, pos.z));
    }

    private static int trace(CommandContext<CommandSourceStack> context, boolean on) {
        QuadcopterEntity crane = crane(context);
        if (crane == null) {
            return 0;
        }
        crane.setTrace(on);
        return say(context.getSource(), "Crane #" + crane.getId() + ": trace " + (on ? "on" : "off"));
    }

    private static int kill(CommandContext<CommandSourceStack> context) {
        int cranes = 0, loads = 0;
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            List<Entity> doomed = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof QuadcopterEntity) {
                    doomed.add(entity);
                    cranes++;
                } else if (entity.entityTags().contains(QuadcopterEntity.LOAD_TAG) && !(entity instanceof Player)) {
                    doomed.add(entity);
                    loads++;
                }
            }
            doomed.forEach(Entity::discard);
        }
        TEST_REMOTES.clear();
        return say(context.getSource(), "Removed " + cranes + " crane(s) and " + loads + " load(s)");
    }

    /**
     * Test aid: drives the real {@code CraneRemoteItem} methods with Fabric's fake player, which stands 3 b
     * from the crane (link) or where the block is (block orders). The fake player is not in the level's player
     * list, so a recall reports "owner out of range".
     */
    private static int remote(CommandContext<CommandSourceStack> context, String sub) throws CommandSyntaxException {
        QuadcopterEntity crane = crane(context);
        if (crane == null) {
            return 0;
        }
        ServerLevel level = (ServerLevel) crane.level();
        FakePlayer player = FakePlayer.get(level);
        ItemStack stack = TEST_REMOTES.computeIfAbsent(crane.getId(), k -> SimplePlanesItems.CRANE_REMOTE.get().getDefaultInstance());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.setShiftKeyDown(false);
        InteractionResult result;
        switch (sub) {
            case "link" -> {
                player.snapTo(crane.getX(), crane.getY() - 1.0, crane.getZ() - 3.0, 0.0F, 0.0F);
                player.lookAt(EntityAnchorArgument.Anchor.EYES, crane.getBoundingBox().getCenter());
                result = stack.getItem().use(level, player, InteractionHand.MAIN_HAND);
            }
            case "recall" -> {
                player.setShiftKeyDown(true);
                player.snapTo(crane.getX(), crane.getY() + 10.0, crane.getZ(), 0.0F, -90.0F);
                result = stack.getItem().use(level, player, InteractionHand.MAIN_HAND);
            }
            case "pickup" -> {
                Entity target = EntityArgument.getEntity(context, "target");
                if (!(target instanceof LivingEntity living)) {
                    return fail(context.getSource(), "not a living entity");
                }
                result = stack.getItem().interactLivingEntity(stack, player, living, InteractionHand.MAIN_HAND);
            }
            default -> {
                BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "block");
                player.setShiftKeyDown(sub.equals("sneakblock"));
                player.snapTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 2.5, 180.0F, 45.0F);
                BlockHitResult hit = new BlockHitResult(new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5), Direction.UP, pos, false);
                result = stack.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            }
        }
        player.setShiftKeyDown(false);
        UUID link = stack.get(SimplePlanesComponents.CRANE_LINK);
        return say(context.getSource(), String.format(Locale.ROOT, "Crane #%d: [test aid] remote %s -> %s, link=%s, state=%s",
            crane.getId(), sub, result, link == null ? "none" : link.equals(crane.getUUID()) ? "this crane" : link.toString(), crane.getState()));
    }
}
