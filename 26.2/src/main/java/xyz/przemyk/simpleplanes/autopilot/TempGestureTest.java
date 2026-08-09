package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;

import java.util.UUID;

/**
 * TEMPORARY test scaffolding — not for commit.
 *
 * <p>Drives {@code HelipadToolItem} through the same {@code useOn}/{@code use} entry points a real
 * player's right-click reaches, with a server-side player object standing in for the client. It
 * exists to answer "is the marking gesture usable by a human" on a rig that has no client.
 */
public final class TempGestureTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("helipad-gesture-test");

    private TempGestureTest() {}

    /** A player that can be talked to without a network connection. */
    private static final class TestPilot extends ServerPlayer {
        TestPilot(ServerLevel level) {
            super(level.getServer(), level,
                new GameProfile(UUID.nameUUIDFromBytes("TestPilot".getBytes()), "TestPilot"),
                ClientInformation.createDefault());
        }

        @Override
        public void sendSystemMessage(Component message) {
            LOGGER.info("[pilot] " + message.getString());
        }

        @Override
        public void sendSystemMessage(Component message, boolean overlay) {
            LOGGER.info("[pilot] " + message.getString());
        }
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(Commands.literal("gesturetest")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("what", StringArgumentType.word())
                    .then(Commands.argument("a", BlockPosArgument.blockPos())
                        .executes(context -> run(context, null))
                        .then(Commands.argument("b", BlockPosArgument.blockPos())
                            .executes(context -> run(context, "b")))))));
    }

    private static int run(CommandContext<CommandSourceStack> context, String second) {
        ServerLevel level = context.getSource().getLevel();
        String what = StringArgumentType.getString(context, "what");
        BlockPos a = BlockPosArgument.getBlockPos(context, "a");
        TestPilot pilot = new TestPilot(level);
        pilot.setPos(a.getX() + 0.5, a.getY() + 1.0, a.getZ() + 0.5);
        ItemStack tool = SimplePlanesItems.HELIPAD_TOOL.get().getDefaultInstance();
        pilot.setItemInHand(InteractionHand.MAIN_HAND, tool);

        if ("here".equals(what)) {
            LOGGER.info("[test] sneak + right-click the air at " + a.toShortString());
            pilot.setShiftKeyDown(true);
            tool.getItem().use(level, pilot, InteractionHand.MAIN_HAND);
            return 1;
        }
        if ("list".equals(what)) {
            LOGGER.info("[test] right-click the air");
            pilot.setShiftKeyDown(false);
            tool.getItem().use(level, pilot, InteractionHand.MAIN_HAND);
            return 1;
        }
        if ("cancel".equals(what)) {
            LOGGER.info("[test] right-click " + a.toShortString() + ", then sneak + right-click it");
            click(level, pilot, tool, a, false);
            LOGGER.info("[test]   anchor now: " + tool.get(AutopilotComponents.HELIPAD_ANCHOR));
            click(level, pilot, tool, a, true);
            LOGGER.info("[test]   anchor now: " + tool.get(AutopilotComponents.HELIPAD_ANCHOR));
            return 1;
        }
        BlockPos b = second == null ? a : BlockPosArgument.getBlockPos(context, "b");
        LOGGER.info("[test] right-click corner " + a.toShortString());
        click(level, pilot, tool, a, false);
        LOGGER.info("[test]   anchor on the stack: " + tool.get(AutopilotComponents.HELIPAD_ANCHOR));
        LOGGER.info("[test] right-click corner " + b.toShortString());
        click(level, pilot, tool, b, false);
        LOGGER.info("[test]   anchor on the stack: " + tool.get(AutopilotComponents.HELIPAD_ANCHOR));
        return 1;
    }

    private static void click(ServerLevel level, ServerPlayer pilot, ItemStack tool, BlockPos pos,
                              boolean sneak) {
        pilot.setShiftKeyDown(sneak);
        BlockHitResult hit = new BlockHitResult(
            new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5), Direction.UP, pos, false);
        tool.getItem().useOn(new UseOnContext(level, pilot, InteractionHand.MAIN_HAND, tool, hit));
    }
}
