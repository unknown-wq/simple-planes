package xyz.przemyk.simpleplanes.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
 * {@code /airliner} test aids, permission 2: {@code status}, {@code board <id>}, {@code logo <id> <0..5>}.
 * Every line also goes to the log at INFO. See design/reports/AGENT-3-REPORT.md.
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
                    airliner.getId(), villager.getId(), airliner.seatOf(villager)));
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

    private static String statusLine(AirlinerEntity airliner) {
        Vec3 v = airliner.position().subtract(airliner.xo, airliner.yo, airliner.zo);
        Vector3f fwd = airliner.transformPosPhysics(new Vector3f(0, 0, 1));
        StringBuilder seats = new StringBuilder();
        for (Entity passenger : airliner.getPassengers()) {
            // seat index @ distance along the nose axis from the airliner's origin
            Vec3 d = passenger.position().subtract(airliner.position());
            seats.append(seats.isEmpty() ? "" : ",").append(airliner.seatOf(passenger))
                .append(String.format(Locale.ROOT, "@%.2f", d.x * fwd.x() + d.y * fwd.y() + d.z * fwd.z()));
        }
        return String.format(Locale.ROOT,
            "#%d logo=%d item-logo=%s skin=%s riders=%d seats=[%s] pos=%.2f,%.2f,%.2f spd=%.3f pitch=%.1f og=%b thr=%d health=%d",
            airliner.getId(), airliner.getLogo(), itemLogo(airliner), airliner.hasMetalSkin() ? "metal" : "wood",
            airliner.getPassengers().size(), seats, airliner.getX(), airliner.getY(), airliner.getZ(), v.length(),
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
