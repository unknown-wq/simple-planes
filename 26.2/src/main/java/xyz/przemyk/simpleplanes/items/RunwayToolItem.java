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
import xyz.przemyk.simpleplanes.autopilot.Airfield;
import xyz.przemyk.simpleplanes.autopilot.AirfieldBrowser;
import xyz.przemyk.simpleplanes.autopilot.AirfieldReport;
import xyz.przemyk.simpleplanes.autopilot.AutopilotComponents;
import xyz.przemyk.simpleplanes.autopilot.AutopilotFeedback;
import xyz.przemyk.simpleplanes.autopilot.AutopilotSavedData;
import xyz.przemyk.simpleplanes.autopilot.AutopilotOutput;

import java.util.function.Consumer;

/**
 * Runway survey tool. Mark the two thresholds of a strip and it measures the runway, reports its
 * characteristics and registers it as a named airfield the autopilot can land at.
 *
 * <p>The tool has two modes, because an apron only means anything next to a runway that has already
 * been surveyed — it is the second half of the same job, not a separate one, so it did not deserve a
 * fourth item in the creative tab.
 *
 * <p><b>Survey mode</b> (the default):
 * <ul>
 *   <li>right-click a block — mark the first threshold, then the second (which runs the survey)</li>
 *   <li>sneak + right-click a block — cancel a half-marked runway</li>
 * </ul>
 *
 * <p><b>Parking mode</b>:
 * <ul>
 *   <li>right-click a block — mark it as a parking spot for the nearest airfield</li>
 *   <li>sneak + right-click a block — remove the marked spot nearest that block</li>
 * </ul>
 *
 * <p>In both modes, right-clicking the air lists the airfields and sneak + right-clicking the air
 * switches mode.
 */
public class RunwayToolItem extends Item {

    /**
     * How far from an airfield the tool will still attribute a parking spot to it. Generous
     * compared with {@code AutopilotConfig.PARKING_MAX_TAXI_DISTANCE}, which is the real limit and
     * is enforced by the validation: this only decides <em>which</em> airfield was meant, so that
     * clicking a little too far away produces "too far from the threshold" rather than "no airfield
     * near here", which is the more useful complaint.
     */
    private static final double PARKING_SEARCH_RADIUS = 256.0;

    public RunwayToolItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    private static boolean parkingMode(ItemStack stack) {
        return Boolean.TRUE.equals(stack.get(AutopilotComponents.PARKING_MODE));
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

        if (parkingMode(stack)) {
            return level instanceof ServerLevel serverLevel
                ? markParking(serverLevel, player, context.getClickedPos(), context.isSecondaryUseActive())
                : InteractionResult.CONSUME;
        }

        if (context.isSecondaryUseActive()) {
            stack.remove(AutopilotComponents.RUNWAY_ANCHOR);
            AutopilotFeedback.info(player, "Runway marking cancelled.");
            return InteractionResult.CONSUME;
        }

        BlockPos clicked = context.getClickedPos();
        BlockPos anchor = stack.get(AutopilotComponents.RUNWAY_ANCHOR);
        if (anchor == null) {
            stack.set(AutopilotComponents.RUNWAY_ANCHOR, clicked);
            AutopilotFeedback.info(player, "Threshold 1 at " + clicked.toShortString()
                + ". Now mark the far end of the runway.");
            return InteractionResult.CONSUME;
        }

        stack.remove(AutopilotComponents.RUNWAY_ANCHOR);
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.CONSUME;
        }

        double length = Math.sqrt(anchor.distSqr(clicked));
        if (length < 20) {
            AutopilotFeedback.warn(player, "That runway is only " + (int) length
                + " blocks long; mark at least 20 blocks apart.");
            return InteractionResult.CONSUME;
        }

        AirfieldReport.surveyAndRegister(AutopilotOutput.toPlayer(player), serverLevel, anchor, clicked);
        return InteractionResult.CONSUME;
    }

    /**
     * Marks or removes a parking spot on whichever airfield is nearest the click.
     *
     * <p>All the judgement lives in {@link AirfieldBrowser}, so the tool and
     * {@code /autopilot airfields park} accept exactly the same spots and refuse them with exactly
     * the same words. The tool's only job is to work out which airfield was meant.
     */
    private InteractionResult markParking(ServerLevel level, Player player, BlockPos clicked, boolean remove) {
        Airfield nearest = AutopilotSavedData.get(level)
            .nearest(clicked.getX() + 0.5, clicked.getZ() + 0.5, PARKING_SEARCH_RADIUS);
        if (nearest == null) {
            AutopilotFeedback.warn(player, "No surveyed airfield near here. Survey the runway first,"
                + " then mark where aircraft should park.");
            return InteractionResult.CONSUME;
        }
        AutopilotOutput output = AutopilotOutput.toPlayer(player);
        if (remove) {
            AirfieldBrowser.unpark(output, level, nearest.name(), clicked);
        } else {
            AirfieldBrowser.park(output, level, nearest.name(), clicked);
        }
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
            boolean parking = !parkingMode(stack);
            stack.set(AutopilotComponents.PARKING_MODE, parking);
            // A half-marked runway means nothing in parking mode, and leaving it set would make the
            // next survey click finish a runway the player has forgotten they started.
            stack.remove(AutopilotComponents.RUNWAY_ANCHOR);
            AutopilotFeedback.info(player, parking
                ? "Parking mode: right-click to mark where aircraft park, sneak + right-click to remove."
                : "Survey mode: right-click both ends of a runway.");
            return InteractionResult.CONSUME;
        }
        AirfieldBrowser.list(AutopilotOutput.toPlayer(player), serverLevel, player.position(),
            player.getName().getString());
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".runway_tool_desc"));
        builder.accept(Component.translatable(SimplePlanesMod.MODID
            + (parkingMode(stack) ? ".runway_tool_mode_parking" : ".runway_tool_mode_survey")));
        BlockPos anchor = stack.get(AutopilotComponents.RUNWAY_ANCHOR);
        if (anchor != null) {
            builder.accept(Component.translatable(SimplePlanesMod.MODID + ".runway_tool_anchor", anchor.toShortString()));
        }
    }

}
