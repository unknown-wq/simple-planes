package xyz.przemyk.simpleplanes.items;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.autopilot.AutopilotComponents;
import xyz.przemyk.simpleplanes.autopilot.AutopilotFeedback;
import xyz.przemyk.simpleplanes.autopilot.AutopilotOutput;
import xyz.przemyk.simpleplanes.autopilot.Helipad;
import xyz.przemyk.simpleplanes.autopilot.HelipadBrowser;
import xyz.przemyk.simpleplanes.autopilot.HelipadReport;
import xyz.przemyk.simpleplanes.autopilot.RotorcraftConfig;

import java.util.function.Consumer;

/**
 * Helipad Marker. Right-click two opposite corners of a landing pad and it surveys the square
 * between them, checks it, and registers it as a named helipad the autopilot can fly to.
 *
 * <h2>Why this is a separate item and not a third mode of the Runway Survey Tool</h2>
 * The parking mode on that tool earned its place by being <em>the second half of the same job</em>:
 * an apron only means anything beside a runway that has already been surveyed, so the survey ends by
 * switching the tool into parking mode and the player carries on clicking. A helipad is not the
 * second half of anything — it is a different object, in a different registry, used by a different
 * flight director, and marking one is a complete job on its own.
 *
 * <p>Three concrete reasons on top of that principle:
 *
 * <ul>
 *   <li><b>The gestures are already spent.</b> The runway tool uses right-click-block, sneak +
 *       right-click-block, right-click-air and sneak + right-click-air, and the last of those is the
 *       mode switch. A third mode makes the mode indicator the only way to know what a click will
 *       do, on a tool where one of the modes registers a permanent object.</li>
 *   <li><b>The clicks mean different things.</b> Two clicks on the runway tool are two ends of a
 *       line; two clicks here are two corners of an area. Putting both on one item means the same
 *       gesture builds a strip or a square depending on a mode a player set five minutes ago, and
 *       gets it wrong silently.</li>
 *   <li><b>A half-marked shape is stateful.</b> Both tools remember the first click on the stack.
 *       Sharing one anchor component between two shapes would let a mode switch turn half a runway
 *       into half a helipad; giving them separate components on one item is two anchors on one tool,
 *       which is a separate tool with extra steps.</li>
 * </ul>
 *
 * <p>Gestures:
 * <ul>
 *   <li><b>right-click a block</b> — mark one corner of the pad, then the opposite one, which runs
 *       the survey</li>
 *   <li><b>sneak + right-click a block</b> — cancel a half-marked pad</li>
 *   <li><b>right-click the air</b> — list the helipads, nearest first</li>
 *   <li><b>sneak + right-click the air</b> — survey the square the player is standing in the middle
 *       of, with the default radius. The one-gesture version, for the common case of "this spot,
 *       here"</li>
 * </ul>
 */
public class HelipadToolItem extends Item {

    /**
     * Radius used by the stand-here gesture, in blocks — a 7x7 pad.
     *
     * <p>Big enough that the centring probe has something to work with and the landing tolerance is
     * a couple of blocks, small enough that a player standing in a clearing is plausibly standing in
     * the middle of it. Marking the corners explicitly is how you get any other size.
     */
    private static final int STAND_HERE_RADIUS = 3;

    public HelipadToolItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = context.getItemInHand();

        if (context.isSecondaryUseActive()) {
            stack.remove(AutopilotComponents.HELIPAD_ANCHOR);
            AutopilotFeedback.info(player, "Helipad marking cancelled.");
            return InteractionResult.CONSUME;
        }

        BlockPos clicked = context.getClickedPos();
        BlockPos anchor = stack.get(AutopilotComponents.HELIPAD_ANCHOR);
        if (anchor == null) {
            stack.set(AutopilotComponents.HELIPAD_ANCHOR, clicked);
            AutopilotFeedback.info(player, "Pad corner at " + clicked.toShortString()
                + ". Now right-click the opposite corner.");
            return InteractionResult.CONSUME;
        }
        stack.remove(AutopilotComponents.HELIPAD_ANCHOR);
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.CONSUME;
        }
        survey(serverLevel, player, anchor, clicked);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.CONSUME;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (player.isSecondaryUseActive()) {
            // Stand in the middle and mark it. The pad a player wants is nearly always the one they
            // are standing on, and walking to two corners of a 7x7 to say so is three gestures where
            // one will do. The explicit corners are still there for any other size.
            stack.remove(AutopilotComponents.HELIPAD_ANCHOR);
            BlockPos here = player.blockPosition();
            survey(serverLevel, player,
                here.offset(-STAND_HERE_RADIUS, 0, -STAND_HERE_RADIUS),
                here.offset(STAND_HERE_RADIUS, 0, STAND_HERE_RADIUS));
            return InteractionResult.CONSUME;
        }
        HelipadBrowser.list(AutopilotOutput.toPlayer(player), serverLevel, player.position(),
            player.getName().getString());
        return InteractionResult.CONSUME;
    }

    /**
     * All the judgement lives in {@link HelipadReport}, so the item and
     * {@code /autopilot helipad survey} accept exactly the same pads and refuse them with exactly
     * the same words.
     */
    private static void survey(ServerLevel level, Player player, BlockPos cornerA, BlockPos cornerB) {
        Helipad pad = HelipadReport.surveyAndRegister(AutopilotOutput.toPlayer(player),
            level, cornerA, cornerB);
        if (pad != null) {
            AutopilotFeedback.success(player, "Fly to it with /autopilot heliflight \"" + pad.name()
                + "\" \"<other pad>\".");
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".helipad_tool_desc"));
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".helipad_tool_size",
            2 * RotorcraftConfig.MIN_PAD_RADIUS + 1, 2 * RotorcraftConfig.MAX_PAD_RADIUS + 1));
        BlockPos anchor = stack.get(AutopilotComponents.HELIPAD_ANCHOR);
        if (anchor != null) {
            builder.accept(Component.translatable(SimplePlanesMod.MODID + ".helipad_tool_anchor",
                anchor.toShortString()));
        }
    }
}
