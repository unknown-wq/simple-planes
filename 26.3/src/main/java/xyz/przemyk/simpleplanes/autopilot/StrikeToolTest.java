package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.items.PlaneStrikeToolItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /autopilot tooltest} — drives the strike tool through a fake player, so the item's own
 * code paths (right-click a block, right-click the air, sneak, the other hand) run headlessly.
 *
 * <pre>
 * /autopilot tooltest hold &lt;tool&gt; [&lt;other hand&gt;]   put items in its hands (components allowed)
 * /autopilot tooltest use &lt;target&gt;                   right-click the top of that block
 * /autopilot tooltest air [sneak]                      right-click the air
 * /autopilot tooltest run &lt;autopilot arguments&gt;      run /autopilot … as the fake player
 * /autopilot tooltest charge &lt;pos&gt; &lt;power&gt; [&lt;blocks&gt;|pierce]  set a fireless Blast off there
 * /autopilot tooltest crater snapshot &lt;from&gt; &lt;to&gt;      remember every block in that box
 * /autopilot tooltest crater diff &lt;centre&gt;            what changed since, and how far out
 * </pre>
 *
 * <p>The player stands at the command's position. Its chat, including the strike's outcome report
 * that arrives later, goes to the log as {@code [StrikeTest] +<ticks>t <message>}, the ticks counted
 * from the last {@code use}. A {@code run} command is queued behind the call that issued it, so its
 * effect shows in the next call's report.
 */
final class StrikeToolTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-autopilot");
    private static final GameProfile PROFILE =
        new GameProfile(UUID.fromString("5c1e0a11-0000-4000-8000-00000057a1c3"), "[StrikeTest]");

    private static @Nullable TestPlayer player;
    private static final Map<BlockPos, BlockState> snapshot = new HashMap<>();

    private StrikeToolTest() {}

    static LiteralArgumentBuilder<CommandSourceStack> node(CommandBuildContext registry) {
        return Commands.literal("tooltest")
            .then(Commands.literal("hold")
                .then(Commands.argument("tool", ItemArgument.item(registry))
                    .executes(c -> hold(c, false))
                    .then(Commands.argument("other", ItemArgument.item(registry))
                        .executes(c -> hold(c, true)))))
            .then(Commands.literal("use")
                .then(Commands.argument("target", BlockPosArgument.blockPos())
                    .executes(StrikeToolTest::use)))
            .then(Commands.literal("air")
                .executes(c -> air(c, false))
                .then(Commands.literal("sneak").executes(c -> air(c, true))))
            .then(Commands.literal("run")
                .then(Commands.argument("arguments", StringArgumentType.greedyString())
                    .executes(StrikeToolTest::run)))
            .then(Commands.literal("charge")
                .then(Commands.argument("pos", Vec3Argument.vec3(false))
                    .then(Commands.argument("power", FloatArgumentType.floatArg(0.0F, Blast.MAX_PIERCE_POWER))
                        .executes(c -> charge(c, true, false))
                        .then(Commands.literal("pierce").executes(c -> charge(c, false, true)))
                        .then(Commands.argument("blocks", BoolArgumentType.bool())
                            .executes(c -> charge(c, BoolArgumentType.getBool(c, "blocks"), false))))))
            .then(Commands.literal("crater")
                .then(Commands.literal("snapshot")
                    .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                            .executes(StrikeToolTest::snapshot))))
                .then(Commands.literal("diff")
                    .then(Commands.argument("centre", Vec3Argument.vec3(false))
                        .executes(StrikeToolTest::diff))));
    }

    /** The warhead on its own, through the same {@link Blast#detonate} path an aircraft takes. */
    private static int charge(CommandContext<CommandSourceStack> c, boolean blocks, boolean pierce) {
        Vec3 at = Vec3Argument.getVec3(c, "pos");
        float power = FloatArgumentType.getFloat(c, "power");
        if (power > Blast.MAX_POWER && !pierce) {
            c.getSource().sendFailure(Component.literal("[StrikeTest] above " + Blast.MAX_POWER + " only with pierce"));
            return 0;
        }
        Blast applied = new Blast(power, blocks, false, pierce)
            .detonate(c.getSource().getLevel(), null, at);
        c.getSource().sendSuccess(() -> Component.literal("[StrikeTest] charge at " + at + ": "
            + (applied == null ? "suppressed" : applied.describe())), false);
        return 1;
    }

    private static int snapshot(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        snapshot.clear();
        for (BlockPos pos : BlockPos.betweenClosed(BlockPosArgument.getBlockPos(c, "from"), BlockPosArgument.getBlockPos(c, "to"))) {
            snapshot.put(pos.immutable(), level.getBlockState(pos));
        }
        c.getSource().sendSuccess(() -> Component.literal("[StrikeTest] snapshot of " + snapshot.size() + " blocks"), false);
        return 1;
    }

    /**
     * Blocks changed since the snapshot, the farthest of them from {@code centre} (block centre to
     * point), and every fire block in the box, new or not.
     */
    private static int diff(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 centre = Vec3Argument.getVec3(c, "centre");
        int changed = 0;
        int fire = 0;
        double farthest = 0.0;
        List<String> offsets = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : snapshot.entrySet()) {
            BlockState now = level.getBlockState(e.getKey());
            if (now.getBlock() instanceof BaseFireBlock) {
                fire++;
            }
            if (now != e.getValue()) {
                changed++;
                farthest = Math.max(farthest, Vec3.atCenterOf(e.getKey()).distanceTo(centre));
                BlockPos d = e.getKey().subtract(BlockPos.containing(centre));
                offsets.add(d.getX() + "," + d.getY() + "," + d.getZ());
            }
        }
        String text = String.format(java.util.Locale.ROOT,
            "[StrikeTest] crater: %d changed, farthest %.2f from %s, fire %d; offsets %s",
            changed, farthest, centre, fire, offsets.size() <= 40 ? offsets : offsets.size() + " (too many)");
        LOGGER.info(text);
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static TestPlayer player(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        if (player == null || player.level() != level) {
            player = new TestPlayer(level);
        }
        Vec3 at = c.getSource().getPosition();
        player.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
        player.setShiftKeyDown(false);
        return player;
    }

    private static int hold(CommandContext<CommandSourceStack> c, boolean other) throws CommandSyntaxException {
        TestPlayer p = player(c);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemArgument.getItem(c, "tool").createItemStack(1));
        p.setItemInHand(InteractionHand.OFF_HAND,
            other ? ItemArgument.getItem(c, "other").createItemStack(1) : ItemStack.EMPTY);
        return report(c, p, "hold", InteractionResult.SUCCESS);
    }

    private static int use(CommandContext<CommandSourceStack> c) {
        TestPlayer p = player(c);
        BlockPos target = BlockPosArgument.getBlockPos(c, "target");
        p.launchedAt = p.level().getGameTime();
        ItemStack stack = p.getMainHandItem();
        InteractionResult result = p.gameMode.useItemOn(p, p.level(), stack, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(target).add(0, 0.5, 0), Direction.UP, target, false));
        return report(c, p, "use " + target.toShortString(), result);
    }

    private static int air(CommandContext<CommandSourceStack> c, boolean sneak) {
        TestPlayer p = player(c);
        p.setShiftKeyDown(sneak);
        InteractionResult result = p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        p.setShiftKeyDown(false);
        return report(c, p, sneak ? "sneak air" : "air", result);
    }

    private static int run(CommandContext<CommandSourceStack> c) {
        TestPlayer p = player(c);
        CommandSourceStack as = c.getSource().withEntity(p).withPosition(p.position());
        c.getSource().getServer().getCommands().performPrefixedCommand(as,
            "autopilot " + StringArgumentType.getString(c, "arguments"));
        return report(c, p, "run", InteractionResult.SUCCESS);
    }

    /** Prints what the player was told during this call, and the tool's settings afterwards. */
    private static int report(CommandContext<CommandSourceStack> c, TestPlayer p, String what, InteractionResult result) {
        List<String> said = new ArrayList<>(p.said);
        p.said.clear();
        ItemStack tool = p.getMainHandItem();
        String settings = tool.getItem() instanceof PlaneStrikeToolItem
            ? "distance " + PlaneStrikeToolItem.getDistance(tool) + ", blast "
                + PlaneStrikeToolItem.getBlast(tool).describe() + ", bearing " + PlaneStrikeToolItem.getBearing(tool)
                + ", type " + PlaneStrikeToolItem.getType(tool).getSerializedName()
                + (tool.has(AutopilotComponents.STRIKE_TYPE) ? "" : " (component absent)")
            : "no strike tool in the main hand";
        c.getSource().sendSuccess(() -> Component.literal("[StrikeTest] " + what + ": "
            + (result.consumesAction() ? "accepted" : "passed") + "; " + settings
            + "; other hand " + p.getOffhandItem().getItem() + "; said " + said), false);
        return 1;
    }

    private static final class TestPlayer extends FakePlayer {

        final List<String> said = new ArrayList<>();
        long launchedAt;

        TestPlayer(ServerLevel level) {
            super(level, PROFILE);
        }

        @Override
        public void sendSystemMessage(Component message) {
            capture(message);
        }

        @Override
        public void sendSystemMessage(Component message, boolean overlay) {
            capture(message);
        }

        private void capture(Component message) {
            String text = message.getString();
            said.add(text);
            LOGGER.info("[StrikeTest] +{}t {}", level().getGameTime() - launchedAt, text);
        }
    }
}
