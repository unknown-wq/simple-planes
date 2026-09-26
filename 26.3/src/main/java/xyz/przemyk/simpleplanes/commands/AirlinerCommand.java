package xyz.przemyk.simpleplanes.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.joml.Quaternionf;
import xyz.przemyk.simpleplanes.entities.AirlinerPartEntity;
import xyz.przemyk.simpleplanes.entities.AirlinerSeats;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.entities.AirlinerEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * {@code /airliner} test aids, permission 2: {@code status}, {@code board <id>}, {@code logo <id> <0..5>},
 * {@code click <id> <x> <y> <z> [player]}. Every line also goes to the log at INFO. See AIRLINER-MODEL.md.
 */
public final class AirlinerCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-airliner");
    private static final double BOARD_RANGE = 8.0;

    private AirlinerCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
            Commands.<CommandSourceStack>literal("airliner")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(AirlinerCommand::status))
                .then(Commands.literal("board")
                    .then(Commands.argument("id", IntegerArgumentType.integer()).executes(AirlinerCommand::board)))
                .then(Commands.literal("click")
                    .then(Commands.argument("id", IntegerArgumentType.integer())
                        .then(Commands.argument("point", Vec3Argument.vec3(false))
                            .executes(c -> click(c, false))
                            .then(Commands.argument("player", EntityArgument.player())
                                .executes(c -> click(c, true))))))
                .then(Commands.literal("logo")
                    .then(Commands.argument("id", IntegerArgumentType.integer())
                        .then(Commands.argument("logo", IntegerArgumentType.integer(0, AirlinerEntity.LOGO_COUNT - 1))
                            .executes(AirlinerCommand::logo))))));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        List<AirlinerEntity> found = new ArrayList<>();
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof AirlinerEntity airliner && airliner.isAlive()) {
                    found.add(airliner);
                }
            }
        }
        if (found.isEmpty()) {
            report(context.getSource(), "no airliners");
            return 0;
        }
        found.sort(Comparator.comparingInt(Entity::getId));
        for (AirlinerEntity airliner : found) {
            report(context.getSource(), statusLine(airliner));
        }
        return found.size();
    }

    private static int board(CommandContext<CommandSourceStack> context) {
        AirlinerEntity airliner = airliner(context);
        if (airliner == null) {
            return 0;
        }
        List<Villager> villagers = airliner.level().getEntitiesOfClass(Villager.class,
            airliner.getBoundingBox().inflate(BOARD_RANGE), v -> v.isAlive() && !v.isPassenger());
        villagers.sort(Comparator.comparingDouble(v -> v.distanceToSqr(airliner)));
        if (villagers.isEmpty()) {
            return fail(context.getSource(), "Airliner #" + airliner.getId() + ": no villager within " + (int) BOARD_RANGE + " b");
        }
        int boarded = 0;
        for (Villager villager : villagers) {
            if (villager.startRiding(airliner)) {
                boarded++;
                report(context.getSource(), String.format(Locale.ROOT, "Airliner #%d: villager #%d boarded, seat %d",
                    airliner.getId(), villager.getId(), airliner.seatOf(villager)) + " (" + seatName(airliner.seatOf(villager)) + ")");
            } else {
                report(context.getSource(), String.format(Locale.ROOT, "Airliner #%d: villager #%d refused, no free passenger seat",
                    airliner.getId(), villager.getId()));
            }
        }
        report(context.getSource(), String.format(Locale.ROOT, "Airliner #%d: %d boarded, riders=%d",
            airliner.getId(), boarded, airliner.getPassengers().size()));
        return boarded;
    }

    private static int logo(CommandContext<CommandSourceStack> context) {
        AirlinerEntity airliner = airliner(context);
        if (airliner == null) {
            return 0;
        }
        airliner.setLogo(IntegerArgumentType.getInteger(context, "logo"));
        report(context.getSource(), "Airliner #" + airliner.getId() + " logo=" + airliner.getLogo());
        return 1;
    }

    /**
     * A click at airliner-frame point (x, y, z), blocks, +z towards the nose, +x the left wing. Without a player
     * it only reports the seat a player clicking there would get; with one it runs the airliner's own
     * {@code interact} for that player with the point as the hit location, as a real click would.
     */
    private static int click(CommandContext<CommandSourceStack> context, boolean asPlayer) throws CommandSyntaxException {
        AirlinerEntity airliner = airliner(context);
        if (airliner == null) {
            return 0;
        }
        Vec3 point = Vec3Argument.getVec3(context, "point");
        Vector3f local = new Vector3f((float) point.x, (float) point.y, (float) point.z);
        if (!asPlayer) {
            int seat = airliner.chooseSeat(true, local.x(), local.z());
            report(context.getSource(), String.format(Locale.ROOT, "Airliner #%d: a player clicking %.2f, %.2f, %.2f boards seat %d (%s)",
                airliner.getId(), local.x(), local.y(), local.z(), seat, seatName(seat)));
            return seat >= 0 ? 1 : 0;
        }
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        Vector3f world = new Vector3f(local).rotate(airliner.frameRotation());
        InteractionResult result = airliner.interact(player, InteractionHand.MAIN_HAND, new Vec3(world.x(), world.y(), world.z()));
        int seat = player.getVehicle() == airliner ? airliner.seatOf(player) : -1;
        report(context.getSource(), String.format(Locale.ROOT, "Airliner #%d: %s clicked %.2f, %.2f, %.2f -> %s, seat %d (%s), controlling=%s",
            airliner.getId(), player.getScoreboardName(), local.x(), local.y(), local.z(), result.getClass().getSimpleName(), seat,
            seatName(seat), airliner.getControllingPassenger() == player));
        return seat >= 0 ? 1 : 0;
    }

    private static String seatName(int seat) {
        if (seat < 0) {
            return "none";
        }
        if (seat == AirlinerSeats.PILOT) {
            return "captain";
        }
        if (seat == AirlinerSeats.FIRST_OFFICER) {
            return "first officer";
        }
        int cabin = seat - AirlinerSeats.FIRST_CABIN_SEAT;
        return "row " + (cabin / 4 + 1) + (char) ('A' + cabin % 4);
    }

    private static String statusLine(AirlinerEntity airliner) {
        Vec3 v = airliner.position().subtract(airliner.xo, airliner.yo, airliner.zo);
        Quaternionf toLocal = airliner.frameRotation().conjugate();
        StringBuilder seats = new StringBuilder();
        for (Entity passenger : airliner.getPassengers()) {
            // seat index @ lateral, along the nose axis: the passenger's feet in the airliner's frame
            Vec3 d = passenger.position().subtract(airliner.position());
            Vector3f l = new Vector3f((float) d.x, (float) d.y, (float) d.z).rotate(toLocal);
            seats.append(seats.isEmpty() ? "" : ",").append(airliner.seatOf(passenger))
                .append(String.format(Locale.ROOT, "@%.2f/%.2f", l.x(), l.z()));
        }
        long parts = airliner.level().getEntitiesOfClass(AirlinerPartEntity.class, airliner.getBoundingBox().inflate(8),
            p -> p.parent() == airliner).size();
        return String.format(Locale.ROOT,
            "#%d logo=%d item-logo=%s skin=%s riders=%d seats=[%s] pilot=%s parts=%d pos=%.2f,%.2f,%.2f spd=%.3f pitch=%.1f og=%b thr=%d health=%d",
            airliner.getId(), airliner.getLogo(), itemLogo(airliner), airliner.hasMetalSkin() ? "metal" : "wood",
            airliner.getPassengers().size(), seats,
            airliner.getControllingPassenger() == null ? "none" : airliner.getControllingPassenger().getScoreboardName(), parts, airliner.getX(), airliner.getY(), airliner.getZ(), v.length(),
            airliner.getXRot(), airliner.getOnGround(), airliner.getThrottle(), airliner.getHealth());
    }

    private static String itemLogo(AirlinerEntity airliner) {
        ItemStack stack = airliner.getItemStack();
        CompoundTag tag = stack.get(SimplePlanesComponents.ENTITY_TAG.get());
        return tag == null ? "none" : tag.getInt("Logo").map(String::valueOf).orElse("none");
    }

    private static @Nullable AirlinerEntity airliner(CommandContext<CommandSourceStack> context) {
        int id = IntegerArgumentType.getInteger(context, "id");
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof AirlinerEntity airliner) {
                return airliner;
            }
            if (entity != null) {
                fail(context.getSource(), "Entity #" + id + " is not an airliner");
                return null;
            }
        }
        fail(context.getSource(), "No entity #" + id);
        return null;
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
}
